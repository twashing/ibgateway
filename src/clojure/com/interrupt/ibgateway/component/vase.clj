(ns com.interrupt.ibgateway.component.vase
  (:require [mount.core :refer [defstate] :as mount]
            [io.pedestal.http :as phttp]
            [com.interrupt.ibgateway.component.vase.server :as srv]))

(defstate server
  :start (srv/run-dev)
  :stop (phttp/stop server))

;; (phttp/start srv/runnable-service)

(comment

  (mount/start #'com.interrupt.ibgateway.component.vase/server)
  (mount/stop #'com.interrupt.ibgateway.component.vase/server)

  (def one (srv/run-dev))
  (phttp/stop one)

  )
