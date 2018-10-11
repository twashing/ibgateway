(ns com.interrupt.ibgateway.component.account.summary
  (:require [clojure.core.match :refer [match]]
            [clojure.core.async :as async :refer [<! go-loop]]
            [clojure.string :as str]
            [clojure.set :as cs]
            [mount.core :refer [defstate] :as mount]
            [com.interrupt.edgar.subscription :as sub]
            [com.interrupt.ibgateway.component.ewrapper :as ew]
            [inflections.core :as inflections]))


(def account-summary (atom nil))
(def account-summary-ch (async/chan (async/sliding-buffer 100)))

(def reqids (atom []))
(def id-largest 100)


;; Message structure
;;
;; (availableids [2 3])   ;; [(2 3) (1 4 5 6 7 8 9 10)]
;; (availableids [6 9])   ;; [(6 9) (1 2 3 4 5 7 8 10)]
;; (availableids [2 1 6 9])   ;; [(1 2 6 9) (3 4 5 7 8 10)]
(defn availableids [scanner-subscriptions]
  (let [existing-ids (sort scanner-subscriptions)
        first-id (first existing-ids)
        contiguous-numbers (take 10 (range 1 id-largest))
        aids (sort (cs/difference (into #{} contiguous-numbers)
                                  (into #{} existing-ids)))]

    [existing-ids aids]))


;; (next-reqid [])
;; (next-reqid [2 1 4 6 9])  ;; 3
;; (next-reqid [2 1 3 6 9])  ;; 4
(defn next-reqid [rids]
  (cond (->> rids
             ((juxt nil? empty?))
             (every? (comp not false?))) 1
        :else (let [ids (availableids rids)]
                (if-not (empty? ids)
                  (-> ids second first)
                  1))))

(defrecord AccountSummarySubscription [client req-id ch
                                       ^String group
                                       ^String tags]
  sub/Subscription
  (subscribe [_]
    (.reqAccountSummary client (int req-id) group tags)
    ch)
  (unsubscribe [_]
    (.cancelAccountSummary client req-id)))

(def default-tags ["TotalCashValue"])

(defn parse-tag-value
  [tag v]
  (case tag
    "TotalCashValue" (Double/parseDouble v)
    v))

(defn start
  ([client req-id]
   (start client req-id "All"))
  ([client req-id group]
   (start client req-id group (str/join "," default-tags)))
  ([client req-id group tags]
   (sub/subscribe (->AccountSummarySubscription client req-id account-summary-ch group tags))
   (go-loop []
     (when-let [{:keys [account tag value]} (<! account-summary-ch)]
       (swap! account-summary assoc-in [account tag] value)
       (recur)))))

(defn stop
  [client req-id]
  (.cancelAccountSummary client req-id))

(def req-id (atom nil))


(defstate summary
  :start (do (reset! req-id (next-reqid []))
             (.start (:client ew/ewrapper) @req-id))
  :stop (.stop (:client ew/ewrapper) @req-id))
