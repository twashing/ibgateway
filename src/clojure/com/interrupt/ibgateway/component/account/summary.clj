(ns com.interrupt.ibgateway.component.account.summary
  (:require [clojure.core.match :refer [match]]
            [clojure.core.async :as async :refer [<! go-loop]]
            [clojure.string :as str]
            [mount.core :refer [defstate] :as mount]
            [com.interrupt.edgar.subscription :as sub]
            ;; [com.interrupt.ibgateway.component.ewrapper :as ew]
            [com.interrupt.ibgateway.component.account.common :refer [next-reqid!]]
            [inflections.core :as inflections]))


(def account-summary (atom nil))
(def account-summary-ch (async/chan (async/sliding-buffer 40)))

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


#_(defstate summary
  :start (do (reset! req-id (next-reqid! []))
             (.start (:client ew/ewrapper) @req-id))
  :stop (.stop (:client ew/ewrapper) @req-id))
