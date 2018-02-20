(defproject ibgateway "0.1.0-SNAPSHOT"
  :description "TODO"
  :url "TODO"
  :license {:name "TODO: Choose a license"
            :url "http://choosealicense.com/"}
  :repositories [["myMavenRepo.read" "https://mymavenrepo.com/repo/HaEY4usKuLXXnqmXBr0z"]
                 ["myMavenRepo.write" "https://mymavenrepo.com/repo/xc9d5m3WdTIFAqIiiYkn/"]]
  :dependencies [[org.clojure/clojure "1.8.0"]

                 [com.stuartsierra/component "0.3.2"]
                 [mount "0.1.12"]

                 [org.danielsz/system "0.4.1"
                  :exclusions [org.clojure/tools.namespace org.clojure/core.async]]
                 [org.clojure/core.async "0.3.441"]
                 [com.interactivebrokers.tws/tws-api "9.72.17-SNAPSHOT"]
                 [ymilky/franzy "0.0.1"]
                 [ymilky/franzy-transit "0.0.1" :exclusions [commons-codec]]
                 [ymilky/franzy-admin "0.0.1" :exclusions [org.slf4j/slf4j-log4j12]]
                 [automat "0.2.4"]
                 [reduce-fsm "0.1.4"]
                 [com.datomic/datomic-free "0.9.5656"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [clojure-future-spec "1.9.0-alpha17"]]
  :local-repo "m2"
  :source-paths ["src/clojure" "test/clojure"]
  :java-source-paths ["src/java"]
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]
                                  [com.stuartsierra/component.repl "0.2.0"]]}}
  :main com.interrupt.ibgateway.core)
