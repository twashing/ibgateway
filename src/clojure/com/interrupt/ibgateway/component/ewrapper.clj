(ns com.interrupt.ibgateway.component.ewrapper
  (:require [clojure.tools.logging :refer [info] :as log]
            [clojure.core.async :as async :refer [<! close!]]
            [environ.core :refer [env]]
            [mount.core :as mount :refer [defstate]]
            [com.interrupt.ibgateway.component.account.contract :as contract]
            [com.interrupt.ibgateway.component.ewrapper-impl :as ewi]
            [com.interrupt.ibgateway.component.account.common :refer [next-reqid! release-reqid!]])
  (:import [com.ib.client Order OrderState OrderStateWrapper Execution CommissionReport]))



(def tws-host (env :tws-host "tws"))
(def tws-port (env :tws-port 4002))
(def client-id (atom (next-reqid!)))

(defstate default-chs-map
  :start {:publisher (-> 1000 async/sliding-buffer async/chan)
          :account-updates (-> 1000 async/sliding-buffer async/chan)
          :order-updates (-> 1000 async/sliding-buffer async/chan)}
  :stop (->> default-chs-map vals (map close!)))

(defstate ewrapper
  :start (ewi/ewrapper tws-host tws-port @client-id default-chs-map)
  :stop (let [{client :client} ewrapper]
          (when (.isConnected client)
            (.eDisconnect client))
          (release-reqid! @client-id)
          (reset! client-id -1)))
