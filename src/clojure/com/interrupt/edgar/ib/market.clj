(ns com.interrupt.edgar.ib.market
  (:import [com.ib.client EWrapper EClientSocket Contract Order OrderState ContractDetails Execution])
  (:use [clojure.core.strint])
  (:require [com.interrupt.edgar.eclientsocket :as socket]
            [clojure.core.async :refer [chan >! <! merge go go-loop pub sub unsub-all sliding-buffer]]
            [overtone.at-at :as at]
            [clj-time.core :as cime]
            [clj-time.local :as time]
            [clj-time.format :as format]))


(defn connect-to-market
  "Connect to the IB marketplace. This should return a 'client' object"
  []
  #_(socket/connect-to-tws))

(defn disconnect-from-market
  "Disconnect from the IB marketplace."
  []
  #_(socket/disconnect-from-tws))

(defn- create-contract [instrm]
  (doto (Contract.)
    (.symbol instrm)
    (.secType "STK")
    (.exchange "SMART")
    (.currency "USD")))

(defn- create-order [action qty price]

  (let [order (Order.)]
    (set! (.m_totalQuantity order) qty)
    (set! (.m_action order) action)
    (set! (.m_orderType order) "LMT")
    (set! (.m_lmtPrice order) price)

    order))


;; ====
;; HISTORICAL Data
(defn request-historical-data
  "Request historical historical information in the form of a feed or data snapshot.

   See function reference here:
     http://www.interactivebrokers.com/php/apiUsersGuide/apiguide/java/reqhistoricaldata.htm"

  ([client idx instrm]
     (request-historical-data client idx instrm "1 D" "1 day" "TRADES"))

  ([client idx instrm , duration-str bar-size what-to-show]
     (let [contract (create-contract instrm)
           nnow (time/local-now)
           tformat (format/formatter "yyyyMMdd HH:mm:ss z")
           tstring (format/unparse tformat nnow)]
       (.reqHistoricalData client idx contract tstring duration-str bar-size what-to-show 1 1) )))

(defn cancel-historical-data
  "Cancel the request ID, used in 'request-historical-data'"
  [client idx]
  (.cancelHistoricalData client idx))




(use '[clojure.reflect :only [reflect]])
(use '[clojure.string :only [join]])

(defn inspect [obj]
  "nicer output for reflecting on an object's methods"
  (let [reflection (reflect obj)
        members (sort-by :name (:members reflection))]
    (println "Class:" (.getClass obj))
    (println "Bases:" (:bases reflection))
    (println "---------------------\nConstructors:")
    (doseq [constructor (filter #(instance? clojure.reflect.Constructor %) members)]
      (println (:name constructor) "(" (join ", " (:parameter-types constructor)) ")"))
    (println "---------------------\nMethods:")
    (doseq [method (filter #(instance? clojure.reflect.Method %) members)]
      (println (:name method) "(" (join ", " (:parameter-types method)) ") ;=>" (:return-type method)))))


;; ====
;; MARKET Data
(defn request-market-data
  "Request historical market information in the form of a feed or data snapshot"

  ([client idx instrm]
   (request-market-data client idx instrm nil false))

  ([client idx instrm genericTicklist snapshot]
     (let [contract (create-contract instrm)]

       ;; As of v969?
       ;; https://interactivebrokers.github.io/tws-api/market_data_type.html

       ;; Switch to live (1) frozen (2) delayed (3) or delayed frozen (4)
       ;; client.reqMarketDataType(1);

       ;; reqMktData(int, com.ib.client.Contract, java.lang.String, boolean);
       (.reqMktData client (.intValue idx) contract genericTicklist snapshot nil))))

(defn cancel-market-data
  "Cancel the request ID, used in 'request-market-data'"
  [client idx]

  (.cancelMktData client idx))


;; ====
;; BUY / SELL stock
(defn buy-stock [client idx instrm qty price]

  (let [contract (create-contract instrm)
        order (create-order "BUY" qty price)]

    (println (str "buy-stock >> client[" client "] / idx[" idx "] / instrm[" instrm "] / contract[" contract "] / order[" order "]"))
    (.placeOrder client idx contract order)))

(defn sell-stock [client idx instrm qty price]

  (let [contract (create-contract instrm)
        order (create-order "SELL" qty price)]

    (println (str "sell-stock >> client[" client "] / idx[" idx "] / instrm[" instrm "] / contract[" contract "] / order[" order "]"))
    (.placeOrder client idx contract order)))


(defn consume [handle-fn channel]
  (go-loop []
    (handle-fn (<! channel))
    (recur)))

(defn subscribe-to-market [publisher handle-fn]

  (let [publication (pub publisher #(:topic %))
        subscriber (chan)]

    (sub publication :tick-price subscriber)
    (sub publication :tick-size subscriber)
    (sub publication :tick-string subscriber)

    (consume handle-fn subscriber)))
