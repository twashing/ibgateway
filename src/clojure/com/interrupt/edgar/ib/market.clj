(ns com.interrupt.edgar.ib.market
  (:import [com.ib.client EWrapper EClientSocket Contract Order OrderState ContractDetails Execution])
  (:require [clj-time.core :as cime]
            [clj-time.format :as format]
            [clj-time.local :as time]
            [clojure.tools.trace :refer [trace]]
            [clojure.tools.logging :refer [debug info]]
            [clojure.core.async :refer [chan >! <! merge go go-loop pub sub unsub-all sliding-buffer]]
            [clojure.core.strint :refer :all]
            [com.interrupt.ibgateway.component.common :as common]
            [com.interrupt.ibgateway.component.account.contract :as contract]
            [com.interrupt.ibgateway.component.ewrapper-impl]))


;; HISTORICAL Data
(defn request-historical-data
  "Request historical historical information in the form of a feed or data snapshot.

   See function reference here:
     http://www.interactivebrokers.com/php/apiUsersGuide/apiguide/java/reqhistoricaldata.htm"

  ([client idx instrm]
   (request-historical-data client idx instrm "1 D" "1 day" "TRADES"))

  ([client idx instrm , duration-str bar-size what-to-show]
   (let [contract (contract/create instrm)
         nnow (time/local-now)
         tformat (format/formatter "yyyyMMdd HH:mm:ss z")
         tstring (format/unparse tformat nnow)]
     (.reqHistoricalData client idx contract tstring duration-str bar-size what-to-show 1 1) )))

(defn cancel-historical-data
  "Cancel the request ID, used in 'request-historical-data'"
  [client idx]
  (.cancelHistoricalData client idx))


;; MARKET Data
(defn request-market-data
  "Request historical market information in the form of a feed or data snapshot"

  ([client idx instrm]
   (request-market-data client idx instrm nil false))

  ([client idx instrm genericTicklist snapshot]
   (let [contract (contract/create instrm)
         regulatorySnaphsot false
         mktDataOptions []]

     ;; As of v969?
     ;; https://interactivebrokers.github.io/tws-api/market_data_type.html

     ;; Switch to live (1) frozen (2) delayed (3) or delayed frozen (4)
     ;; client.reqMarketDataType(1);

     (.reqMktData client (.intValue idx) contract genericTicklist snapshot regulatorySnaphsot mktDataOptions))))

(defn cancel-market-data
  "Cancel the request ID, used in 'request-market-data'"
  [client idx]
  (.cancelMktData client idx))


;; BUY / SELL stock
(defmulti buy-stock (fn [_ _ order-type _ _ _ _] order-type))

(defmethod buy-stock "MKT" [client order-id order-type account-name instrm qty _price]

  (info "buy-stock MKT CALLED /" [client order-id order-type account-name instrm qty _price])
  (let [contract (contract/create instrm)
        order (doto (Order.)
                (.action "BUY")
                (.orderType order-type)
                (.totalQuantity qty)
                (.account account-name))]
    (.placeOrder client order-id contract order)))

(defmethod buy-stock "LMT" [client order-id order-type account-name instrm qty price]

  (info "buy-stock LMT CALLED /" [client order-id order-type account-name instrm qty price])
  (.placeOrder client
               order-id
               (contract/create instrm)
               (doto (Order.)
                 (.action "BUY")
                 (.orderType order-type)
                 (.totalQuantity qty)
                 (.lmtPrice price)
                 (.account account-name))))

(defmethod buy-stock "TRAIL" [client order-id order-type account-name instrm qty price]

  (info "buy-stock TRAIL CALLED /" [client order-id order-type account-name instrm qty price])
  (let [;; auxPrice (->> @common/latest-standard-deviation
        ;;               (clojure.pprint/cl-format nil "~,2f")
        ;;               read-string
        ;;               (Double.)
        ;;               (* common/balancing-sell-standard-deviation-multiple)
        ;;               (clojure.pprint/cl-format nil "~,2f")
        ;;               read-string)
        auxPrice 0.01
        trailStopPrice (+ price auxPrice)]
    (.placeOrder client
                 order-id
                 (contract/create instrm)
                 (doto (Order.)
                   (.action "BUY")
                   (.orderType order-type)
                   (.totalQuantity qty)
                   (.auxPrice auxPrice)
                   (.trailStopPrice trailStopPrice)
                   (.account account-name)))))


(defmulti sell-stock (fn [_ _ order-type _ _ _ _] order-type))

(defmethod sell-stock "MKT" [client order-id order-type account-name instrm qty _price]

  (let [contract (contract/create instrm)
        order (doto (Order.)
                (.action "SELL")
                (.orderType order-type)
                (.totalQuantity qty)
                (.account account-name))]
    (.placeOrder client order-id contract order)))

(defmethod sell-stock "TRAIL" [client order-id order-type account-name instrm qty price]

  (let [action "SELL"
        trailingPercent 1]

    (.placeOrder client
                 order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType order-type)
                   (.trailingPercent trailingPercent)
                   (.trailStopPrice price)
                   (.totalQuantity qty)))))

(defmethod sell-stock "TRAIL LIMIT" [client order-id order-type account-name instrm qty price]

  (let [action "SELL"
        lmtPriceOffset 0.1
        trailingAmount 0.2]

    (.placeOrder client
                 order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType order-type)
                   (.lmtPriceOffset lmtPriceOffset)
                   (.auxPrice trailingAmount)
                   (.trailStopPrice price)
                   (.totalQuantity qty)))))
