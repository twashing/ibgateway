(ns com.interrupt.ibgateway.component.ewrapper
  (:require [clojure.core.async :refer [close!]]
            [com.interrupt.ibgateway.component.ewrapper-impl :as ewi]
            [mount.core :as mount :refer [defstate]]))

(defstate ewrapper
  :start (ewi/ewrapper)
  :stop (let [{:keys [client publisher]} ewrapper]
          (when (.isConnected client)
            (.eDisconnect client))
          (close! publisher)))
