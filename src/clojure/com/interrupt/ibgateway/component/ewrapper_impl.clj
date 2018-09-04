(ns com.interrupt.ibgateway.component.ewrapper-impl
  (:require [clojure.core.async :as async
             :refer [chan >! <! merge go go-loop pub sub unsub-all sliding-buffer]])
  (:import [java.util Calendar]
           [java.text SimpleDateFormat]
           [com.interrupt.ibgateway EWrapperImpl]
           [com.ib.client
            EWrapper EClient EClientSocket EReader EReaderSignal
            Contract ContractDetails ScannerSubscription]

           [com.ib.client Types$BarSize Types$DurationUnit Types$WhatToShow]))

(defn scanner-subscripion [instrument location-code scan-code]
  (doto (ScannerSubscription.)
    (.instrument instrument)
    (.locationCode location-code)
    (.scanCode scan-code)))

(defn scanner-subscribe [req-id client instrument location-code scan-code]

  (let [subscription (scanner-subscripion instrument location-code scan-code)]
    (.reqScannerSubscription client req-id subscription nil)
    req-id))

(defn scanner-unsubscribe [req-id client]
  (.cancelScannerSubscription client req-id))

(defn historical-subscribe [req-id client]

  (def cal (Calendar/getInstance))
  (.add cal Calendar/DAY_OF_MONTH -16)

  (def form (SimpleDateFormat. "yyyyMMdd HH:mm:ss"))
  (def formatted (.format form (.getTime cal)))

  (let [contract (doto (Contract.)
                   (.symbol "TSLA")
                   (.secType "STK")
                   (.currency "USD")
                   (.exchange "SMART")
                   #_(.primaryExch "ISLAND"))]

    #_(.reqHistoricalData client req-id contract formatted "2 W" "1 min" "TRADES" 1 1 nil)
    (.reqHistoricalData client req-id contract formatted "4 M" "1 min" "TRADES" 1 1 nil))

  req-id)

(defn- create-contract [instrm]
  (doto (Contract.)
    (.symbol instrm)
    (.secType "STK")
    (.exchange "SMART")
    (.currency "USD")))

(defn live-subscribe

  ([req-id client instrm]
   (live-subscribe client req-id instrm "" false))

  ([req-id client instrm genericTicklist snapshot]
   (let [contract (create-contract instrm)]
     (.reqMktData client (.intValue req-id) contract genericTicklist snapshot nil))))

(defn ewrapper-impl [publisher]

  (proxy [EWrapperImpl] []

    (tickPrice [^Integer tickerId ^Integer field ^Double price ^Integer canAutoExecute]

      ;; (println "New - Tick Price. Ticker Id:" tickerId " Field: " field " Price: " price " CanAutoExecute: " canAutoExecute)
      (let [ch-value {:topic :tick-price
                      :ticker-id tickerId
                      :field field
                      :price price
                      :can-auto-execute canAutoExecute}]
        (async/put! publisher ch-value)))

    (tickSize [^Integer tickerId ^Integer field ^Integer size]

      ;; (println "New - Tick Size. Ticker Id:"  tickerId  " Field: "  field  " Size: "  size)
      (let [ch-value {:topic :tick-size
                      :ticker-id tickerId
                      :field field
                      :size size}]
        (async/put! publisher ch-value)))

    (tickString [^Integer tickerId ^Integer tickType ^String value]
      ;; (println "New - Tick String. Ticker Id:"  tickerId  " Type: "  tickType  " Value: "  value)
      (let [ch-value {:topic :tick-string
                      :ticker-id tickerId
                      :tick-type tickType
                      :value value}]
        (async/put! publisher ch-value)))

    (tickGeneric [^Integer tickerId ^Integer tickType ^Double value]
      (println "New - Tick Generic. Ticker Id:"  tickerId  ", Field: " tickType  ", Value: "  value))

    (scannerParameters [^String xml]

      (println "scannerParameters CALLED")
      (def scannerParameters xml))

    (scannerData [reqId rank ^ContractDetails contractDetails ^String distance ^String benchmark
                  ^String projection ^String legsStr]

      (let [sym (.. contractDetails contract symbol)
            sec-type (.. contractDetails contract secType)
            curr (.. contractDetails contract currency)

            ch-value {:topic :scanner-data
                      :req-id reqId
                      :message-end false
                      :symbol sym
                      :sec-type sec-type
                      :rank rank}]

        #_(println (str "ScannerData CALLED / reqId: " reqId " - Rank: " rank ", Symbol: " sym
                        ", SecType: " sec-type ", Currency: " curr ", Distance: " distance
                        ", Benchmark: " benchmark ", Projection: " projection ", Legs String: " legsStr))

        (async/put! publisher ch-value)))

    (scannerDataEnd [reqId]

      (let [ch-value {:topic :scanner-data
                      :req-id reqId
                      :message-end true}]

        #_(println (str "ScannerDataEnd CALLED / reqId: " reqId))

        (async/put! publisher ch-value)))


    ;; public void historicalData(int reqId, String date, double open,
    ;;                             double high, double low, double close, int volume, int count,
    ;;                             double WAP, boolean hasGaps) {
    ;;                                                           System.out.println("HistoricalData. "+reqId+" - Date: "+date+", Open: "+open+", High: "+high+", Low: "+low+", Close: "+close+", Volume: "+volume+", Count: "+count+", WAP: "+WAP+", HasGaps: "+hasGaps);
    ;;                                                           }

    (historicalData [reqId ^String date open high low close
                     volume count WAP  hasGaps]

      (let [ch-value {:topic :historical-data
                      :req-id reqId
                      :date date
                      :open open
                      :high high
                      :low low
                      :close close
                      :volume volume
                      :count count
                      :wap WAP
                      :has-gaps hasGaps}]

        (println "2. HistoricalData." reqId
                 " - Date: " date
                 ", Open: " open
                 ", High: " high
                 ", Low: " low
                 ", Close: " close
                 ", Volume: " volume
                 ", Count: " count
                 ", WAP: " WAP
                 ", HasGaps: " hasGaps)

        (async/put! publisher ch-value)))))

(defn ewrapper

  ([]
   (ewrapper 1))

  ([no-of-topics]

   ;; Setup client, wrapper, process messages
   (let [buffer-size (* no-of-topics (+ 1 50))
         publisher (chan (sliding-buffer 100))
         ;; broadcast-channel (pub publisher #(:topic %))
         ewrapperImpl (ewrapper-impl publisher)
         client (.getClient ewrapperImpl)
         signal (.getSignal ewrapperImpl)

         result (.eConnect client "tws" 4002 1)

         ereader (EReader. client signal)]

     ;; (if (.isConnected esocket)
     ;;   (.eDisconnect esocket))

     (.start ereader)
     (future
       (while (.isConnected client)
         (.waitForSignal signal)
         (try
           (.processMsgs ereader)
           (catch Exception e
             (println (str "Exception: " (.getMessage e)))))))

     {:client client
      :publisher publisher
      :ewrapper-impl ewrapperImpl})))
