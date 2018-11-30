(ns com.interrupt.ibgateway.record-live-data
  (:require  [clojure.core.async :refer [chan sliding-buffer] :async async]
             [mount.core :refer [defstate] :as mount]
             [unilog.config :refer [start-logging!]]
             [clojure.tools.namespace.repl :as tn]
             [clojure.tools.logging :refer [info]]
             [clojure.tools.cli :refer [parse-opts]]
             [com.interrupt.ibgateway.component.common :refer [bind-channels->mult]]
             [com.interrupt.ibgateway.component.ewrapper :as ew]
             [com.interrupt.ibgateway.component.repl-server]
             [com.interrupt.ibgateway.component.switchboard :as switchboard])
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
                :file     "logs/record-live-data.log"}]
   :overrides  {"org.apache.http"      :debug
                "org.apache.http.wire" :error}})

(start-logging! logging-config)

(defstate state
  :start {:running true}
  :stop (assoc state :running false))


(defn init [_]
  (mount/start #'com.interrupt.ibgateway.component.ewrapper/default-chs-map
               #'com.interrupt.ibgateway.component.ewrapper/ewrapper
               #'com.interrupt.ibgateway.component.repl-server/server))

(defn start []
  (while (:running state)))

(defn stop [client live-subscriptions]
  (switchboard/record-live-data-stop client live-subscriptions)
  (mount/stop #'com.interrupt.ibgateway.component.ewrapper/default-chs-map
              #'com.interrupt.ibgateway.component.ewrapper/ewrapper
              #'com.interrupt.ibgateway.component.repl-server/server))

(defn go' []
  (init nil)
  :ready)

(defn reset [client live-subscriptions]
  (stop client live-subscriptions)
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
      (-> parsed-options :options :record)
      (let [client (:client ew/ewrapper)
            publisher (:publisher ew/default-chs-map)
            publisher-dupl (chan (sliding-buffer 100))]

        (bind-channels->mult publisher publisher-dupl)
        (switchboard/record-live-data ew/ewrapper switchboard/stock-scans publisher-dupl))

      :else (info "No args provided"))

    (start)))

(comment

  (let [client (:client ew/ewrapper)]
    (stop client switchboard/live-subscriptions))

  (let [client (:client ew/ewrapper)]
    (reset client switchboard/live-subscriptions)))
