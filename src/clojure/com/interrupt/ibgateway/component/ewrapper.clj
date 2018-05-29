(ns com.interrupt.ibgateway.component.ewrapper
  (:require [clojure.core.async :refer [close!]]
            [mount.core :refer [defstate] :as mount]
            [com.interrupt.ibgateway.component.ewrapper-impl :as ewi]))

(defstate ewrapper
  :start (ewi/ewrapper)
  :stop (let [{:keys [client publisher]} ewrapper]
          (when (.isConnected client)
            (.eDisconnect client))
          (close! publisher)))
