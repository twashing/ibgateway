(defproject ibgateway "0.1.0-SNAPSHOT"
  :description "TODO"
  :url "TODO"
  :license {:name "TODO: Choose a license"
            :url "http://choosealicense.com/"}
  :repositories [["myMavenRepo.read" "https://mymavenrepo.com/repo/HaEY4usKuLXXnqmXBr0z"]
                 ["myMavenRepo.write" "https://mymavenrepo.com/repo/xc9d5m3WdTIFAqIiiYkn/"]
                 ["my.datomic.com" {:url "https://my.datomic.com/repo"
                                    :creds :gpg}]]
  :dependencies [[org.clojure/clojure "1.10.1-beta1"]
                 ;; [org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.10.520"]
                 [org.apache.commons/commons-daemon "1.0.9"]
                 [mount "0.1.16"]
                 [org.clojure/core.async "0.4.490"]
                 [com.interactivebrokers.tws/tws-api "9.72.17-SNAPSHOT"]
                 ;; [com.interactivebrokers.tws/tws-api "9.74.01"]
                 [org.clojure/core.match "0.3.0"]
                 [org.clojure/math.combinatorics "0.1.5"]
                 [javax.servlet/javax.servlet-api "4.0.1"]
                 [environ "1.1.0"]
                 [inflections "0.13.2"]

                 ;; Pedestal libraries
                 [io.pedestal/pedestal.service "0.5.5"]
                 [io.pedestal/pedestal.jetty "0.5.5"]
                 ;;
                 ;; ;; Logging
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [org.slf4j/jul-to-slf4j "1.7.26"]
                 [org.slf4j/jcl-over-slf4j "1.7.26"]
                 [org.slf4j/log4j-over-slf4j "1.7.26"]
                 ;;
                 ;; ;; Edgar proper
                 [cljs-uuid "0.0.4"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [clj-time "0.15.1"]

                 [ring/ring-core "1.7.1" :exclusions [javax.servlet/servlet-api]]
                 [spootnik/unilog "0.7.24"]
                 [io.pedestal/pedestal.service "0.5.5"]
                 [io.pedestal/pedestal.jetty "0.5.5"]
                 [io.pedestal/pedestal.log "0.5.5"]
                 [com.cognitect/pedestal.vase "0.9.3"]
                 [org.clojure/tools.cli "0.4.2"]
                 [org.clojure/tools.reader "1.3.2"]
                 [automata "0.2.0"]

                 [org.onyxplatform/onyx "0.14.4"]
                 [com.codahale.metrics/metrics-core "3.0.2"]

                 ;; Java Libraries
                 [manifold "0.1.8"]
                 [net.cgrand/xforms "0.17.0"]
                 [employeerepublic/promisespromises "0.5"]
                 [enlive "1.1.6"]
                 [com.cognitect/transit-clj "0.8.313"]
                 [com.cognitect/transit-cljs "0.8.256"]
                 [com.amazonaws/aws-java-sdk "1.11.546"]
                 [org.apache.commons/commons-math3 "3.6.1"]
                 [com.rpl/specter "1.1.2"]
                 [org.clojure/data.csv "0.1.4"]
                 ;; [org.clojure/tools.nrepl "0.2.13"]
                 [nrepl "0.6.0"]]

  :local-repo "m2"
  :source-paths ["src/clojure" "test/clojure"]
  :java-source-paths ["src/java"]
  :profiles {:dev {:dependencies [[org.clojure/tools.trace "0.7.10"]
                                  [org.clojure/test.check "0.10.0-alpha2"]
                                  [com.gfredericks/test.chuck "0.2.9"]
                                  [im.chit/lucid.core.inject "1.3.13"]
                                  [http-kit.fake "0.2.2"]
                                  [cider/piggieback "0.4.0"]
                                  [figwheel-sidecar "0.5.18"]
                                  ;; [org.clojure/tools.nrepl "0.2.13"]
                                  [nrepl "0.6.0"]]

                   :source-paths ["src/clojure" "workbench/clojure" "test/clojure"]
                   :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}

                   :resource-paths ["dev-resources" "test/resources"]

                   :plugins [[cider/cider-nrepl "0.21.1"]
                             [lein-ancient "0.6.15"]
                             [lein-environ "1.1.0"]]

                   :aliases {"rebl" ["trampoline" "run" "-m" "rebel-readline.main"]}}}

  :cljsbuild {:builds [{:id "main"
                        :source-paths ["src/clojurescript/"]
                        :figwheel true
                        :compiler {:main "com.interrupt.edgar.core"
                                   :asset-path "js/out"
                                   :output-to "resources/public/js/core.js"
                                   :output-dir "resources/public/js/out"}}]}

  :main com.interrupt.ibgateway.core)
