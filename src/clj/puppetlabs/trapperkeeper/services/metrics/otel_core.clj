(ns puppetlabs.trapperkeeper.services.metrics.otel-core
  "OpenTelemetry metrics integration for trapperkeeper-metrics.

  Provides:
  - SdkMeterProvider lifecycle (init / shutdown)
  - OTLP exporter (push to an OpenTelemetry Collector, opt-in)
  - PullMetricReader for the /metrics/v3 scrape endpoint
  - Ring handler that serves collected metrics in Prometheus text format

  Design notes
  ────────────
  The MeterProvider is configured in `init` and shut down in `stop`.
  When `:opentelemetry :enabled` is false a *noop* MeterProvider
  is returned so downstream code can call `.meterBuilder` unconditionally
  with zero overhead."
  (:import (com.puppetlabs.trapperkeeper.metrics PullMetricReader)
           (io.opentelemetry.api OpenTelemetry)
           (io.opentelemetry.api.metrics MeterProvider)
           (io.opentelemetry.sdk OpenTelemetrySdk)
           (io.opentelemetry.sdk.metrics SdkMeterProvider)
           (io.opentelemetry.sdk.metrics.export PeriodicMetricReader)
           (io.opentelemetry.sdk.resources Resource)
           (io.opentelemetry.api.common Attributes AttributeKey)
           (io.opentelemetry.exporter.otlp.http.metrics OtlpHttpMetricExporter)
           (java.time Duration))
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.i18n.core :as i18n]
            [ring.middleware.content-type :as ring-content-type]
            [ring.middleware.not-modified :as ring-not-modified]
            [ring.middleware.params :as ring-params]
            [schema.core :as schema]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def OtlpConfig
  "Schema for the OTLP push exporter."
  {(schema/optional-key :enabled) schema/Bool
   (schema/optional-key :endpoint) schema/Str
   (schema/optional-key :export-interval-seconds) schema/Int
   (schema/optional-key :headers) {schema/Str schema/Str}})

(def OtelResourceConfig
  "Extra resource attributes merged with defaults."
  {schema/Str schema/Str})

(def OtelConfig
  "Top-level OpenTelemetry configuration, nested under :metrics :opentelemetry."
  {(schema/optional-key :enabled) schema/Bool
   (schema/optional-key :otlp) OtlpConfig
   (schema/optional-key :resource) OtelResourceConfig})

(def OtelContext
  "Runtime OTEL objects stored in the TK service context."
  {:meter-provider MeterProvider
   :pull-reader PullMetricReader
   (schema/optional-key :sdk) SdkMeterProvider})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Resource helpers

