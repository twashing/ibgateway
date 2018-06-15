(ns com.interrupt.ibgateway.core
  (:require  [mount.core :refer [defstate] :as mount]
             [unilog.config  :refer [start-logging!]]
             [com.interrupt.ibgateway.component.repl-server]
             [com.interrupt.ibgateway.component.switchboard]
             [com.interrupt.ibgateway.component.vase])
  (:import [org.apache.commons.daemon Daemon DaemonContext])
  (:gen-class
   :implements [org.apache.commons.daemon.Daemon]))


(def logging-config
  {:level   :info
   :console true
   ;; :file "logs/ibgateway.log"
   :appenders [{:appender :rolling-file
                :rolling-policy {:type :fixed-window
                                 :max-index 5}
                :triggering-policy {:type :size-based
                                    :max-size 5120}
                :file     "logs/ibgateway.log"}]
   :overrides  {"org.apache.http"      :debug
                "org.apache.http.wire" :error}})

(start-logging! logging-config)

(defstate state
  :start {:running true}
  :stop (assoc state :running false))

(defn init [_]
  (mount/start))

(defn start []
  (while (:running state)
    ;; (println "tick")
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

  ;; TODO - do we still need this?
  (Thread/sleep 5000) ;; a hack, to ensure that the tws machine is available, before we try to connect to it.

  (init args)
  (start))


(comment

  state

  (-main nil)

  (mount/start #'com.interrupt.ibgateway.component.ewrapper/ewrapper)
  (mount/stop #'com.interrupt.ibgateway.component.ewrapper/ewrapper)

  (mount/start #'com.interrupt.ibgateway.component.vase/server)

  (mount/start)
  (mount/stop)
  (mount/find-all-states)
  (require 'com.interrupt.ibgateway.component.ewrapper))



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
