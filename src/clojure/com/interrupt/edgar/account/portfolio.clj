(ns com.interrupt.edgar.account.portfolio
  (:require [clojure.core.async :as async]
            [clojure.pprint :as pprint]))

(def portfolio-info (atom nil))

(def portfolio-updates-ch (async/chan (async/sliding-buffer 100)))

(defn print-portfolio
  ([]
   (print-portfolio @portfolio-info))
  ([p]
   (doseq [acct (keys p)
           :let [contracts (map (fn [[k {{:keys [symbol]} :contract :as v}]]
                                  (assoc v :conid k :symbol symbol))
                                (get p acct))
                 ks [:conid
                     :symbol
                     :position
                     :market-price
                     :market-value
                     :average-cost
                     :unrealized-pnl
                     :realized-pnl]]]
     (pprint/print-table ks contracts))))
