(ns com.interrupt.ibgateway.core
  (:require  [mount.core :refer [defstate] :as mount]
             [com.interrupt.ibgateway.component.repl-server]
             [com.interrupt.ibgateway.component.switchboard]
             #_[system.repl :refer [set-init! init start stop reset refresh system]]
             #_[com.interrupt.ibgateway.component.repl-server :refer [new-repl-server]]
             #_[com.interrupt.ibgateway.component.ewrapper :refer [new-ewrapper]]
             #_[com.interrupt.ibgateway.component.ewrapper-impl :as ei]
             #_[com.interrupt.ibgateway.component.switchboard :refer [new-switchboard]]
             #_[clojure.core.async :refer [chan >! <! merge go go-loop pub sub unsub-all sliding-buffer]])
  (:import [org.apache.commons.daemon Daemon DaemonContext])
  (:gen-class
   :implements [org.apache.commons.daemon.Daemon])
  #_(:import [java.util.concurrent TimeUnit]
           [java.util Calendar]
           [java.text SimpleDateFormat]
           [com.ib.client
            EWrapper EClient EClientSocket EReader EReaderSignal
            Contract ContractDetails ScannerSubscription]
           [com.ib.client Types$BarSize Types$DurationUnit Types$WhatToShow]))


(defstate state
  :start (atom {:running true})
  :stop (swap! state assoc :running false))

(defn init [args]
  (mount/start))

(defn start []
  (while (:running @state)
    (println "tick")
    (Thread/sleep 2000)))


;; Daemon implementation
(defn -init [this ^DaemonContext context]
  (init (.getArguments context)))

(defn -start [this]
  (future (start)))

(defn -stop [this]
  (mount/stop))

(defn -destroy [this])

(defn -main [& args]
  (init args)
  (start))

(comment

  state

  (-main nil)

  (mount/start)
  (mount/stop)

  )


#_(defn system-map []
  (component/system-map
   :nrepl (new-repl-server 5554 "0.0.0.0")
   :ewrapper (new-ewrapper)
   :switchboard (component/using
                 (new-switchboard "zookeeper:2181" "kafka:9092")
                 [:ewrapper])))

#_(set-init! #'system-map)
#_(defn start-system [] (start))
#_(defn stop-system [] (stop))




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


;; **
;; kafka-listener -> Kafka
;; ewrapper -> TWS
;; migrate data sink atoms (high-opt-imp-volat, high-opt-imp-volat-over-hist, etc)
;;   > to core.async channels > then to kafka output


;; Add these to the 'platform/ibgateway' namespace
;;   scanner-start ( ei/scanner-subscribe )
;;   scanner-stop ( ei/scanner-unsubscribe )

;; record connection IDs

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
;; {:scanner-command :start}
;; {:scanner-command :stop}


;; write (Transit) to Kafka
;; read (Transit) from Kafka
;; feed to analysis


#_(defn market-start [])
#_(defn market-stop [])
#_(defn open-request-ids [])

#_(defn -main [& args]
  (Thread/sleep 5000) ;; a hack, to ensure that the tws machine is available, before we try to connect to it.
  (mount/start))

(comment
  (mount/start)
  (mount/stop))
