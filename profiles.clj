{:user {:dependencies [[org.clojure/tools.nrepl "0.2.12"]
                       [im.chit/lucid.core.inject "1.3.13"]
                       [spyscope "0.1.5"]]
        :injections [(require 'spyscope.core)
                     (use 'lucid.core.inject)
                     (inject '[clojure.core
                               [clojure.repl dir]
                               [clojure.pprint pprint]
                               [clojure.java.javadoc javadoc]
                               [clojure.reflect reflect]
                               [clojure.repl apropos dir doc find-doc pst source]])]
        :plugins [[lein-try "0.4.3"]
                  [lein-ancient "0.6.10"]
                  [cider/cider-nrepl "0.17.0-SNAPSHOT" :exclusions [org.clojure/tools.nrepl]]
                  [refactor-nrepl "2.4.0-SNAPSHOT" :exclusions [org.clojure/tools.nrepl]]]}}
