(ns com.interrupt.ibgateway.core
  (:require  [mount.core :refer [defstate] :as mount]
             [unilog.config :refer [start-logging!]]
             [clojure.tools.logging :refer [debug info warn error]]
             [clojure.tools.cli :refer [parse-opts]]
             [clojure.tools.namespace.repl :as tn]
             [com.interrupt.ibgateway.component.ewrapper]
             [com.interrupt.ibgateway.component.switchboard.store]
             [com.interrupt.ibgateway.component.processing-pipeline]
             [com.interrupt.ibgateway.component.repl-server]
             [com.interrupt.ibgateway.component.figwheel.repl-server]
             [com.interrupt.ibgateway.component.switchboard :as switchboard]
             [com.interrupt.ibgateway.component.vase :as vase]
             [com.interrupt.ibgateway.cloud.storage])
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
                "org.apache.http.wire" :error}})

(start-logging! logging-config)

(defstate state
  :start {:running true}
  :stop (assoc state :running false))

(defn init [_]
  (mount/start ;; #'com.interrupt.ibgateway.component.ewrapper/ewrapper
               ;; #'com.interrupt.ibgateway.component.switchboard/control-channel
               ;; ;; #'com.interrupt.ibgateway.component.switchboard.store/conn
               ;; #'com.interrupt.ibgateway.component.processing-pipeline/processing-pipeline
               #'com.interrupt.ibgateway.component.repl-server/server
               ;; ;; #'com.interrupt.ibgateway.component.figwheel.repl-server/server
               ;; ;; #'com.interrupt.ibgateway.component.figwheel.figwheel/figwheel
               ;; #'com.interrupt.ibgateway.component.vase/server
               ;; ;; #'com.interrupt.ibgateway.cloud.storage/s3
               ;; ;; #'com.interrupt.ibgateway.core/state
               ))

(defn start []
  (while (:running state)
    ;; (println "tick")
    ;; (Thread/sleep 2000)
    ))

(defn stop []
  (mount/stop ;; #'com.interrupt.ibgateway.component.ewrapper/ewrapper
              ;; #'com.interrupt.ibgateway.component.switchboard/control-channel
              ;; #'com.interrupt.ibgateway.component.switchboard.store/conn
              ;; #'com.interrupt.ibgateway.component.processing-pipeline/processing-pipeline
              #'com.interrupt.ibgateway.component.repl-server/server
              ;; ;; #'com.interrupt.ibgateway.component.figwheel.repl-server/server
              ;; ;; #'com.interrupt.ibgateway.component.figwheel.figwheel/figwheel
              ;; #'com.interrupt.ibgateway.component.vase/server
              ;; ;; #'com.interrupt.ibgateway.cloud.storage/s3
              ;; #'com.interrupt.ibgateway.core/state
              ))

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
