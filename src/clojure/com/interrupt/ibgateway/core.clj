(ns com.interrupt.ibgateway.core
  (:require  [mount.core :refer [defstate] :as mount]
             [unilog.config :refer [start-logging!]]
             [clojure.tools.namespace.repl :as tn]
             [clojure.tools.logging :refer [debug info warn error]]
             [clojure.tools.cli :refer [parse-opts]]
             [com.interrupt.ibgateway.component.repl-server])
  (:import [org.apache.commons.daemon Daemon DaemonContext])
  (:gen-class
   :implements [org.apache.commons.daemon.Daemon]))


(def logging-config
  {:level   :info
   :console true
   :appenders [{:appender :rolling-file
                :rolling-policy {:type :fixed-window
                                 :max-index 5}
                :triggering-policy {:type :size-based
                                    :max-size 5120}
                :file     "logs/ibgateway.log"}]
   :overrides  {"org.apache.http"      :debug
                "org.apache.http.wire" :error
                "org.apache.zookeeper" :info
                "org.apache.kafka.clients.consumer.internals" :info
                "org.apache.kafka.common.network.Selector" :info
                "org.apache.kafka.clients.NetworkClient" :info
                "org.apache.kafka.clients.Metadata" :info
                "org.apache.kafka.clients.producer.KafkaProducer" :info
                "org.apache.kafka.common.metrics.Metrics" :info
                "org.apache.kafka.clients.consumer.KafkaConsumer" :info
                "org.I0Itec.zkclient.ZkConnection" :info
                "org.I0Itec.zkclient.ZkClient" :info
                "ChocoRP" :info}})

(start-logging! logging-config)

(defstate state
  :start {:running true}
  :stop (assoc state :running false))

(defn init [_]
  (mount/start #'com.interrupt.ibgateway.component.repl-server/server))

(defn start []
  (while (:running state)))

(defn stop []
  (mount/stop #'com.interrupt.ibgateway.component.repl-server/server))

(defn go' []
  (init nil)
  :ready)

(defn reset []
  (stop)
  (tn/refresh :after 'com.interrupt.ibgateway.core/go'))



;; Daemon implementation
(defn -init [this ^DaemonContext context]
  (init (.getArguments context)))

(defn -start [this]
  (future (start)))

(defn -stop [this]
  (mount/stop))

(defn -destroy [this])



(def cli-options
  [["-R" "--record" :default true]])

(defn -main [& args]

  ;; TODO - do we still need this?
  (Thread/sleep 5000) ;; a hack, to ensure that the tws machine is available, before we try to connect to it.

  (init args)

  (let [parsed-options (parse-opts args cli-options)]

    (info "parsed-options: " parsed-options)
    (cond

      ;; (-> parsed-options :options :record)
      ;; (switchboard/record-live-data)

      :else (info "No args provided"))

    (start)))
