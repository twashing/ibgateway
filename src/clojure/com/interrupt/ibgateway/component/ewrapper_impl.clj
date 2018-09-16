(ns com.interrupt.ibgateway.component.ewrapper-impl
  (:require [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [inflections.core :as inflections]
            [net.cgrand.xforms :as x])
  (:import [com.ib.client Contract EReader ScannerSubscription]
           com.ib.controller.ScanCode
           com.interrupt.ibgateway.EWrapperImpl))

(defn create-contract
  [symbol]
  (doto (Contract.)
    (.symbol symbol)
    (.secType "STK")
    (.exchange "SMART")
    (.currency "USD")))

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

(def scan-codes (->> ScanCode .getEnumConstants (map str) sort))

(defn scan-code-ch-kw
  [code]
  (keyword (format "%s-ch" (str/lower-case (inflections/dasherize code)))))

(def scan-code-req-id-base 1000)

(def req-id->scan-code-ch-kw
  (->> (map-indexed (fn [i code]
                      [(+ scan-code-req-id-base i)
                       (scan-code-ch-kw code)])
                    scan-codes)
       (into {})))

(defn scan-code-ch-kw->req-id
  [ch-kw]
  (-> {}
      (into (for [[k v] req-id->scan-code-ch-kw] [v k]))
      (get ch-kw)))

(def relevant-scan-codes
  ["HIGH_OPT_IMP_VOLAT"
   "HIGH_OPT_IMP_VOLAT_OVER_HIST"
   "HOT_BY_VOLUME"
   "TOP_VOLUME_RATE"
   "HOT_BY_OPT_VOLUME"
   "OPT_VOLUME_MOST_ACTIVE"
   "MOST_ACTIVE_USD"
   "HOT_BY_PRICE"
   "TOP_PRICE_RANGE"
   "HOT_BY_PRICE_RANGE"])

(def scanner-num-rows 10)

(defn scanner-subscription
  [instrument location-code scan-code]
  (doto (ScannerSubscription.)
    (.instrument instrument)
    (.locationCode location-code)
    (.scanCode scan-code)
    (.numberOfRows scanner-num-rows)))

(defn scanner-subscribe
  [client instrument location-code scan-code]
  (let [req-id (-> scan-code
                   scan-code-ch-kw
                   scan-code-ch-kw->req-id)
        subscription (scanner-subscription instrument location-code scan-code)]
    (.reqScannerSubscription client (int req-id) subscription nil)
    req-id))

(defn scanner-unsubscribe [client req-id]
  (.cancelScannerSubscription client req-id))

(defn scanner-chs
  "Return map of scan code types (as keywords) to channels partitioned by n."
  []
  (into {} (for [code relevant-scan-codes]
             [(scan-code-ch-kw code)
              (->> scanner-num-rows
                   x/partition
                   (async/chan (async/sliding-buffer 100)))])))

(defn ewrapper-impl
  [{ch :publisher :keys [scanner-chs]}]
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
                 :sec-type (. contract getSecType)
                 :rank rank}]
        (if-let [scanner-ch (->> req-id
                                 req-id->scan-code-ch-kw
                                 (get scanner-chs))]
          (async/put! scanner-ch val)
          (log/warnf "No scanner channel for req-id %s" req-id))))

    (scannerDataEnd [req-id]
      (let [val {:topic :scanner-data
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

(def default-chs-map
  {:publisher (-> 1000 async/sliding-buffer async/chan)
   :scanner-chs (scanner-chs)
   :scanner-decision-ch (-> 100 async/sliding-buffer async/chan)})

(defn ewrapper
  ([]
   (ewrapper "tws"))
  ([host]
   (ewrapper host 4002))
  ([host port]
   (ewrapper host port 1))
  ([host port client-id]
   (ewrapper host port client-id default-chs-map))
  ([host port client-id chs-map]
   (ewrapper host port client-id chs-map default-exception-handler))
  ([host port client-id chs-map ex-handler]
   (let [wrapper (ewrapper-impl chs-map)
         client (.getClient wrapper)
         signal (.getSignal wrapper)]
     (.eConnect client host port client-id)
     (let [ereader (EReader. client signal)]
       (.start ereader)
       (future
         (while (.isConnected client)
           (.waitForSignal signal)
           (try (.processMsgs ereader)
                (catch Exception e (ex-handler e))))))
     (merge {:client client
             :wrapper wrapper}
            chs-map))))
