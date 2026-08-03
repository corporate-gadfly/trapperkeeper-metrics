(ns puppetlabs.trapperkeeper.services.metrics.otel-core-test
  (:import (com.puppetlabs.trapperkeeper.metrics PullMetricReader)
           (io.opentelemetry.api.metrics MeterProvider)
           (io.opentelemetry.sdk.metrics SdkMeterProvider))
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.trapperkeeper.services.metrics.metrics-service :refer [metrics-service
                                                                               metrics-webservice]]
            [puppetlabs.trapperkeeper.services.metrics.otel-core :as otel]
            [puppetlabs.trapperkeeper.services.protocols.metrics :as metrics-protocol]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting-service]
            [puppetlabs.trapperkeeper.services.webserver.jetty-service :as jetty-service]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.trapperkeeper.app :as app]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Test configs

(def base-config
  {:metrics {:server-id "localhost"}})

(def otel-enabled-config
  {:metrics {:server-id "localhost"
             :opentelemetry {:enabled true}}})

(def otel-with-v3-config
  {:metrics {:server-id "localhost"
             :opentelemetry {:enabled true}
             :metrics-webservice {:opentelemetry {:enabled true}}}
   :webserver {:port 8180
               :host "0.0.0.0"}
   :web-router-service {:puppetlabs.trapperkeeper.services.metrics.metrics-service/metrics-webservice
                         "/metrics"}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Unit tests — otel-core functions

(deftest sanitize-metric-name-test
  (testing "dots become underscores"
    (is (= "http_server_request_duration" (otel/sanitize-metric-name "http.server.request.duration"))))
  (testing "dashes become underscores"
    (is (= "my_counter" (otel/sanitize-metric-name "my-counter"))))
  (testing "slashes become underscores"
    (is (= "foo_bar_baz" (otel/sanitize-metric-name "foo/bar/baz"))))
  (testing "already clean names pass through"
    (is (= "simple_counter" (otel/sanitize-metric-name "simple_counter"))))
  (testing "colons are preserved"
    (is (= "my:counter" (otel/sanitize-metric-name "my:counter")))))

(deftest create-otel-context-disabled-test
  (testing "when OTEL is explicitly disabled, returns noop MeterProvider"
    (let [ctx (otel/create-otel-context "localhost" {:enabled false})]
      (is (= (MeterProvider/noop) (:meter-provider ctx)))
      (is (instance? PullMetricReader (:pull-reader ctx)))
      (is (nil? (:sdk ctx))))))

(deftest create-otel-context-enabled-test
  (testing "when OTEL is enabled by default (nil config), returns SdkMeterProvider"
    (let [ctx (otel/create-otel-context "test-server" nil)]
      (try
        (is (instance? SdkMeterProvider (:meter-provider ctx)))
        (is (instance? SdkMeterProvider (:sdk ctx)))
        (is (instance? PullMetricReader (:pull-reader ctx)))
        (finally
          (otel/shutdown-otel-context ctx)))))

  (testing "when OTEL is explicitly enabled, returns SdkMeterProvider"
    (let [ctx (otel/create-otel-context "test-server" {:enabled true})]
      (try
        (is (instance? SdkMeterProvider (:meter-provider ctx)))
        (is (instance? SdkMeterProvider (:sdk ctx)))
        (is (instance? PullMetricReader (:pull-reader ctx)))
        (finally
          (otel/shutdown-otel-context ctx))))))

(deftest pull-reader-collects-metrics-test
  (testing "PullMetricReader returns metrics recorded via the SdkMeterProvider"
    (let [ctx (otel/create-otel-context "test-server" {:enabled true})]
      (try
        (let [^SdkMeterProvider provider (:meter-provider ctx)
              meter   (.build (.meterBuilder provider "test.scope") )
              counter (.build (.counterBuilder meter "test.counter"))]
          ;; record some data
          (.add counter 42)
          ;; collect
          (let [metrics (.collectAllMetrics ^PullMetricReader (:pull-reader ctx))]
            (is (pos? (count metrics)))
            (is (some #(= "test.counter" (.getName %)) metrics))))
        (finally
          (otel/shutdown-otel-context ctx))))))

(deftest metrics-to-prometheus-format-test
  (testing "counter metrics render in Prometheus text format"
    (let [ctx (otel/create-otel-context "test-server" {:enabled true})]
      (try
        (let [^SdkMeterProvider provider (:meter-provider ctx)
              meter   (.build (.meterBuilder provider "test.scope"))
              counter (.build (.counterBuilder meter "http.server.requests"))]
          (.add counter 5)
          (let [output (otel/collect-and-render-prometheus (:pull-reader ctx))]
            (is (string? output))
            (is (str/includes? output "# TYPE http_server_requests counter"))
            (is (str/includes? output "http_server_requests "))
            (is (str/includes? output "5"))))
        (finally
          (otel/shutdown-otel-context ctx)))))

  (testing "histogram metrics render with _bucket, _sum, _count"
    (let [ctx (otel/create-otel-context "test-server" {:enabled true})]
      (try
        (let [^SdkMeterProvider provider (:meter-provider ctx)
              meter     (.build (.meterBuilder provider "test.scope"))
              histogram (.build (.histogramBuilder meter "http.request.duration"))]
          (.record histogram 25.0)
          (.record histogram 150.0)
          (let [output (otel/collect-and-render-prometheus (:pull-reader ctx))]
            (is (str/includes? output "# TYPE http_request_duration histogram"))
            (is (str/includes? output "http_request_duration_bucket{le=\"+Inf\"}"))
            (is (str/includes? output "http_request_duration_sum"))
            (is (str/includes? output "http_request_duration_count 2"))))
        (finally
          (otel/shutdown-otel-context ctx)))))

  (testing "gauge metrics render correctly"
    (let [ctx (otel/create-otel-context "test-server" {:enabled true})]
      (try
        (let [^SdkMeterProvider provider (:meter-provider ctx)
              meter (.build (.meterBuilder provider "test.scope"))
              gauge (.buildWithCallback
                     (.gaugeBuilder meter "jvm.memory.used")
                     (reify java.util.function.Consumer
                       (accept [_ obs]
                         (.record obs 1024.0))))]
          (let [output (otel/collect-and-render-prometheus (:pull-reader ctx))]
            (is (str/includes? output "# TYPE jvm_memory_used gauge"))
            (is (str/includes? output "jvm_memory_used "))
            (is (str/includes? output "1024"))))
        (finally
          (otel/shutdown-otel-context ctx)))))

  (testing "metrics with attributes render labels"
    (let [ctx (otel/create-otel-context "test-server" {:enabled true})]
      (try
        (let [^SdkMeterProvider provider (:meter-provider ctx)
              meter   (.build (.meterBuilder provider "test.scope"))
              counter (.build (.counterBuilder meter "http.requests"))]
          (.add counter 3
                (io.opentelemetry.api.common.Attributes/of
                 (io.opentelemetry.api.common.AttributeKey/stringKey "http.method") "GET"
                 (io.opentelemetry.api.common.AttributeKey/stringKey "http.route") "/api/v1"))
          (let [output (otel/collect-and-render-prometheus (:pull-reader ctx))]
            (is (str/includes? output "http_requests{"))
            (is (str/includes? output "http_method=\"GET\""))
            (is (str/includes? output "http_route=\"/api/v1\""))
            (is (str/includes? output "} 3"))))
        (finally
          (otel/shutdown-otel-context ctx)))))

  (testing "empty pull reader produces empty string"
    (let [reader (PullMetricReader.)]
      (is (= "" (otel/collect-and-render-prometheus reader))))))

(deftest prometheus-label-escaping-test
  (testing "label values with special characters are escaped"
    (let [ctx (otel/create-otel-context "test-server" {:enabled true})]
      (try
        (let [^SdkMeterProvider provider (:meter-provider ctx)
              meter   (.build (.meterBuilder provider "test.scope"))
              counter (.build (.counterBuilder meter "test.escaped"))]
          (.add counter 1
                (io.opentelemetry.api.common.Attributes/of
                 (io.opentelemetry.api.common.AttributeKey/stringKey "path") "/foo\"bar\\baz"))
          (let [output (otel/collect-and-render-prometheus (:pull-reader ctx))]
            ;; backslash and quote should be escaped
            (is (str/includes? output "path=\"/foo\\\"bar\\\\baz\""))))
        (finally
          (otel/shutdown-otel-context ctx))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Protocol integration tests

(deftest get-otel-meter-provider-disabled-test
  (testing "get-otel-meter-provider returns noop when OTEL is explicitly disabled"
    (with-app-with-config
     app
     [metrics-service]
     {:metrics {:server-id "localhost"
                :opentelemetry {:enabled false}}}
     (let [svc (app/get-service app :MetricsService)]
       (is (= (MeterProvider/noop) (metrics-protocol/get-otel-meter-provider svc)))))))

(deftest get-otel-meter-provider-enabled-test
  (testing "get-otel-meter-provider returns SdkMeterProvider by default (no config key)"
    (with-test-logging
      (with-app-with-config
       app
       [metrics-service]
       base-config
       (let [svc (app/get-service app :MetricsService)]
         (is (instance? SdkMeterProvider (metrics-protocol/get-otel-meter-provider svc)))))))

  (testing "get-otel-meter-provider returns SdkMeterProvider when OTEL is explicitly enabled"
    (with-test-logging
      (with-app-with-config
       app
       [metrics-service]
       otel-enabled-config
       (let [svc (app/get-service app :MetricsService)]
         (is (instance? SdkMeterProvider (metrics-protocol/get-otel-meter-provider svc))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; /metrics/v3 endpoint integration test

(deftest metrics-v3-endpoint-test
  (testing "v3 endpoint returns Prometheus text format"
    (with-test-logging
      (with-app-with-config
       app
       [jetty-service/jetty-service
        webrouting-service/webrouting-service
        metrics-service
        metrics-webservice]
       otel-with-v3-config

       ;; record a metric via the MeterProvider so the endpoint has something to serve
       (let [svc      (app/get-service app :MetricsService)
             provider (metrics-protocol/get-otel-meter-provider svc)
             meter    (.build (.meterBuilder ^SdkMeterProvider provider "integration.test"))
             counter  (.build (.counterBuilder meter "v3.test.counter"))]
         (.add counter 99)

         (let [resp (http-client/get "http://localhost:8180/metrics/v3")]
           (is (= 200 (:status resp)))
           (let [body (slurp (:body resp))]
             (is (str/includes? body "# TYPE v3_test_counter counter"))
             (is (str/includes? body "v3_test_counter 99"))))

         (testing "/v3/metrics path also works"
           (let [resp (http-client/get "http://localhost:8180/metrics/v3/metrics")]
             (is (= 200 (:status resp)))
             (let [body (slurp (:body resp))
                   ct   (get-in resp [:headers "content-type"])]
               (is (str/includes? ct "text/plain"))
               (is (str/includes? body "v3_test_counter"))))))))))

(deftest metrics-v3-disabled-when-explicitly-off-test
  (testing "v3 endpoint returns 404 when OTEL is explicitly disabled"
    (with-test-logging
      (with-app-with-config
       app
       [jetty-service/jetty-service
        webrouting-service/webrouting-service
        metrics-service
        metrics-webservice]
       {:metrics {:server-id "localhost"
                  :opentelemetry {:enabled false}
                  :metrics-webservice {:opentelemetry {:enabled false}}}
        :webserver {:port 8180 :host "0.0.0.0"}
        :web-router-service {:puppetlabs.trapperkeeper.services.metrics.metrics-service/metrics-webservice
                              "/metrics"}}
       (let [resp (http-client/get "http://localhost:8180/metrics/v3")]
         (is (= 404 (:status resp))))))))