(schema/defn build-resource :- Resource
  "Build an OTEL Resource from the server-id and optional user-supplied
  attributes."
  [server-id :- schema/Str
   extra-attrs :- (schema/maybe OtelResourceConfig)]
  (let [builder (-> (Resource/builder)
                    (.put (AttributeKey/stringKey "service.name")
                          (or server-id "puppetserver")))]
    (doseq [[k v] extra-attrs]
      (.put builder (AttributeKey/stringKey k) (str v)))
    (.build builder)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; OTLP exporter

(schema/defn build-otlp-reader
  "Build a PeriodicMetricReader backed by an OTLP HTTP/protobuf exporter.
  Returns nil when OTLP is not enabled."
  [otlp-config :- (schema/maybe OtlpConfig)]
  (when (:enabled otlp-config)
    (let [endpoint  (get otlp-config :endpoint "http://localhost:4318/v1/metrics")
          headers   (get otlp-config :headers {})
          interval  (get otlp-config :export-interval-seconds 60)
          exporter  (let [b (OtlpHttpMetricExporter/builder)]
                      (.setEndpoint b endpoint)
                      (doseq [[k v] headers] (.addHeader b k v))
                      (.build b))]
      (log/info "OTLP metrics exporter enabled — endpoint:" endpoint
                "interval:" interval "s")
      (-> (PeriodicMetricReader/builder exporter)
          (.setInterval (Duration/ofSeconds interval))
          (.build)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; MeterProvider lifecycle

(schema/defn create-otel-context :- OtelContext
  "Initialise the OpenTelemetry MeterProvider.

  When `otel-enabled?` is true an SdkMeterProvider is built with:
    - a PullMetricReader  (serves /metrics/v3)
    - an OTLP reader      (if otlp.enabled, pushes to a collector)

  When false, MeterProvider/noop is returned and everything is zero-cost."
  [server-id :- schema/Str
   otel-config :- (schema/maybe OtelConfig)]
  (let [otel-enabled? (get otel-config :enabled true)]
    (if otel-enabled?
      (let [pull-reader  (PullMetricReader.)
            resource     (build-resource server-id
                                        (get otel-config :resource))
            builder      (-> (SdkMeterProvider/builder)
                             (.setResource resource)
                             (.registerMetricReader pull-reader))
            ;; optionally add OTLP push exporter
            otlp-reader  (build-otlp-reader (get otel-config :otlp))
            _            (when otlp-reader
                           (.registerMetricReader builder otlp-reader))
            sdk          (.build builder)]
        (log/info "OpenTelemetry metrics enabled (SdkMeterProvider)")
        {:meter-provider sdk
         :pull-reader    pull-reader
         :sdk            sdk})
      (do
        (log/debug "OpenTelemetry metrics disabled (noop MeterProvider)")
        {:meter-provider (MeterProvider/noop)
         :pull-reader    (PullMetricReader.)}))))

(schema/defn shutdown-otel-context
  "Gracefully shut down the SdkMeterProvider if one was created."
  [otel-ctx :- OtelContext]
  (when-let [sdk (:sdk otel-ctx)]
    (log/info "Shutting down OpenTelemetry MeterProvider")
    (.shutdown ^SdkMeterProvider sdk)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; /metrics/v3 — Prometheus text exposition format
;;;
;;; Spec: https://prometheus.io/docs/instrumenting/exposition_formats/
;;; We emit OpenMetrics-compatible output (# HELP, # TYPE, metric lines).
;;; Metric names are sanitised: dots → underscores, dashes → underscores.

(def ^:private prometheus-content-type
  "text/plain; version=0.0.4; charset=utf-8")

(defn sanitize-metric-name
  "Replace characters not allowed in Prometheus metric names with underscores.
  Prometheus names must match [a-zA-Z_:][a-zA-Z0-9_:]*."
  [^String s]
  (-> s
      (str/replace #"[.\-/]" "_")
      (str/replace #"[^a-zA-Z0-9_:]" "_")))

(defn- format-label-value
  "Escape a Prometheus label value (inside double quotes)."
  [^String v]
  (-> v
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))

(defn- format-labels
  "Render OTEL Attributes as Prometheus label set: {k1=\"v1\",k2=\"v2\"}."
  [^Attributes attrs]
  (let [pairs (java.util.ArrayList.)]
    (.forEach attrs
              (reify java.util.function.BiConsumer
                (accept [_ k v]
                  (.add pairs
                        (str (sanitize-metric-name
                              (str (.getKey ^AttributeKey k)))
                             "=\""
                             (format-label-value (str v))
                             "\"")))))
    (if (.isEmpty pairs)
      ""
      (str "{" (str/join "," pairs) "}"))))

(defn- otel-type->prometheus-type
  "Map an OTEL MetricDataType to its Prometheus TYPE string."
  [^io.opentelemetry.sdk.metrics.data.MetricDataType t]
  (condp = (str t)
    "LONG_GAUGE"   "gauge"
    "DOUBLE_GAUGE" "gauge"
    "LONG_SUM"     "counter"
    "DOUBLE_SUM"   "counter"
    "HISTOGRAM"    "histogram"
    "SUMMARY"      "summary"
    "untyped"))

(defn- append-gauge-or-sum!
  "Append lines for a gauge or sum (long/double) metric."
  [^StringBuilder sb ^String pname points]
  (doseq [point points]
    (let [labels (format-labels (.getAttributes ^io.opentelemetry.sdk.metrics.data.PointData point))]
      (cond
        (instance? io.opentelemetry.sdk.metrics.data.LongPointData point)
        (doto sb (.append pname) (.append labels) (.append " ")
              (.append (.getValue ^io.opentelemetry.sdk.metrics.data.LongPointData point))
              (.append "\n"))

        (instance? io.opentelemetry.sdk.metrics.data.DoublePointData point)
        (doto sb (.append pname) (.append labels) (.append " ")
              (.append (.getValue ^io.opentelemetry.sdk.metrics.data.DoublePointData point))
              (.append "\n"))))))

(defn- append-histogram!
  "Append Prometheus histogram lines (_bucket, _count, _sum)."
  [^StringBuilder sb ^String pname points]
  (doseq [^io.opentelemetry.sdk.metrics.data.HistogramPointData point points]
    (let [labels-str  (format-labels (.getAttributes point))
          boundaries  (.getBoundaries point)
          counts      (.getCounts point)
          ;; strip trailing "}" from labels so we can inject le=
          has-labels? (not (str/blank? labels-str))
          label-open  (if has-labels?
                        (str (subs labels-str 0 (dec (count labels-str))) ",")
                        "{")
          cum-count   (atom 0)]
      ;; _bucket lines: cumulative counts per boundary
      (dotimes [i (count boundaries)]
        (swap! cum-count + (nth counts i))
        (doto sb
          (.append pname) (.append "_bucket")
          (.append label-open) (.append "le=\"")
          (.append (nth boundaries i)) (.append "\"} ")
          (.append @cum-count) (.append "\n")))
      ;; +Inf bucket
      (swap! cum-count + (nth counts (count boundaries)))
      (doto sb
        (.append pname) (.append "_bucket")
        (.append label-open) (.append "le=\"+Inf\"} ")
        (.append @cum-count) (.append "\n"))
      ;; _sum and _count
      (doto sb
        (.append pname) (.append "_sum") (.append labels-str) (.append " ")
        (.append (.getSum point)) (.append "\n")
        (.append pname) (.append "_count") (.append labels-str) (.append " ")
        (.append (.getCount point)) (.append "\n")))))

(defn- append-summary!
  "Append Prometheus summary lines (_count, _sum)."
  [^StringBuilder sb ^String pname points]
  (doseq [^io.opentelemetry.sdk.metrics.data.SummaryPointData point points]
    (let [labels-str (format-labels (.getAttributes point))]
      (doto sb
        (.append pname) (.append "_sum") (.append labels-str) (.append " ")
        (.append (.getSum point)) (.append "\n")
        (.append pname) (.append "_count") (.append labels-str) (.append " ")
        (.append (.getCount point)) (.append "\n")))))

(defn metrics->prometheus
  "Render a collection of MetricData as a Prometheus text exposition string."
  [metrics]
  (let [sb (StringBuilder.)]
    (doseq [^io.opentelemetry.sdk.metrics.data.MetricData md metrics]
      (let [pname  (sanitize-metric-name (.getName md))
            ptype  (otel-type->prometheus-type (.getType md))
            desc   (.getDescription md)
            points (.getPoints (.getData md))]
        ;; # HELP / # TYPE header
        (when-not (str/blank? desc)
          (doto sb (.append "# HELP ") (.append pname) (.append " ") (.append desc) (.append "\n")))
        (doto sb (.append "# TYPE ") (.append pname) (.append " ") (.append ptype) (.append "\n"))
        ;; data lines
        (case ptype
          ("gauge" "counter" "untyped") (append-gauge-or-sum! sb pname points)
          "histogram"                   (append-histogram! sb pname points)
          "summary"                     (append-summary! sb pname points))))
    (str sb)))

(defn collect-and-render-prometheus
  "Collect all current OTEL metrics from the PullMetricReader and render
  them as a Prometheus text exposition string."
  [^PullMetricReader pull-reader]
  (metrics->prometheus (.collectAllMetrics pull-reader)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Ring handler for /metrics/v3

(defn build-v3-handler
  "Build a Comidi handler that serves OTEL metrics in Prometheus text format
  on GET /v3 and GET /v3/metrics.
  `pull-reader-fn` is a 0-arity function that returns the current PullMetricReader."
  [path pull-reader-fn]
  (let [handle-fn (fn [_req]
                    {:status  200
                     :headers {"Content-Type" prometheus-content-type}
                     :body    (collect-and-render-prometheus (pull-reader-fn))})]
    (comidi/routes->handler
     (comidi/wrap-routes
      (comidi/context path
        (comidi/context "/v3"
          (comidi/GET "" [] handle-fn)
          (comidi/GET "/metrics" [] handle-fn)))
      (fn [handler]
        (-> handler
            (ring-params/wrap-params)
            (ring-not-modified/wrap-not-modified)
            (i18n/locale-negotiator)))))))

