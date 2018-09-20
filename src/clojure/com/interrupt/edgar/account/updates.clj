(ns com.interrupt.edgar.account.updates
  (:require [clojure.core.async :as async :refer [<! go-loop]]
            [clojure.string :as str]
            [com.interrupt.edgar.subscription :as sub]))

(def accounts-info (atom nil))

(defn remove-not-ready
  [updates]
  (->> updates
       (reduce (fn [[ready? coll] {:keys [key value] :as m}]
                 (cond
                   (and (= "AccountReady" key) (= "true" value)) [true coll]
                   (and (= "AccountReady" key) (= "false" value)) [false coll]
                   :else [ready? (if ready? (conj coll m) coll)]))
               [true []])
       second))

(def ch-xform (comp (partition-by #(= ::download-end %))
                    (remove #(= [::download-end] %))
                    (map remove-not-ready)))

(def account-updates-ch (async/chan (async/sliding-buffer 1000) ch-xform))

(defrecord AccountUpdatesSubscription [client ch ^String acct-code]
  sub/Subscription
  (subscribe [_]
    (.reqAccountUpdates client true acct-code)
    ch)
  (unsubscribe [_]
    (.reqAccountUpdates client false acct-code)))

(defn start
  [client acct-code]
  (let [ch (-> client
               (->AccountUpdatesSubscription account-updates-ch acct-code)
               sub/subscribe)]
    (go-loop []
      (when-let [ms (<! ch)]
        (swap! accounts-info
               #(reduce (fn [info {:keys [account-name key value]}]
                          (assoc-in info [account-name key] value))
                        %
                        ms))
        (recur)))))

(defn stop
  [client acct-code]
  (.reqAccountUpdates client false acct-code))
