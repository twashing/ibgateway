
(defproject ibgateway "0.1.0-SNAPSHOT"
  :description "TODO"
  :url "TODO"
  :license {:name "TODO: Choose a license"
            :url "http://choosealicense.com/"}
  :repositories [["myMavenRepo.read" "https://mymavenrepo.com/repo/HaEY4usKuLXXnqmXBr0z"]
                 ["myMavenRepo.write" "https://mymavenrepo.com/repo/xc9d5m3WdTIFAqIiiYkn/"]]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.apache.commons/commons-daemon "1.0.9"]
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
                 [org.clojure/math.combinatorics "0.1.4"]
                 [clojure-future-spec "1.9.0-alpha17"]]
  :local-repo "m2"
  :source-paths ["src/clojure" "test/clojure"]
  :java-source-paths ["src/java"]
  :profiles {:dev {:dependencies [[drift "1.5.3"]
                                  [spyscope "0.1.5"]
                                  [im.chit/lucid.core.inject "1.3.13"]
                                  [com.gfredericks/test.chuck "0.2.8"]
                                  [http-kit.fake "0.2.1"]
                                  [org.clojure/test.check "0.9.0"]
                                  [prismatic/schema "1.1.6"]
                                  [prismatic/schema-generators "0.1.0"]
                                  [org.clojure/tools.nrepl "0.2.12"]]
                   :injections [(require 'spyscope.core)
                                (use 'lucid.core.inject)
                                (inject '[clojure.core
                                          [clojure.repl dir]
                                          [clojure.pprint pprint]
                                          [clojure.java.javadoc javadoc]
                                          [clojure.reflect reflect]
                                          [clojure.repl apropos dir doc find-doc pst source]])]

                   :plugins [[cider/cider-nrepl "0.17.0-SNAPSHOT"]

                             ;; Latest refactor-nrepl ("2.4.0-SNAPSHOT") isn't sync'd
                             ;; with this version of cider-nrepl ("0.17.0-SNAPSHOT"). So
                             ;; we have to wait for an update.

                             ;; [refactor-nrepl "2.4.0-SNAPSHOT"]
                             ]}}
  :main com.interrupt.ibgateway.core)
