(ns com.interrupt.edgar.subscription
  (:require [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojure.core.async :as async :refer [>! go]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.interrupt.ibgateway.component.ewrapper-impl :as ewrapper-impl])
  (:import [com.ib.client Contract ScannerSubscription]
           java.io.PushbackReader))

(defprotocol Subscription
  (subscribe [this] "Return channel containing subscribed values.")
  (unsubscribe [this]))

(defrecord FileSubscription [path ch]
  Subscription
  (subscribe [_]
    (go
      (with-open [r (PushbackReader. (io/reader path))]
        (try
          (loop []
            (when-let [v (edn/read {:eof nil} r)]
              (>! ch v)
              (recur)))
          (finally (async/close! ch)))))
    ch)
  (unsubscribe [_]))

(defrecord LiveSubscription [client ch ticker-id
                             ^Contract contract
                             ^String generic-tick-list
                             ^Boolean snapshot?
                             options]
  Subscription
  (subscribe [_]
    (.reqMktData client (int ticker-id)
                 contract generic-tick-list (boolean snapshot?) options)
    ch)
  (unsubscribe [_] (.cancelMktData client ticker-id)))


(defrecord HistoricalDataSubscription [client ch ticker-id
                                       ^Contract contract
                                       ^String end-datetime
                                       ^String duration-str
                                       ^String bar-size-setting
                                       ^String what-to-show
                                       ^Integer use-rth
                                       ^Integer format-date
                                       chart-options]
  Subscription
  (subscribe [_]
    (.reqHistoricalData client (int ticker-id) contract end-datetime
                        duration-str bar-size-setting what-to-show
                        (int use-rth) (int format-date) chart-options)
    ch)
  (unsubscribe [_] (.cancelHistoricalData client ticker-id)))

(def datetime-formatter (tf/formatter "yyyyMMdd HH:mm:ss"))

(defn historical-data-subscription
  ([client ch ticker-id]
   (->> "TSLA"
        ewrapper-impl/create-contract
        (historical-data-subscription client ch ticker-id)))
  ([client ch ticker-id contract]
   (->> 16
        t/days
        (t/minus (t/now))
        (tf/unparse datetime-formatter)
        (historical-data-subscription client ch ticker-id contract)))
  ([client ch ticker-id contract end-datetime]
   (->HistoricalDataSubscription client ch ticker-id contract end-datetime
                                 "4 M" "1 min" "TRADES" 1 1 nil)))

(defrecord ScannerSubSubscription [client ch ticker-id
                                   ^ScannerSubscription subscription
                                   options]
  Subscription
  (subscribe [_]
    (.reqScannerSubscription client ticker-id subscription options)
    ch)
  (unsubscribe [_] (.cancelScannerSubscription client ticker-id)))
