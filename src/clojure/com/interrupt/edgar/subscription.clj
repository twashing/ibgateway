(ns com.interrupt.edgar.subscription
  (:require [clojure.core.async :as async :refer [go >!]]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io PushbackReader]))

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

(defrecord LiveSubscription [client ch ticker-id contract
                             generic-tick-list snapshot? mkt-data-options]
  Subscription
  (subscribe [_]
    (.reqMktData client (int ticker-id) contract generic-tick-list (boolean snapshot?) mkt-data-options)
    ch)
  (unsubscribe [_] (.cancelMktData client ticker-id)))
