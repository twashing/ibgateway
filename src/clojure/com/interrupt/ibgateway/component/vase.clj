(ns com.interrupt.ibgateway.component.vase
  (:require [mount.core :refer [defstate] :as mount]
            [io.pedestal.http :as phttp]
            [com.interrupt.ibgateway.component.vase.server :as server]))

(defstate server
  :start (server/run-dev)
  :stop (phttp/stop server))

(comment

  (mount/start #'com.interrupt.ibgateway.component.vase/server)
  (mount/stop #'com.interrupt.ibgateway.component.vase/server))
