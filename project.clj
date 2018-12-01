(defproject ibgateway "0.1.0-SNAPSHOT"
  :description "TODO"
  :url "TODO"
  :license {:name "TODO: Choose a license"
            :url "http://choosealicense.com/"}
  :repositories [["myMavenRepo.read" "https://mymavenrepo.com/repo/HaEY4usKuLXXnqmXBr0z"]
                 ["myMavenRepo.write" "https://mymavenrepo.com/repo/xc9d5m3WdTIFAqIiiYkn/"]
                 ["my.datomic.com" {:url "https://my.datomic.com/repo"
                                    :creds :gpg}]]
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.10.238"]
                 [org.apache.commons/commons-daemon "1.0.9"]
                 [mount "0.1.12"]
                 [org.clojure/core.async "0.4.474"]
                 [com.interactivebrokers.tws/tws-api "9.72.17-SNAPSHOT"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [org.clojure/math.combinatorics "0.1.4"]
                 [javax.servlet/javax.servlet-api "4.0.0"]
                 [environ "1.1.0"]
                 [inflections "0.13.0"]

                 ;; Pedestal libraries
                 [io.pedestal/pedestal.service "0.5.3"]
                 [io.pedestal/pedestal.jetty "0.5.3"]
                 ;;
                 ;; ;; Logging
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [org.slf4j/jul-to-slf4j "1.7.25"]
                 [org.slf4j/jcl-over-slf4j "1.7.25"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]
                 ;;
                 ;; ;; Edgar proper
                 [cljs-uuid "0.0.4"]
                 [org.clojure/tools.namespace "0.2.10"]
                 [clj-time "0.14.3"]

                 [ring/ring-core "1.6.3" :exclusions [javax.servlet/servlet-api]]
                 [spootnik/unilog "0.7.22"]
                 [io.pedestal/pedestal.service "0.5.2"]
                 [io.pedestal/pedestal.jetty "0.5.2"]
                 [io.pedestal/pedestal.log "0.5.2"]
                 [com.cognitect/pedestal.vase "0.9.3"]
                 [org.clojure/tools.cli "0.3.7"]
                 [automata "0.2.0-SNAPSHOT"]

                 ;; Java Libraries
                 [manifold "0.1.7-alpha6"]
                 [net.cgrand/xforms "0.17.0"]
                 [employeerepublic/promisespromises "0.5"]
                 [enlive "1.1.6"]
                 [com.cognitect/transit-clj "0.8.309"]
                 [com.cognitect/transit-cljs "0.8.256"]
                 [com.amazonaws/aws-java-sdk "1.11.401"]
                 [org.apache.commons/commons-math3 "3.6.1"]
                 [com.rpl/specter "1.1.1"]
                 [org.clojure/data.csv "0.1.4"]]

  :local-repo "m2"
  :source-paths ["src/clojure" "test/clojure"]
  :java-source-paths ["src/java"]
  :profiles {:dev {:dependencies [[org.clojure/tools.trace "0.7.9"]
                                  [org.clojure/tools.nrepl "0.2.13"]
                                  [org.clojure/test.check "0.10.0-alpha2"]
                                  [com.gfredericks/test.chuck "0.2.9"]
                                  [im.chit/lucid.core.inject "1.3.13"]
                                  [http-kit.fake "0.2.2"]
                                  [cider/piggieback "0.3.6"]
                                  [figwheel-sidecar "0.5.16"]]

                   :source-paths ["src/clojure" "workbench/clojure" "test/clojure"]
                   :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}

                   :resource-paths ["dev-resources" "test/resources"]

                   :plugins [[refactor-nrepl "2.4.0"]
                             [cider/cider-nrepl "0.18.0"]
                             [lein-cljsbuild "1.1.7"]
                             [lein-environ "1.1.0"]
                             [lein-figwheel "0.5.16"]
                             [com.billpiel/sayid "0.0.17"]]

                   :aliases {"rebl" ["trampoline" "run" "-m" "rebel-readline.main"]}}}

  :cljsbuild {:builds [{:id "main"
                        :source-paths ["src/clojurescript/"]
                        :figwheel true
                        :compiler {:main "com.interrupt.edgar.core"
                                   :asset-path "js/out"
                                   :output-to "resources/public/js/core.js"
                                   :output-dir "resources/public/js/out"}}]}

  :main com.interrupt.ibgateway.core)
