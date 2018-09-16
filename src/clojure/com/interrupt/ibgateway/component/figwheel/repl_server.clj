(ns com.interrupt.ibgateway.component.figwheel.repl-server
  (:require [environ.core :refer [env]]
            [mount.core :refer [defstate] :as mount]
            [clojure.tools.nrepl.server :refer [start-server stop-server] :as nrepl]
            [cider.nrepl :refer [cider-middleware]]
            [refactor-nrepl.middleware :refer [wrap-refactor]]))


(def host "0.0.0.0")
(def port 5555)

(defstate server
  :start (start-server :port port
                       :bind host
                       :handler (apply
                                 nrepl/default-handler
                                 (conj (map resolve cider-middleware)
                                       wrap-refactor)))
  :stop (stop-server server))
