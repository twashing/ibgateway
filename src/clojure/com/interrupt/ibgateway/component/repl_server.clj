(ns com.interrupt.ibgateway.component.repl-server
  (:require [environ.core :refer [env]]
            [mount.core :refer [defstate] :as mount]
            ;; [clojure.tools.nrepl.server :refer [start-server stop-server] :as nrepl]
            [nrepl.server :refer [start-server stop-server] :as nrepl]
            [cider.nrepl :refer [cider-middleware]]))


(def host "0.0.0.0")
(def port 5554)

(defstate server
  :start (start-server :port port
                       :bind host
                       :handler (apply
                                 nrepl/default-handler
                                 (map resolve cider-middleware)))
  :stop (stop-server server))
