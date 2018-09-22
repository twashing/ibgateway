(ns com.interrupt.edgar.account.updates
  (:require [clojure.core.async :as async :refer [<! go-loop]]
            [clojure.string :as str]
            [com.interrupt.edgar.account.portfolio :as portfolio]
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

    ;; Account updates
    (go-loop []
      (when-let [ms (<! ch)]
        (swap! accounts-info
               #(reduce (fn [info {:keys [account-name key value]}]
                          (assoc-in info [account-name key] value))
                        %
                        ms))
        (recur)))

    ;; Portfolio updates
    (go-loop []
      (when-let [m (<! portfolio/portfolio-updates-ch)]
        (swap! portfolio/portfolio-info
               #(let [{:keys [account-name]
                       {:keys [conid] :as contract} :contract} m
                      v (-> m
                            (dissoc :account-name)
                            (assoc :contract (dissoc contract :conid)))]
                  (assoc-in % [account-name conid] v)))
        (recur)))))

(defn stop
  [client acct-code]
  (.reqAccountUpdates client false acct-code))
