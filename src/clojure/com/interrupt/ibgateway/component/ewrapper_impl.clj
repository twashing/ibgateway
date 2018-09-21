(ns com.interrupt.ibgateway.component.ewrapper-impl
  (:require [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojure.tools.logging :refer [debug info warn error]]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [com.interrupt.edgar.account.summary :as acct-summary]
            [com.interrupt.edgar.account.updates :as acct-updates]
            [com.interrupt.edgar.account.portfolio :as portfolio]
            [com.interrupt.edgar.contract :as contract]
            [com.interrupt.edgar.obj-convert :as obj-convert]
            [com.interrupt.edgar.scanner :as scanner])
  (:import [com.ib.client Contract EReader]
           com.interrupt.ibgateway.EWrapperImpl))

(def valid-order-id (atom -1))

(def datetime-formatter (tf/formatter "yyyyMMdd HH:mm:ss"))

(defn historical-subscribe
  ([client ticker-id]
   (historical-subscribe client ticker-id (contract/create "TSLA")))
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

(defn scanner-subscribe
  [client instrument location-code scan-code]
  (let [req-id (-> scan-code
                   scanner/scan-code->ch-kw
                   scanner/ch-kw->req-id)
        subscription (scanner/scanner-subscription instrument location-code scan-code)]
    (.reqScannerSubscription client (int req-id) subscription nil)
    req-id))

(defn scanner-unsubscribe [client req-id]
  (.cancelScannerSubscription client req-id))

(defn ewrapper-impl
  [{ch :publisher
    :keys [account-summary-ch
           account-updates-ch
           portfolio-updates-ch]}]
  (proxy [EWrapperImpl] []
    (nextValidId [^Integer order-id]
      (reset! valid-order-id order-id))

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
                                 scanner/req-id->ch-kw
                                 scanner/ch-kw->ch)]
          (async/put! scanner-ch val)
          (log/warnf "No scanner channel for req-id %s" req-id))))

    (scannerDataEnd [req-id]
      (if-let [scanner-ch (->> req-id
                               scanner/req-id->ch-kw
                               scanner/ch-kw->ch)]
        (async/put! scanner-ch ::scanner/data-end)
        (log/warnf "No scanner channel for req-id %s" req-id)))

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
        (async/put! ch val)))

    (accountSummary [req-id account tag value currency]
      (let [val {:req-id req-id
                 :account account
                 :tag tag
                 :value (acct-summary/parse-tag-value tag value)
                 :currency currency}]
        (async/put! account-summary-ch val)))

    (updateAccountValue [^String key
                         ^String value
                         ^String currency
                         ^String account-name]
      (let [val {:account-name account-name
                 :key key
                 :value value
                 :currency currency}]
        (async/put! account-updates-ch val)))

    (updatePortfolio [^Contract contract
                      ^Double position
                      ^Double market-price
                      ^Double market-value
                      ^Double average-cost
                      ^Double unrealized-pnl
                      ^Double realized-pnl
                      ^String account-name]
      (let [val {:contract (obj-convert/convert contract)
                 :position position
                 :market-price market-price
                 :market-value market-value
                 :average-cost average-cost
                 :unrealized-pnl unrealized-pnl
                 :realized-pnl realized-pnl
                 :account-name account-name}]
        (async/put! portfolio-updates-ch val)))

    (accountDownloadEnd [^String account]
      (async/put! account-updates-ch ::acct-updates/download-end))))

(defn default-exception-handler
  [^Exception e]
  (println "Exception:" (.getMessage e)))

(def default-chs-map
  {:publisher (-> 1000 async/sliding-buffer async/chan)
   :account-summary-ch acct-summary/account-summary-ch
   :account-updates-ch acct-updates/account-updates-ch
   :portfolio-updates-ch portfolio/portfolio-updates-ch})

(defn ewrapper
  ([]
   (ewrapper "tws"))
  ([host]
   (ewrapper host 4002))
  ([host port]
   (ewrapper host port 1))
  ([host port client-id]
   (ewrapper host port client-id {:publisher (-> 1000 async/sliding-buffer async/chan)}))
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
