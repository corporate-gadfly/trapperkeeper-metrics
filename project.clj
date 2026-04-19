(def i18n-version "1.0.3")
(def dropwizard-metrics-version "4.2.38")

(defproject org.openvoxproject/trapperkeeper-metrics "2.1.8-SNAPSHOT"
  :description "Trapperkeeper Metrics Service"
  :url "http://github.com/openvoxproject/trapperkeeper-metrics"
  :license {:name "Apache License, Version 2.0"
              :url "http://www.apache.org/licenses/LICENSE-2.0.html"}

  :min-lein-version "2.9.1"

  :pedantic? :abort

  ;; Generally, try to keep version pins in :managed-dependencies and the libraries
  ;; this project actually uses in :dependencies, inheriting the version from
  ;; :managed-dependencies. This prevents endless version conflicts due to deps of deps.
  ;; Renovate should keep the versions largely in sync between projects.
  :managed-dependencies [[org.clojure/clojure "1.12.4"]
                         [org.clojure/java.jmx "1.1.1"]
                         [org.clojure/tools.logging "1.3.1"]
                         [cheshire "5.13.0"]
                         [commons-codec "1.21.0"]
                         [commons-io "2.21.0"]
                         [io.dropwizard.metrics/metrics-core ~dropwizard-metrics-version]
                         [io.dropwizard.metrics/metrics-graphite ~dropwizard-metrics-version]
                         [org.bouncycastle/bcpkix-jdk18on "1.84"]
                         [org.bouncycastle/bcpkix-fips "1.0.8"]
                         [org.bouncycastle/bc-fips "1.0.2.6"]
                         [org.bouncycastle/bctls-fips "1.0.19"]
                         [org.jolokia/jolokia-core "1.7.2"]
                         [org.openvoxproject/comidi "1.1.2"]
                         [org.openvoxproject/http-client "2.2.5"]
                         [org.openvoxproject/i18n ~i18n-version]
                         [org.openvoxproject/kitchensink "3.5.6"]
                         [org.openvoxproject/kitchensink "3.5.6" :classifier "test"]
                         [org.openvoxproject/ring-middleware "2.1.6"]
                         [org.openvoxproject/trapperkeeper "4.3.4"]
                         [org.openvoxproject/trapperkeeper "4.3.4" :classifier "test"]
                         [org.openvoxproject/trapperkeeper-authorization "2.1.8"]
                         [org.openvoxproject/trapperkeeper-webserver-jetty10 "1.1.5"]
                         [org.slf4j/slf4j-api "2.0.17"]
                         [prismatic/schema "1.4.1"]
                         [ring/ring-codec "1.3.0"]
                         [ring/ring-core "1.14.2"]]

  :dependencies [[org.clojure/clojure]
                 [org.clojure/java.jmx]
                 [org.clojure/tools.logging]
                 [cheshire]
                 [io.dropwizard.metrics/metrics-core]
                 [io.dropwizard.metrics/metrics-graphite]
                 [org.jolokia/jolokia-core]
                 [org.openvoxproject/comidi]
                 [org.openvoxproject/i18n]
                 [org.openvoxproject/kitchensink]
                 [org.openvoxproject/ring-middleware]
                 [org.openvoxproject/trapperkeeper]
                 [org.openvoxproject/trapperkeeper-authorization]
                 [prismatic/schema]]

  :plugins [[org.openvoxproject/i18n ~i18n-version]]

  :source-paths  ["src/clj"]
  :java-source-paths  ["src/java"]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/CLOJARS_USERNAME
                                     :password :env/CLOJARS_PASSWORD
                                     :sign-releases false}]]

  :classifiers  [["test" :testutils]]

  :profiles {:defaults {:dependencies [[org.openvoxproject/http-client]
                                       [org.openvoxproject/kitchensink :classifier "test"]
                                       [org.openvoxproject/trapperkeeper :classifier "test"]
                                       [org.openvoxproject/trapperkeeper-webserver-jetty10]]}
             :dev-dependencies {:dependencies [[org.bouncycastle/bcpkix-jdk18on]]}
             :dev [:defaults :dev-dependencies]
             :fips-dependencies {:dependencies [[org.bouncycastle/bcpkix-fips]
                                                [org.bouncycastle/bc-fips]
                                                [org.bouncycastle/bctls-fips]]
                                 :jvm-opts ~(let [version (System/getProperty "java.specification.version")
                                                  [major minor _] (clojure.string/split version #"\.")
                                                  unsupported-ex (ex-info "Unsupported major Java version. Expects 17 or 21."
                                                                               {:major major
                                                                                :minor minor})]
                                                 (condp = (java.lang.Integer/parseInt major)
                                                   17 ["-Djava.security.properties==./dev-resources/java.security.jdk17-fips"]
                                                   21 ["-Djava.security.properties==./dev-resources/java.security.jdk21-fips"]
                                                   (throw unsupported-ex)))}
             :fips [:defaults :fips-dependencies]


             ;; per https://github.com/technomancy/leiningen/issues/1907
             ;; the provided profile is necessary for lein jar / lein install
             :provided {:dependencies [[org.bouncycastle/bcpkix-jdk18on]]}

             :testutils {:source-paths ^:replace ["test"]
                         :java-source-paths ^:replace []}}

  :repl-options {:init-ns examples.ring-app.repl}

  :main puppetlabs.trapperkeeper.main)
