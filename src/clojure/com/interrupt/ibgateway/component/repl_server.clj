(ns com.interrupt.ibgateway.component.repl-server
  (:require [mount.core :refer [defstate] :as mount]
            #_[com.stuartsierra.component :as component]
            [clojure.tools.nrepl.server :refer [start-server stop-server] :as nrepl]
            [cider.nrepl :refer [cider-middleware]]
            [refactor-nrepl.middleware :refer [wrap-refactor]]))

#_(defrecord ReplServer [port bind]
  component/Lifecycle
  (start [component]
    (assoc component
           :server (start-server :port port
                                 :bind bind
                                 :handler (apply
                                           nrepl/default-handler
                                           #_#'pb/wrap-cljs-repl
                                           (conj (map resolve cider-middleware)
                                                 wrap-refactor)))))
  (stop [{server :server :as component}]
    (when server
      (stop-server server)
      component)))

#_(defn new-repl-server
  ([port]
   (new-repl-server port "localhost"))
  ([port bind]
   (map->ReplServer {:port port :bind bind}) ))


(def host "0.0.0.0")
(def port 5554)

(defstate server
  :start (start-server :port port
                       :bind host
                       :handler (apply
                                 nrepl/default-handler
                                 (conj (map resolve cider-middleware)
                                       wrap-refactor)))
  :stop (stop-server server))


(comment
  (mount/start)
  (mount/stop))
