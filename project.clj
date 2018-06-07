(defproject ibgateway "0.1.0-SNAPSHOT"
  :description "TODO"
  :url "TODO"
  :license {:name "TODO: Choose a license"
            :url "http://choosealicense.com/"}
  :repositories [["myMavenRepo.read" "https://mymavenrepo.com/repo/HaEY4usKuLXXnqmXBr0z"]
                 ["myMavenRepo.write" "https://mymavenrepo.com/repo/xc9d5m3WdTIFAqIiiYkn/"]
                 ["my.datomic.com" {:url "https://my.datomic.com/repo"
                                    :creds :gpg}]]
  :dependencies [[org.clojure/clojure "1.9.0-alpha14"]
                 [org.apache.commons/commons-daemon "1.0.9"]
                 [mount "0.1.12"]
                 [org.clojure/core.async "0.4.474"]
                 [com.interactivebrokers.tws/tws-api "9.72.17-SNAPSHOT"]
                 [ymilky/franzy "0.0.1"]
                 [ymilky/franzy-transit "0.0.1" :exclusions [commons-codec]]
                 [ymilky/franzy-admin "0.0.1" :exclusions [org.slf4j/slf4j-log4j12]]
                 [automat "0.2.4"]
                 [reduce-fsm "0.1.4"]
                 [com.datomic/datomic-free "0.9.5697"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [org.clojure/math.combinatorics "0.1.4"]
                 [cider/cider-nrepl "0.17.0-SNAPSHOT"]
                 [refactor-nrepl "2.4.0-SNAPSHOT"]
                 [clojure-future-spec "1.9.0-alpha17"]


                 ;; KLUDGE libraries
                 [javax.servlet/javax.servlet-api "4.0.0"]

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
                 [org.clojure/core.incubator "0.1.4"]
                 ;; [jtsclient/jtsclient "9.8.3"]
                 [org.clojure/data.csv "0.1.4"]
                 [overtone/at-at "1.2.0"]
                 [cljs-uuid "0.0.4"]
                 [org.clojure/tools.namespace "0.2.10"]
                 [lamina "0.5.6"]
                 [aleph "0.4.4"]
                 [clj-time "0.14.3"]
                 ;; #_[com.datomic/datomic "0.8.3335"
                 ;;    :exclusions [org.slf4j/slf4j-nop org.slf4j/log4j-over-slf4j]]
                 ;; #_[midje "1.5.1"]
                 [ring/ring-core "1.6.3"
                  :exclusions [javax.servlet/servlet-api]]
                 [spootnik/unilog "0.7.22"]

                 [io.pedestal/pedestal.service "0.5.2"]
                 [io.pedestal/pedestal.jetty "0.5.2"]
                 [com.cognitect/pedestal.vase "0.9.1"]

                 ;; Java Libraries
                 [joda-time "2.2"]
                 [manifold "0.1.7-alpha6"]
                 [net.cgrand/xforms "0.17.0"]
                 [employeerepublic/promisespromises "0.5"]
                 [com.xnlogic/transducers "0.1.0"]
                 [com.rpl/specter "1.1.1"]]
  :local-repo "m2"
  :source-paths ["src/clojure" "test/clojure"]
  :java-source-paths ["src/java"]
  :profiles {:dev {:dependencies [#_[spyscope "0.1.5"]
                                  [org.clojure/tools.trace "0.7.9"]
                                  [org.clojure/test.check "0.10.0-alpha2"]
                                  [com.gfredericks/test.chuck "0.2.9"]
                                  [im.chit/lucid.core.inject "1.3.13"]
                                  [org.clojure/tools.nrepl "0.2.13"]
                                  [http-kit.fake "0.2.2"]]
                   :injections [#_(require 'spyscope.core)
                                (use 'lucid.core.inject)
                                (inject '[clojure.core
                                          [clojure.repl dir]
                                          [clojure.pprint pprint]
                                          [clojure.java.javadoc javadoc]
                                          [clojure.reflect reflect]
                                          [clojure.repl apropos dir doc find-doc pst source]])]

                   :plugins [[cider/cider-nrepl "0.17.0-SNAPSHOT"]
                             [refactor-nrepl "2.4.0-SNAPSHOT"]]

                   :aliases {"rebl" ["trampoline" "run" "-m" "rebel-readline.main"]}}}

  :main com.interrupt.ibgateway.core)
