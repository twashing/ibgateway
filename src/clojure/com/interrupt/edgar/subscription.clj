(ns com.interrupt.edgar.subscription
  (:require [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojure.core.async :as async :refer [>! go]]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [com.ib.client Contract]
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
  (unsubscribe [_]
    (async/close! ch)))

(defrecord LiveSubscription [client ch ticker-id
                             ^Contract contract
                             ^String generic-tick-list
                             ^Boolean snapshot?
                             options]
  Subscription
  (subscribe [_]
    #_(let [regulatorySnaphsot false]
        (.reqMktData client (int ticker-id)
                     contract generic-tick-list (boolean snapshot?)
                     regulatorySnaphsot options))
    (.reqMktData client (int ticker-id)
                 contract generic-tick-list (boolean snapshot?) options)
    ch)
  (unsubscribe [_] (.cancelMktData client (int ticker-id))))


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

#_(defn historical-data-subscription
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
