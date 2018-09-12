(ns com.interrupt.ibgateway.component.ewrapper-impl
  (:require [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojure.core.async :as async])
  (:import [com.ib.client Contract ContractDetails EReader ScannerSubscription]
           com.interrupt.ibgateway.EWrapperImpl))

(defn create-contract
  [symbol]
  (doto (Contract.)
    (.symbol symbol)
    (.secType "STK")
    (.exchange "SMART")
    (.currency "USD")))

(defn scanner-subscription
  [instrument location-code scan-code]
  (doto (ScannerSubscription.)
    (.instrument instrument)
    (.locationCode location-code)
    (.scanCode scan-code)))

(defn scanner-subscribe
  [client req-id instrument location-code scan-code]
  (let [subscription (scanner-subscription instrument location-code scan-code)]
    (.reqScannerSubscription client req-id subscription nil)
    req-id))

(defn scanner-unsubscribe [client req-id]
  (.cancelScannerSubscription client req-id))

(def datetime-formatter (tf/formatter "yyyyMMdd HH:mm:ss"))

(defn historical-subscribe
  ([client ticker-id]
   (historical-subscribe client ticker-id (create-contract "TSLA")))
  ([client ticker-id contract]
   (->> (t/minus (t/now) (t/days 16))
        (tf/unparse datetime-formatter)
        (historical-subscribe client ticker-id contract)))
  ([client ticker-id contract end-datetime]
   (.reqHistoricalData client ticker-id contract end-datetime
                       "4 M" "1 min" "TRADES" 1 1 nil)
   ticker-id))

(defn historical-unsubscribe
  [client req-id]
  (.cancelHistoricalData client req-id))

(defn ewrapper-impl
  [ch]
  (proxy [EWrapperImpl] []
    (tickPrice [^Integer ticker-id ^Integer field ^Double price ^Integer can-auto-execute?]
      (let [val {:topic :tick-price
                 :ticker-id ticker-id
                 :field field
                 :price price
                 :can-auto-execute can-auto-execute?}]
        (async/put! ch val)))

    (tickSize [^Integer ticker-id ^Integer field ^Integer size]
      (let [val {:topic :tick-size
                 :ticker-id ticker-id
                 :field field
                 :size size}]
        (async/put! ch val)))

    (tickString [^Integer ticker-id ^Integer tickType ^String value]
      (let [val {:topic :tick-string
                 :ticker-id ticker-id
                 :tick-type tickType
                 :value value}]
        (async/put! ch val)))

    (tickGeneric [^Integer ticker-id ^Integer tickType ^Double value]
      (println "New - Tick Generic. Ticker Id:"  ticker-id  ", Field: " tickType  ", Value: "  value))

    (scannerData [req-id rank contract-details distance benchmark projection _]
      (let [contract (.contract contract-details)
            val {:topic :scanner-data
                 :req-id req-id
                 :message-end false
                 :symbol (. contract symbol)
                 :sec-type (. contract secType)
                 :rank rank}]
        (async/put! ch val)))

    (scannerDataEnd [req-id]
      (let [val {:topic :scanner-data`
                 :req-id req-id
                 :message-end true}]
        (async/put! ch val)))

    (historicalData [req-id date open high low close volume count wap gaps?]
      (let [val {:topic :historical-data
                 :req-id req-id
                 :date date
                 :open open
                 :high high
                 :low low
                 :close close
                 :volume volume
                 :count count
                 :wap wap
                 :has-gaps gaps?}]
        (async/put! ch val)))))

(defn default-exception-handler
  [^Exception e]
  (println "Exception:" (.getMessage e)))

(defn ewrapper
  ([]
   (ewrapper "tws"))
  ([host]
   (ewrapper host 4002))
  ([host port]
   (ewrapper host port 1))
  ([host port client-id]
   (ewrapper host port client-id (-> 1000 async/sliding-buffer async/chan)))
  ([host port client-id ch]
   (ewrapper host port client-id ch default-exception-handler))
  ([host port client-id ch ex-handler]
   (let [wrapper (ewrapper-impl ch)
         client (.getClient wrapper)
         signal (.getSignal wrapper)
         ereader (EReader. client signal)]
     (.eConnect client host port client-id)
     (.start ereader)
     (future
       (while (.isConnected client)
         (.waitForSignal signal)
         (try (.processMsgs ereader)
              (catch Exception e (ex-handler e)))))
     {:client client
      :wrapper wrapper
      :publisher ch})))
