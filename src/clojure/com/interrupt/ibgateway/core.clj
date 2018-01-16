(ns com.interrupt.ibgateway.core
  (:require  [com.stuartsierra.component :as component]
             [system.repl :refer [set-init! init start stop reset refresh system]]
             [com.interrupt.ibgateway.component.repl-server :refer [new-repl-server]]
             [com.interrupt.ibgateway.component.ewrapper :refer [new-ewrapper]]
             [com.interrupt.ibgateway.component.ewrapper-impl :as ei]
             [clojure.core.async :refer [chan >! <! merge go go-loop pub sub unsub-all sliding-buffer]])
  #_(:import [java.util.concurrent TimeUnit]
           [java.util Calendar]
           [java.text SimpleDateFormat]
           [com.ib.client
            EWrapper EClient EClientSocket EReader EReaderSignal
            Contract ContractDetails ScannerSubscription]
           [com.ib.client Types$BarSize Types$DurationUnit Types$WhatToShow]))


(defn system-map []
  (component/system-map
   :nrepl (new-repl-server 5554 "0.0.0.0")
   :ewrapper (new-ewrapper)))

(set-init! #'system-map)
(defn start-system [] (start))
(defn stop-system [] (stop))


#_(defn consume-subscriber-historical [historical-atom subscriber-chan]
  (go-loop [r1 nil]

    (let [{:keys [req-id date open high low close volume count wap has-gaps] :as val} r1]
      (swap! historical-atom assoc date val))
    (recur (<! subscriber-chan))))

#_(defn historical-start [req-id client publication historical-atom]

  (let [subscriber (chan)]
    (ei/historical-subscribe req-id client)
    (sub publication req-id subscriber)
    (consume-subscriber-historical historical-atom subscriber)))

#_(defn historical-stop [])

;; TODO

;; Add these to the 'platform/ibgateway' namespace
;;   scanner-start ( ei/scanner-subscribe )
;;   scanner-stop ( ei/scanner-unsubscribe )

;; record connection IDs

;; migrate data sink atoms (high-opt-imp-volat, high-opt-imp-volat-over-hist, etc)
;;   > to core.async channels > then to onyx output (mostly kafka)

;; CONFIG for
;;   network name of tws

;; TESTs for ibgateway
;;   enable core.async onyx transport for services
;;   workbench for data transport in and out of service
;;   workbench for subscribing to tws
;;
;;   test if open, remain open
;;   test if closed, remain closed
;;   test start scanning; we capture distinct categories (volatility, etc)
;;   test stop scanning
;;   test toggle scan
{:scanner-command :start}
{:scanner-command :stop}


;; write (Transit) to Kafka
;; read (Transit) from Kafka
;; feed to analysis


#_(defn market-start [])
#_(defn market-stop [])
#_(defn open-request-ids [])

(defn -main [& args]
  (Thread/sleep 5000) ;; a hack, to ensure that the tws machine is available, before we try to connect to it.
  (start-system))

(comment
  (start-system)
  (reset)
  (stop))
