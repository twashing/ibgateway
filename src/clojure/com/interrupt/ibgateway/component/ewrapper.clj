(ns com.interrupt.ibgateway.component.ewrapper
  (:require [clojure.tools.logging :refer [info] :as log]
            [clojure.core.async :as async :refer [<! close!]]
            [environ.core :refer [env]]
            [mount.core :as mount :refer [defstate]]
            [com.interrupt.ibgateway.component.account.contract :as contract]
            [com.interrupt.ibgateway.component.ewrapper-impl :as ewi]
            [com.interrupt.ibgateway.component.account.common :refer [next-reqid! release-reqid!]])
  (:import [com.ib.client Order OrderState OrderStateWrapper Execution CommissionReport]))



(def tws-host (env :tws-host "tws-live"))
(def tws-port (Integer/parseInt (env :tws-port "7496")))
(def client-id (atom nil))

(defn setup-default-channels []
  {:publisher (-> 100 async/sliding-buffer async/chan)
   :account-updates (-> 1 async/sliding-buffer async/chan)
   :position-updates (-> 1 async/sliding-buffer async/chan)
   :order-updates (-> 100 async/sliding-buffer async/chan)
   :valid-order-ids (async/chan)
   :order-filled-notifications (async/chan)})

(defn teardown-default-channels [default-channels]
  (->> default-channels vals (map close!)))

(defstate ewrapper
  :start (let [dchans (setup-default-channels)]
           {:default-channels dchans
            :ewrapper (ewi/ewrapper tws-host tws-port (reset! client-id (next-reqid!)) dchans)})
  :stop (let [client (-> ewrapper :ewrapper :client)]

          ;; disconnect client
          (when (.isConnected client)
            (.eDisconnect client))
          (release-reqid! @client-id)
          (reset! client-id -1)

          ;; teardown channels
          (teardown-default-channels (-> ewrapper :default-channels))))
