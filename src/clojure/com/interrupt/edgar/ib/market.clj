(ns com.interrupt.edgar.ib.market
  (:import [com.ib.client EWrapper EClientSocket Contract Order OrderState ContractDetails Execution])
  (:use [clojure.core.strint])
  (:require [com.interrupt.edgar.eclientsocket :as socket]
            [lamina.core :as lamina]
            [overtone.at-at :as at]
            [clj-time.core :as cime]
            [clj-time.local :as time]
            [clj-time.format :as format]))


(defn connect-to-market
  "Connect to the IB marketplace. This should return a 'client' object"
  []
  #_(socket/connect-to-tws)
  )

(defn disconnect-from-market
  "Disconnect from the IB marketplace."
  []
  #_(socket/disconnect-from-tws))

(defn- create-contract [instrm]

  #_(let [contract (Contract.)]
      (set! (.m_symbol contract) instrm)
      (set! (.m_secType contract) "STK")
      (set! (.m_exchange contract) "SMART")
      (set! (.m_currency contract) "USD")

      contract)

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
     (request-market-data client idx instrm "" false))

  ([client idx instrm genericTicklist snapshot]
     (let [contract (create-contract instrm)]

       ;; reqMktData(int, com.ib.client.Contract, java.lang.String, boolean);
       (.reqMktData client (.intValue idx) contract genericTicklist snapshot nil))))

(defn cancel-market-data
  "Cancel the request ID, used in 'request-market-data'"
  [client idx]

  (.cancelMktData client idx))


(defonce event-channel (ref nil))
(defn create-event-channel []
  (dosync (alter event-channel (fn [inp] (lamina/channel)))))
(create-event-channel)



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



;; ====
;; SUBSCRIPTION code
(defn close-market-channel []
  (lamina/force-close @event-channel))

(defn subscribe-to-market [handle-fn]
  (lamina/receive-all @event-channel handle-fn))

(defn publish-event [^clojure.lang.PersistentHashMap event]
  (lamina/enqueue @event-channel event))


;; transform java.util.HashMap to a Clojure map
(defn publish-event-from-java [^java.util.HashMap event]
  (publish-event (merge {} event)))


;; ==========
(defn test-publisher []

  (subscribe-to-market #(println "handling: " %))

  (def my-pool (at/mk-pool))
  (at/every 1000 (fn [] (publish-event { :tickerId 0 :field 1 :price 5.75 :canAutoExecute 1})) my-pool) )
;; ==========
