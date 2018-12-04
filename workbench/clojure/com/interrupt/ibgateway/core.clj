(ns com.interrupt.ibgateway.core
  (:require [clojure.core.async
             :refer [chan >! >!! <! <!! alts! close! merge go go-loop pub sub unsub-all
                     sliding-buffer mult tap pipeline] :as async]
            [clojure.tools.logging :refer [debug info warn error]]
            [com.interrupt.ibgateway.component.account :refer [account-name]]
            [com.interrupt.ibgateway.component.account.portfolio :as portfolio]
            [com.interrupt.ibgateway.component.account.summary :as acct-summary]
            [com.interrupt.ibgateway.component.account.updates :as acct-updates]
            [com.interrupt.edgar.scanner :as scanner]
            [com.interrupt.ibgateway.cloud.storage]
            [com.interrupt.ibgateway.component.ewrapper :as ew]
            [com.interrupt.ibgateway.component.ewrapper-impl :as ei]
            [com.interrupt.ibgateway.component.figwheel.figwheel]
            [com.interrupt.ibgateway.component.processing-pipeline :as pp]
            [com.interrupt.ibgateway.component.execution-engine :as ee]
            [com.interrupt.ibgateway.component.switchboard :as sw]
            [com.interrupt.ibgateway.component.switchboard.store]
            [com.interrupt.ibgateway.component.account.contract :as contract]
            [com.interrupt.ibgateway.component.vase]
            [com.interrupt.ibgateway.component.vase.service
             :refer [send-message-to-all!]]
            [mount.core :refer [defstate] :as mount])
  (:import [com.ib.client EClient ExecutionFilter Order]))


(comment
  (def account "DU16007")

  (mount/stop #'ew/ewrapper)
  (mount/start #'ew/ewrapper)

  (def client (:client ew/ewrapper))

  (acct-updates/start client account)

  (acct-updates/stop client account)

  @acct-updates/accounts-info

  @portfolio/portfolio-info)


(comment

  (do (def client (:client ew/ewrapper))
      (def account "DU16007")
      (def valid-order-id 1))

  (acct-summary/start client 1)
  (acct-summary/stop client 1)


  (mount/stop #'com.interrupt.ibgateway.component.ewrapper/ewrapper
              #'com.interrupt.ibgateway.component.account.summary/summary
              #'com.interrupt.ibgateway.component.account.updates/updates)

  (mount/start #'com.interrupt.ibgateway.component.ewrapper/ewrapper
               #'com.interrupt.ibgateway.component.account.summary/summary
               #'com.interrupt.ibgateway.component.account.updates/updates)


  @acct-summary/account-summary
  acct-summary/account-summary-ch

  (.cancelOrder client valid-order-id)

  (.placeOrder client
               valid-order-id
               (contract/create "AAPL")
               (doto (Order.)
                 (.action "BUY")
                 (.orderType "MKT")
                 (.totalQuantity 10)
                 (.account account)))

  (.placeOrder client
               valid-order-id
               (contract/create "AAPL")
               (doto (Order.)
                 (.action "SELL")
                 (.orderType "MKT")
                 (.totalQuantity 10)
                 (.account account)))

  (.reqIds client -1)

  (.placeOrder client
               valid-order-id
               (contract/create "AMZN")
               (doto (Order.)
                 (.action "BUY")
                 (.orderType "MKT")
                 (.totalQuantity 20)
                 (.account account)))

  (.placeOrder client
               valid-order-id
               (contract/create "AAPL")
               (doto (Order.)
                 (.action "BUY")
                 (.orderType "MKT")
                 (.totalQuantity 50)
                 (.account account))))


(comment

  (-main nil)

  (mount/start #'com.interrupt.ibgateway.component.ewrapper/ewrapper)
  (mount/stop #'com.interrupt.ibgateway.component.ewrapper/ewrapper)

  (mount/start)
  (mount/stop)
  (mount/find-all-states))


(comment

  (require '[com.interrupt.ibgateway.cloud.storage :refer [put-file get-file]])

  (mount/stop #'com.interrupt.ibgateway.cloud.storage/s3)
  (mount/start #'com.interrupt.ibgateway.cloud.storage/s3)

  (def bucket-name "edgarly")
  (def file-name "live-recordings/2018-08-27-TSLA.edn")

  (put-file s3 bucket-name file-name)
  (get-file s3 bucket-name file-name))


(comment ;; processing-pipeline workbench


  ;; 1. START
  (mount/start #'com.interrupt.ibgateway.component.ewrapper/ewrapper)
  (mount/stop #'com.interrupt.ibgateway.component.ewrapper/ewrapper)

  (do
    (def control-channel (chan))
    (def instrument "TSLA")
    (def concurrency 1)
    (def ticker-id 0)

    ;; "live-recordings/2018-08-20-TSLA.edn"
    ;; "live-recordings/2018-08-27-TSLA.edn"
    (def fname "live-recordings/2018-08-20-TSLA.edn")
    (def source-ch (-> ew/ewrapper :ewrapper :publisher))
    (def joined-channel-map (pp/setup-publisher-channel source-ch instrument concurrency ticker-id)))


  ;; 2. Point your browser to http://localhost:8080


  ;; 3. Capture output channels and send to browser
  (let [{jch :joined-channel} joined-channel-map]

    (go-loop [c 0 r (<! jch)]
      (if-not r
        r
        (let [sr (update-in r [:sma-list] dissoc :population)]
          (info "count: " c " / sr: " r)
          ;; (send-message-to-all! sr)
          (recur (inc c) (<! jch))))))


  ;; 4. Start streaming
  (sw/kickoff-stream-workbench (-> ew/ewrapper :ewrapper :wrapper)
                               control-channel
                               fname)


  ;; STOP
  (do
    (sw/stop-stream-workbench control-channel)
    (pp/teardown-publisher-channel joined-channel-map))

  (mount/stop #'com.interrupt.ibgateway.component.ewrapper/ewrapper))


(comment ;; execution-engine workbench


  ;; START
  (mount/start #'com.interrupt.ibgateway.component.ewrapper/ewrapper)


  (do
    (def control-channel (chan))
    (def instrument "TSLA")
    (def concurrency 1)
    (def ticker-id 0)

    ;; "live-recordings/2018-08-20-TSLA.edn"
    ;; "live-recordings/2018-08-27-TSLA.edn"
    (def fname "live-recordings/2018-08-20-TSLA.edn")
    (def source-ch (-> ew/ewrapper :ewrapper :publisher))
    (def joined-channel-map (pp/setup-publisher-channel source-ch instrument concurrency ticker-id))
    (sw/kickoff-stream-workbench (-> ew/ewrapper :ewrapper :wrapper) control-channel fname))


  (def joined-channel-tapped
    (ee/setup-execution-engine joined-channel-map ew/ewrapper instrument account-name))


  ;; STOP
  (do
    (sw/stop-stream-workbench control-channel)
    (pp/teardown-publisher-channel joined-channel-map)
    (ee/teardown-execution-engine joined-channel-tapped))

  (mount/stop #'com.interrupt.ibgateway.component.ewrapper/ewrapper))


(comment ;; stream live workbench

  (mount/start
               #'com.interrupt.ibgateway.component.ewrapper/ewrapper
               #'com.interrupt.ibgateway.component.processing-pipeline/processing-pipeline
               #'com.interrupt.ibgateway.component.execution-engine/execution-engine
               #'com.interrupt.ibgateway.core/state)

  (let [ticker-id 1003]
    (def live-subscription (sw/start-stream-live ew/ewrapper ew/default-chs-map "AAPL" ticker-id)))

  (sw/stop-stream-live live-subscription)

  (mount/stop
              #'com.interrupt.ibgateway.component.ewrapper/ewrapper
              #'com.interrupt.ibgateway.component.processing-pipeline/processing-pipeline
              #'com.interrupt.ibgateway.component.execution-engine/execution-engine
              #'com.interrupt.ibgateway.core/state))


(comment  ;; A scanner workbench

  (mount/stop #'com.interrupt.ibgateway.component.ewrapper/ewrapper)
  (mount/start #'com.interrupt.ibgateway.component.ewrapper/ewrapper)

  (do

    (def client (:client ew/ewrapper))

    ;; Subscribe
    (scanner/start client)

    ;; Unsubscribe
    (scanner/stop client)

    (when-let [leaderboard (scanner/scanner-decide)]
      (doseq [[i m] (map-indexed vector leaderboard)]
        (println i ":" m)))))


(comment  ;; Scanner + Processing Pipeline + Execution Engine


  ;; SCAN SET CHANGES
  (require '[clojure.set :as s])

  (do
    (def one #{:a :b :c})
    (def two #{:b :c :d})
    (def three #{:c :d}))

  (defn ->removed [orig princ]
    (s/difference orig princ))

  (defn ->added [orig princ]
    (s/difference princ orig))


  ;; SCAN
  (mount/start #'com.interrupt.ibgateway.component.ewrapper/ewrapper
               #'com.interrupt.ibgateway.component.account/account)

  (do

    (def client (-> ew/ewrapper :ewrapper :client))

    ;; Subscribe
    (scanner/start client)

    ;; Unsubscribe
    ;; (scanner/stop client)

    (when-let [leaderboard (scanner/scanner-decide)]
      (doseq [[i m] (map-indexed vector leaderboard)]
        (println i ":" m))))


  ;; START
  (do
    (def instrument "AMZN")
    (def concurrency 1)
    (def ticker-id 1003)
    (def source-ch (-> ew/ewrapper :ewrapper :publisher))
    (def joined-channel-map (pp/setup-publisher-channel source-ch instrument concurrency ticker-id))
    (def joined-channel-tapped (ee/setup-execution-engine joined-channel-map ew/ewrapper instrument account-name))
    (def live-subscription (sw/start-stream-live ew/ewrapper instrument ticker-id)))


  ;; STOP
  (do
    (sw/stop-stream-live live-subscription)
    (pp/teardown-publisher-channel joined-channel-map)
    (ee/teardown-execution-engine joined-channel-tapped))

  (mount/stop #'com.interrupt.ibgateway.component.ewrapper/ewrapper
              #'com.interrupt.ibgateway.component.account/account))


(comment ;; from com.interrupt.ibgateway.component.processing-pipeline

  (require
    '[clojure.core.async :refer [chan sliding-buffer <! go-loop onto-chan] :as async]
    '[manifold.stream :as stream]
    '[prpr.stream.cross :as stream.cross])

  (let [tick-list->macd-ch (chan (sliding-buffer 50))
        sma-list->macd-ch (chan (sliding-buffer 50))

        tick-list->MACD (->> tick-list->macd-ch
                             stream/->source
                             (stream.cross/event-source->sorted-stream :id))
        sma-list->MACD (->> sma-list->macd-ch
                            stream/->source
                            (stream.cross/event-source->sorted-stream :id))

        result (stream.cross/set-streams-union {:default-key-fn :id
                                                :skey-streams {:tick-list tick-list->MACD
                                                               :sma-list sma-list->MACD}})

        connector-ch (chan (sliding-buffer 100))]

    ;; OK
    #_(go-loop [r (<! tick-list->macd-ch)]
        (info r)
        (when r
          (recur (<! tick-list->macd-ch))))

    ;; OK
    #_(stream/map (fn [r]
                    (prn ">> " r)
                    r)
                  sma-list->MACD)

    (stream/connect @result connector-ch)
    (go-loop [{:keys [tick-list sma-list] :as r} (<! connector-ch)]
      (info "record: " r)
      (if-not r
        r
        (recur (<! connector-ch))))

    (onto-chan tick-list->macd-ch [{:id :a :val 1} {:id :b :val 2} {:id :c :val 3}])
    (onto-chan sma-list->macd-ch [{:id :a :val 2} {:id :b :val 3} {:id :c :val 4}])))


(comment ;; com.interrupt.ibgateway.component.switchboard.brokerage

  (next-reqid [])
  (scannerid-availableid-pairs []))


(comment  ;; DB workbench - from com.interrupt.ibgateway.component.switchboard.store

  ;; Examples
  {:switchboard/scanner :stock-scanner
   :switchboard/state :on}

  {:switchboard/scanner :stock-price
   :switchboard/state :on
   :switchboard/instrument "TSLA"}

  {:switchboard/scanner :stock-historical
   :switchboard/state :on
   :switchboard/instrument "TSLA"}


  (require '[datomic.api :as d])
  (def uri "datomic:mem://ibgateway")

  (def rdelete (d/delete-database uri))
  (def rcreate (d/create-database uri))
  (def conn (d/connect uri))
  (def db (d/db conn))


  ;; SCHEMA
  (def schema
    [;; used for >  STOCK SCANNER | STOCK | STOCK HISTORICAL
     {:db/ident :switchboard/scanner   ;; :stock-scanner/state
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/one
      :db/doc "A simple switch on whether or not, to scan the stock-market'"}

     {:db/ident :switchboard/state
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/one
      :db/doc "A simple switch on whether or not, to scan the stock-market'"}

     {:db/ident :switchboard/request-id
      :db/valueType :db.type/long
      :db/cardinality :db.cardinality/one
      :db/unique :db.unique/identity
      :db/doc "Records the request id made to TWS"}

     {:db/ident :switchboard/instrument
      :db/valueType :db.type/string
      :db/cardinality :db.cardinality/one
      :db/doc "The stock symbol being tracked"}


     {:db/ident :switchboard/on}
     {:db/ident :switchboard/off}
     {:db/ident :switchboard/stock-scanner}
     {:db/ident :switchboard/stock-price}
     {:db/ident :switchboard/stock-historical}])

  (def schema-transact-result (d/transact conn schema))


  ;; TURN ON
  (def scanner-on [{:switchboard/scanner :switchboard/stock-scanner
                    :switchboard/state :switchboard/on}])

  (def scanner-off [{:switchboard/scanner :switchboard/stock-scanner
                     :switchboard/state :switchboard/off}])

  (def stock-scanner-on [{:switchboard/scanner :switchboard/stock-price
                          :switchboard/state :switchboard/on
                          :switchboard/instrument "TSLA"}])

  (def stock-historical-on [{:switchboard/scanner :switchboard/stock-historical
                             :switchboard/state :switchboard/on
                             :switchboard/instrument "TSLA"}])

  (def all-on [{:switchboard/scanner :switchboard/stock-scanner
                :switchboard/state :switchboard/on}

               {:switchboard/scanner :switchboard/stock-price
                :switchboard/state :switchboard/on
                :switchboard/instrument "TSLA"}

               {:switchboard/scanner :switchboard/stock-historical
                :switchboard/state :switchboard/on
                :switchboard/instrument "TSLA"}])

  #_(d/transact conn scanner-on)
  #_(d/transact conn scanner-off)
  #_(d/transact conn stock-scanner-on)
  #_(d/transact conn stock-historical-on)

  (def result (d/transact conn all-on))

  (pprint (->> (for [color [:red :blue]
                     size [1 2]
                     type ["a" "b"]]
                 {:inv/color color
                  :inv/size size
                  :inv/type type})
               (map-indexed
                 (fn [idx map]
                   (assoc map :inv/sku (str "SKU-" idx))))
               vec))


  ;; QUERY
  (pprint (d/q '[:find (pull ?e [:switchboard/instrument
                                 {:switchboard/scanner [*]}
                                 {:switchboard/state [*]}])
                 :where
                 [?e :switchboard/state]]
               (d/db conn)))

  (pprint (d/q '[:find (pull ?e [{:switchboard/state [*]}
                                 :switchboard/instrument])
                 :where
                 [?e :switchboard/scanner ?s]
                 [?s :db/ident :switchboard/stock-scanner]]
               (d/db conn)))


  ;; TURN ON - IBM
  (def ibm-on [{:switchboard/scanner :switchboard/stock-price
                :switchboard/state :switchboard/on
                :switchboard/instrument "IBM"}])

  (d/transact conn ibm-on)


  ;; QUERY
  (pprint (d/q '[:find (pull ?e [{:switchboard/state [*]}
                                 :switchboard/instrument])
                 :where
                 [?e :switchboard/state]
                 [?e :switchboard/instrument "IBM"]
                 (d/db conn)]))


  ;;ADD an entity
  (def intl-on [{:switchboard/scanner {:db/id [:db/ident :switchboard/stock-price]}
                 :switchboard/state {:db/id [:db/ident :switchboard/on]}
                 :switchboard/instrument "INTL"}])

  (d/transact conn intl-on)

  ;; UPDATE an entity
  (def ibm-off [[:db/add
                 [:switchboard/instrument "IBM"]
                 :switchboard/state :stock-price-state/off]])

  #_(d/transact conn ibm-off)

  ;; QUERY - does a stock price scan exist?
  #_(pprint (d/q '[:find (pull ?e [{:switchboard/state [*]}
                                   :switchboard/instrument])
                   :where
                   [?e :switchboard/state ?s]
                   [?s :db/ident :stock-price-state/on]]
                 (d/db conn)))

  #_(pprint (d/q '[:find (pull ?e [{:switchboard/state [*]}
                                   :switchboard/instrument])
                   :where
                   [?e :switchboard/state ?s]
                   [?s :db/ident :stock-price-state/off]]
                 (d/db conn)))



  ;; QUERY - does a historical fetch exist?
  #_(pprint (d/q '[:find (pull ?e [{:switchboard/state [*]}
                                   :switchboard/instrument])
                   :where
                   [?e :switchboard/state ?s]
                   [?s :db/ident :stock-historical-state/on]]
                 (d/db conn)))

  #_(pprint (d/q '[:find (pull ?e [{:switchboard/state [*]}
                                   :switchboard/instrument])
                   :where
                   [?e :switchboard/state ?s]
                   [?s :db/ident :stock-historical-state/off]]
                 (d/db conn))))


(comment  ;; from com.interrupt.ibgateway.component.ewrapper-impl

  ;; (def one (ewrapper 11))
  ;; (def client (:client one))
  ;; (def publisher (:publisher one))


  ;; ====
  ;; Requesting historical data

  ;; (def cal (Calendar/getInstance))
  ;; (.add cal Calendar/MONTH -6)

  ;; (def form (SimpleDateFormat. "yyyyMMdd HH:mm:ss"))
  ;; (def formatted (.format form (.getTime cal)))
  ;;
  ;; (let [contract (doto (Contract.)
  ;;                  (.symbol "TSLA")
  ;;                  (.secType "STK")
  ;;                  (.currency "USD")
  ;;                  (.exchange "SMART"))]
  ;;
  ;;   (.reqHistoricalData client 4002 contract formatted "2 W" "1 sec" "MIDPOINT" 1 1 nil))
  ;;
  ;; (.reqHistoricalData client 4001 (ContractSamples/EurGbpFx) formatted "2 W" "1 sec" "MIDPOINT" 1 1 nil)
  ;; (.reqHistoricalData client 4002 (ContractSamples/USStockWithPrimaryExch) formatted "1 M" "1 day" "MIDPOINT" 1 1 nil)
  ;;
  ;; ;; private static void marketScanners(EClientSocket client) throws InterruptedException
  ;;
  ;; ;; Requesting all available parameters which can be used to build a scanner request
  ;; (.reqScannerParameters client)
  ;;
  ;; ;; Triggering a scanner subscription
  ;; (spit "scannerParameters.1.xml" scannerParameters)
  ;;
  ;; (.reqScannerSubscription client 7001 (ScannerSubscriptionSamples/HighOptVolumePCRatioUSIndexes) nil)
  ;;
  ;; ;; Canceling the scanner subscription
  ;; (.cancelScannerSubscription client 7001)


  ;; ====
  ;; Core.async workbench
  ;; (def subscriber-one (chan))
  ;; (def subscriber-two (chan))
  ;;
  ;; (sub broadcast-channel SCANNERDATA subscriber-one)
  ;; (sub broadcast-channel SCANNERDATA subscriber-two)
  ;;
  ;; (def high-volatility-one (atom {}))
  ;; (def high-volatility-two (atom {}))
  ;;
  ;;
  ;; ;; ====
  ;; (defn take-and-save [channel topic req-id]
  ;;   (go-loop []
  ;;     (let [msg (<! channel)]
  ;;
  ;;       (if (and (= (:topic msg) topic)
  ;;                (= (:req-id msg) req-id))
  ;;         (swap! high-volatility-one assoc (:symbol msg) msg)
  ;;         (swap! high-volatility-two assoc (:symbol msg) msg))
  ;;       (recur))))
  ;;
  ;; (take-and-save subscriber-one SCANNERDATA 1)
  ;; (take-and-save subscriber-two SCANNERDATA 2)


  ;; (go (>! publisher {:topic SCANNERDATA
  ;;                    :req-id 1
  ;;                    :message-end false
  ;;                    :symbol :foo
  ;;                    :sec-type "stock"}))
  ;;
  ;; (go (>! publisher {:topic SCANNERDATA
  ;;                    :req-id 2
  ;;                    :message-end false
  ;;                    :symbol :bar
  ;;                    :sec-type "stock"}))
  ;;
  ;;
  ;; (unsub-all broadcast-channel SCANNERDATA)
  ;;
  ;; (def scanSub1 (ScannerSubscription.))
  ;; (.instrument scanSub1 "STK")
  ;; (.locationCode scanSub1 "STK.US.MAJOR")
  ;; (.scanCode scanSub1 "HIGH_OPT_IMP_VOLAT")
  ;; (.reqScannerSubscription client 7001 scanSub1 nil)
  ;; (.cancelScannerSubscription client 7001)
  ;;
  ;; (def scanSub2 (ScannerSubscription.))
  ;; (.instrument scanSub2 "STK")
  ;; (.locationCode scanSub2 "STK.US.MAJOR")
  ;; (.scanCode scanSub2 "HIGH_OPT_IMP_VOLAT_OVER_HIST")
  ;; (.reqScannerSubscription client 7002 scanSub2 nil)
  ;; (.cancelScannerSubscription client 7002)


  ;; Sanity 1:  {:topic :scanner-data, :req-id 1, :message-end false, :symbol AGFS, :sec-type #object[com.ib.client.Types$SecType 0x1ff4ec3a STK], :rank 0}
  ;; Sanity 1:  {:topic :scanner-data, :req-id 1, :message-end false, :symbol BAA, :sec-type #object[com.ib.client.Types$SecType 0x1ff4ec3a STK], :rank 1}
  ;; Sanity 1:  {:topic :scanner-data, :req-id 1, :message-end false, :symbol EBR B, :sec-type #object[com.ib.client.Types$SecType 0x1ff4ec3a STK], :rank 2}

  ;; (require '[system.repl])

  (def client (:client ew/ewrapper))
  (def publisher (:publisher ew/ewrapper))

  (do
    ;; ===
    ;; Stock scanners
    (def default-instrument "STK")
    (def default-location "STK.US.MAJOR")

    ;; Volatility
    (ei/scanner-subscribe client 1 default-instrument default-location "HIGH_OPT_IMP_VOLAT")
    (ei/scanner-subscribe client 2 default-instrument default-location "HIGH_OPT_IMP_VOLAT_OVER_HIST")

    ;; Volume
    (ei/scanner-subscribe client 3 default-instrument default-location "HOT_BY_VOLUME")
    (ei/scanner-subscribe client 4 default-instrument default-location "TOP_VOLUME_RATE")
    (ei/scanner-subscribe client 5 default-instrument default-location "HOT_BY_OPT_VOLUME")
    (ei/scanner-subscribe client 6 default-instrument default-location "OPT_VOLUME_MOST_ACTIVE")
    ;; (scanner-subscribe client 7 default-instrument default-location "COMBO_MOST_ACTIVE")

    ;; Price Change
    (ei/scanner-subscribe client 8 default-instrument default-location "MOST_ACTIVE_USD")
    (ei/scanner-subscribe client 9 default-instrument default-location "HOT_BY_PRICE")
    (ei/scanner-subscribe client 10 default-instrument default-location "TOP_PRICE_RANGE")
    (ei/scanner-subscribe client 11 default-instrument default-location "HOT_BY_PRICE_RANGE"))

  (do

    ;; Subscribe
    (def publication
      (pub publisher #(:req-id %)))

    (def subscriber-one (chan))
    (def subscriber-two (chan))
    (def subscriber-three (chan))
    (def subscriber-four (chan))
    (def subscriber-five (chan))
    (def subscriber-six (chan))
    ;; (def subscriber-seven (chan))
    (def subscriber-eight (chan))
    (def subscriber-nine (chan))
    (def subscriber-ten (chan))
    (def subscriber-eleven (chan))

    (sub publication 1 subscriber-one)
    (sub publication 2 subscriber-two)
    (sub publication 3 subscriber-three)
    (sub publication 4 subscriber-four)
    (sub publication 5 subscriber-five)
    (sub publication 6 subscriber-six)
    ;; (sub publication 7 subscriber-seven)
    (sub publication 8 subscriber-eight)
    (sub publication 9 subscriber-nine)
    (sub publication 10 subscriber-ten)
    (sub publication 11 subscriber-eleven))

  (defn consume-subscriber [dest-atom subscriber]
    (go-loop [r1 nil]

      (let [{:keys [req-id symbol rank] :as val} (select-keys r1 [:req-id :symbol :rank])]
        (if (and r1 rank)
          (swap! dest-atom assoc rank val)))

      (recur (<! subscriber))))

  (do
    ;; Buckets
    (def volat-one (atom {}))
    (def volat-two (atom {}))
    (def volat-three (atom {}))
    (def volat-four (atom {}))
    (def volat-five (atom {}))
    (def volat-six (atom {}))
    ;; (def volat-seven (atom {}))
    (def volat-eight (atom {}))
    (def volat-nine (atom {}))
    (def volat-ten (atom {}))
    (def volat-eleven (atom {}))

    (consume-subscriber volat-one subscriber-one)
    (consume-subscriber volat-two subscriber-two)
    (consume-subscriber volat-three subscriber-three)
    (consume-subscriber volat-four subscriber-four)
    (consume-subscriber volat-five subscriber-five)
    (consume-subscriber volat-six subscriber-six)
    ;; (consume-subscriber volat-seven subscriber-seven)
    (consume-subscriber volat-eight subscriber-eight)
    (consume-subscriber volat-nine subscriber-nine)
    (consume-subscriber volat-ten subscriber-ten)
    (consume-subscriber volat-eleven subscriber-eleven))

  (do

    ;; Intersection
    (require '[clojure.set :as s])
    (def sone (set (map :symbol (vals @volat-one))))
    (def stwo (set (map :symbol (vals @volat-two))))
    (def s-volatility (s/intersection sone stwo))  ;; OK

    (def sthree (set (map :symbol (vals @volat-three))))
    (def sfour (set (map :symbol (vals @volat-four))))
    (def sfive (set (map :symbol (vals @volat-five))))
    (def ssix (set (map :symbol (vals @volat-six))))
    ;; (def sseven (set (map :symbol (vals @volat-seven))))
    (def s-volume (s/intersection sthree sfour #_sfive #_ssix #_sseven))

    (def seight (set (map :symbol (vals @volat-eight))))
    (def snine (set (map :symbol (vals @volat-nine))))
    (def sten (set (map :symbol (vals @volat-ten))))
    (def seleven (set (map :symbol (vals @volat-eleven))))
    (def s-price-change (s/intersection #_seight #_snine sten seleven)))

  (s/intersection sone stwo snine)
  (s/intersection sone stwo seleven)


  (require '[clojure.math.combinatorics :as combo])
  (def intersection-subsets
    (filter (fn [e] (> (count e) 1))
            (combo/subsets [{:name "one" :val sone}
                            {:name "two" :val stwo}
                            {:name "three" :val sthree}
                            {:name "four" :val sfour}
                            {:name "five" :val sfive}
                            {:name "six" :val ssix}
                            ;; {:name "seven" :val sseven}
                            {:name "eight" :val seight}
                            {:name "nine" :val snine}
                            {:name "ten" :val sten}
                            {:name "eleven" :val seleven}])))

  (def sorted-intersections
    (sort-by #(count (:intersection %))
             (map (fn [e]
                    (let [result (apply s/intersection (map :val e))
                          names (map :name e)]
                      {:names names :intersection result}))
                  intersection-subsets)))

  (def or-volatility-volume-price-change
    (filter (fn [e]
              (and (> (count (:intersection e)) 1)
                   (some #{"one" "two"} (:names e))
                   (some #{"three" "four" "five" "six" "seven"} (:names e))
                   (some #{"eight" "nine" "ten" "eleven"} (:names e))))
            sorted-intersections))

  ;; Unsubscribe
  (scanner-unsubscribe 1 client)
  (scanner-unsubscribe 2 client)
  (scanner-unsubscribe 3 client)
  (scanner-unsubscribe 4 client)
  (scanner-unsubscribe 5 client)
  (scanner-unsubscribe 6 client)
  (scanner-unsubscribe 7 client)
  (scanner-unsubscribe 8 client)
  (scanner-unsubscribe 9 client)
  (scanner-unsubscribe 10 client)
  (scanner-unsubscribe 11 client))


(comment  ;; Parsing tick-string / xform input -> tick-list - from com.interrupt.edgar.ib.handler.live

  (def input-source (read-string (slurp "live.4.edn")))
  (def options {:stock-match {:symbol "TSLA"
                              :ticker-id-filter 0}})

  ;; 1
  (->> input-source
       (filter rtvolume-time-and-sales?)

       (take 100)
       pprint)


  ;; 2
  (def e1 {:topic :tick-string
           :ticker-id 0
           :tick-type 48
           :value ";0;1524063373067;33853;295.43541145;true"})

  (def e2 {:topic :tick-string
           :ticker-id 0
           :tick-type 48
           :value "295.31;8;1524063373569;33861;295.43538182;true"})

  (parse-tick-string e1)
  (parse-tick-string e2)


  ;; 3
  (->> input-source
       (filter rtvolume-time-and-sales?)
       (map parse-tick-string)

       (take 10)
       pprint)

  ;; 3.1
  (-> (filter rtvolume-time-and-sales?)
      (sequence (take 50 input-source))
      pprint)


  (def xf (comp (filter rtvolume-time-and-sales?)
                (map parse-tick-string)
                (x/partition moving-average-window moving-average-increment (x/into []))
                (map (partial simple-moving-average-format options))))


  (def result (-> xf
                  (sequence (take 500 input-source)))))


(comment  ;; Abstracting out xform -> handler-xform

  (def input-source (read-string (slurp "live.4.edn")))
  (def options {:stock-match {:symbol "TSLA"
                              :ticker-id-filter 0}})

  (def result (-> handler-xform
                  (sequence (take 1000 input-source)))))


(comment  ;; Generating sma-list, ema-list, bollinger-band

  (require '[com.interrupt.edgar.core.analysis.lagging :as al])

  (def input-source (read-string (slurp "live.4.edn")))
  (def options {:stock-match {:symbol "TSLA"
                              :ticker-id-filter 0}})

  (def sma-list (-> (comp handler-xform
                          (x/partition moving-average-window moving-average-increment (x/into []))
                          (map (partial al/simple-moving-average {})))
                    (sequence (take 1000 input-source))))

  (def ema-list (al/exponential-moving-average {} moving-average-window sma-list))

  (def bollinger-band (al/bollinger-band moving-average-window sma-list)))


(comment  ;; Playing with promisespromises

  (require '[clojure.core.async :refer [to-chan <!!]]
           '[manifold.stream :as s]
           '[prpr.stream.cross :as prpr :refer [event-source->sorted-stream]])


  (def c1 (to-chan [{:id 2 :value "a"} {:id 3} {:id 4}]))
  (def c2 (to-chan [{:id 1} {:id 2 :value "b"} {:id 3}]))
  (def c3 (to-chan [{:id 0} {:id 1} {:id 2 :value "c"}]))

  (def cs1 (s/->source c1))
  (def cs2 (s/->source c2))
  (def cs3 (s/->source c3))

  (def kss {:cs1 cs1 :cs2 cs2 :cs3 cs3})
  (def os (prpr/full-outer-join-streams
            {:default-key-fn :id
             :skey-streams kss}))
  (def ovs @(s/reduce conj [] os))


  #_(def ss1 (prpr/event-source->sorted-stream :id cs1))
  #_(def ss2 (prpr/event-source->sorted-stream :id cs2))
  #_(def ss3 (prpr/event-source->sorted-stream :id cs3))

  #_(def result (prpr/set-streams-union {:default-key-fn :id
                                         :skey-streams {:ss1 ss1
                                                        :ss2 ss2
                                                        :ss3 ss3}}))

  #_(s/take! result)

  ;; ClassCastException manifold.deferred.SuccessDeferred cannot be cast to manifold.stream.core.IEventSource  com.interrupt.edgar.ib.handler.live/eval47535 (form-init767747358322570513.clj:266)

  ;;cross-streams
  ;;sort-merge-streams
  ;;set-streams-union (which uses cross-streams)


  ;;clojure.core.async [mult
  ;;                    merge mix
  ;;                    pub sub
  ;;                    map]
  ;;net.cgrand/xforms [group-by for]


  (let [s0 (s/->source [{:foo 1 :bar 10} {:foo 3 :bar 30} {:foo 4 :bar 40}])
        s1 (s/->source [{:foo 1 :baz 100} {:foo 2 :baz 200} {:foo 3 :baz 300}])
        kss {:0 s0 :1 s1}

        os @(prpr/full-outer-join-streams
              {:default-key-fn :foo
               :skey-streams kss})
        ovs @(s/reduce conj [] os)
        ]

    (println "Foo: " os)
    (println "Bar: " ovs)

    #_(is (= [{:0 {:foo 1 :bar 10} :1 {:foo 1 :baz 100}}
              {:1 {:foo 2 :baz 200}}
              {:0 {:foo 3 :bar 30} :1 {:foo 3 :baz 300}}
              {:0 {:foo 4 :bar 40}}]
             ovs))))


(comment  ;; SUCCESS with promisespromises

  (require '[clojure.core.async :refer [to-chan chan go-loop <!]]
           '[manifold.stream :as stream]
           '[prpr.stream.cross :as stream.cross]
           'prpr.stream
           '[prpr.promise :refer [ddo]]
           '[xn.transducers :as xn])

  (let [c1 (to-chan [{:id 2 :a "a"} {:id 3} {:id 4}])
        c2 (to-chan [{:id 1} {:id 2 :b "b"} {:id 3}])
        c3 (to-chan [{:id 0} {:id 1} {:id 2 :c "c"}])
        cs1 (stream/->source c1)
        cs2 (stream/->source c2)
        cs3 (stream/->source c3)
        ss1 (stream.cross/event-source->sorted-stream :id cs1)
        ss2 (stream.cross/event-source->sorted-stream :id cs2)
        ss3 (stream.cross/event-source->sorted-stream :id cs3)]


    #_(let [result (stream.cross/set-streams-union {:default-key-fn :id
                                                    :skey-streams {:ss1 ss1
                                                                   :ss2 ss2
                                                                   :ss3 ss3}})]
        @(stream/take! @result))

    (ddo [u-s (stream.cross/set-streams-union {:default-key-fn :id
                                               :skey-streams {:ss1 ss1
                                                              :ss2 ss2
                                                              :ss3 ss3}})]
         (->> u-s
              (stream/map
                (fn [r]
                  (prn r)
                  r))
              (prpr.stream/count-all-throw
                "count results")))

    #_(stream.cross/set-streams-union {:default-key-fn :id
                                       :skey-streams {:ss1 ss1
                                                      :ss2 ss2
                                                      :ss3 ss3}})

    #_(let [oc (chan 1 (map vals))
            result (stream.cross/set-streams-union {:default-key-fn :id
                                                    :skey-streams {:ss1 ss1
                                                                   :ss2 ss2
                                                                   :ss3 ss3}})]
        (stream/connect @result oc)
        (go-loop [r (<! oc)]
          (println "record: " r)
          (println "record merged: " (apply merge r))
          (println "")
          (if-not r
            r
            (recur (<! oc)))))))


#_(comment  ;; SUCCESS with promisespromises


    #_(let [c1 (to-chan [{:id 2 :a "a"} {:id 3} {:id 4}])
            c2 (to-chan [{:id 1} {:id 2 :b "b"} {:id 3}])
            c3 (to-chan [{:id 0} {:id 1} {:id 2 :c "c"}])
            cs1 (stream/->source c1)
            cs2 (stream/->source c2)
            cs3 (stream/->source c3)
            ss1 (stream.cross/event-source->sorted-stream :id cs1)
            ss2 (stream.cross/event-source->sorted-stream :id cs2)
            ss3 (stream.cross/event-source->sorted-stream :id cs3)]


        (let [oc (chan 1 (map vals))
              result (stream.cross/set-streams-union {:default-key-fn :id
                                                      :skey-streams {:ss1 ss1
                                                                     :ss2 ss2
                                                                     :ss3 ss3}})]
          (stream/connect @result oc)
          (go-loop [r (<! oc)]
            (info "record: " r)
            (info "record merged: " (apply merge r))
            (info "")
            (if-not r
              r
              (recur (<! oc)))))))


#_(comment

    (defn join-averages
      ([state input] (join-averages state #{:tick-list :sma-list :ema-list} input))
      ([state completion-set input]

       (let [inputF (last input)
             uuid (:uuid inputF)
             entry (cond
                     (:last-trade-price-exponential inputF) {:ema-list input}
                     (:last-trade-price-average inputF) {:sma-list input}
                     (:last-trade-price inputF) {:tick-list input})]

         (if-let [current (get @state uuid)]

           (let [_ (swap! state update-in [uuid] merge entry)
                 c' (get @state uuid)]

             (if (has-all-lists? c' completion-set)
               (update-state-and-complete state uuid c')
               input))

           (do (swap! state merge {uuid entry})
               input)))))

    (let [ema-list [{:uuid "1" :last-trade-price-exponential 10}
                    {:uuid "2" :last-trade-price-exponential 11}
                    {:uuid "3" :last-trade-price-exponential 12}]

          sma-list [{:uuid "1" :last-trade-price-average 10.1}
                    {:uuid "2" :last-trade-price-average 10.2}
                    {:uuid "3" :last-trade-price-average 10.3}]

          tick-list [{:uuid "1" :last-trade-price 11.1}
                     {:uuid "2" :last-trade-price 11.2}
                     {:uuid "3" :last-trade-price 11.3}]

          ec (chan (sliding-buffer 100))
          sc (chan (sliding-buffer 100))
          tc (chan (sliding-buffer 100))

          _ (onto-chan ec ema-list)
          _ (onto-chan sc sma-list)
          _ (onto-chan tc tick-list)

          merged-ch (async/merge [tc sc ec])
          #_output-ch #_(chan (sliding-buffer 100) (join-averages (fn [ac e]
                                                                    (log/info "ac" ac)
                                                                    (log/info "e" e)
                                                                    (concat ac (list e)))))

          output-ch (chan (sliding-buffer 100) (filter :joined))]

      #_(async/pipe merged-ch output-ch)
      #_(go-loop [r (<! output-ch)]
          (when-not (nil? r)
            (log/info "record" r)
            (recur (<! output-ch))))

      (pipeline 1 output-ch (map (partial join-averages (atom {}))) merged-ch)
      (go-loop [r (<! output-ch)]
        (when-not (nil? r)
          (log/info "record" r)
          (recur (<! output-ch))))))


(comment  ;; Track strategies / Manage orders

  (require '[com.interrupt.edgar.ib.market :as market]
           '[com.interrupt.edgar.core.analysis.lagging :as alagging]
           '[com.interrupt.edgar.core.signal.common :as common]
           '[com.interrupt.edgar.core.signal.lagging :as slagging]
           '[com.interrupt.edgar.core.signal.leading :as sleading]
           '[com.interrupt.edgar.core.signal.confirming :as sconfirming]
           '[com.interrupt.edgar.core.strategy.strategy :as strategy]
           '[com.interrupt.edgar.core.strategy.target :as target])


  (def ^:dynamic *tracking-data* (ref []))
  (def ^:dynamic *orderid-index* (ref 100))

  (defn track-strategies
    "Follows new strategy recommendations coming in"
    [tick-list strategy-list]

    ;; iterate through list of strategies
    (reduce (fn [rA eA]

              #_(println (str "... 1 > eA[" eA "] > some test if/else[" (some #(= % (:tickerId eA)) (map :tickerId @*tracking-data*)) "]"))
              ;; does tickerId of current entry = any tickerIds in existing list?
              (if (some #(= % (:tickerId eA))
                        (map :tickerId @*tracking-data*))


                ;; for tracking symbols, each new tick -> calculate:
                ;;     $ gain/loss
                ;;     % gain/loss
                (dosync (alter *tracking-data* (fn [inp]

                                                 (let [result-filter (filter #(= (-> % second :tickerId) (:tickerId eA))
                                                                             (map-indexed (fn [idx itm] [idx itm]) inp))]

                                                   #_(println (str "... 2 > value[" (ffirst (seq result-filter))
                                                                   "] / result-filter[" (seq result-filter)
                                                                   "] / input[" (type inp) "][" inp "]"))

                                                   ;; update-in-place, the existing *tracking-data*
                                                   ;; i. find index of relevent entry
                                                   (into [] (update-in inp
                                                                       [(ffirst (seq result-filter))]
                                                                       (fn [i1]

                                                                         #_(println (str "... 3 > update-in inp[" i1 "]"))
                                                                         (let [price-diff (- (:last-trade-price (first tick-list)) (:orig-trade-price i1))
                                                                               merge-result (merge i1 {:last-trade-price (:last-trade-price (first tick-list))
                                                                                                       :last-trade-time (:last-trade-time eA)
                                                                                                       :change-pct (/ price-diff (:orig-trade-price i1))
                                                                                                       :change-prc price-diff})]

                                                                           #_(println (str "... 4 > result[" merge-result "]"))
                                                                           merge-result))))))))

                ;; otherwise store them in a hacked-session
                (dosync (alter *tracking-data* concat (list {:uuid (:uuid eA)
                                                             :symbol (:symbol tick-list)
                                                             :tickerId (:tickerId eA)
                                                             :orig-trade-price (:last-trade-price eA)
                                                             :orig-trade-time (:last-trade-time eA)
                                                             :strategies (:strategies eA)
                                                             :source-entry eA})))))
            []
            strategy-list))

  (defn watch-strategies
    "Tracks and instruments existing strategies in play"
    [tick-list]

    #_(println (str "... 1 > WATCH > watch-strategies > test[" (some #(= % (:tickerId (first tick-list)))
                                                                     (map :tickerId @*tracking-data*)) "]"))

    ;; check if latest tick matches a stock being watched
    (if (some #(= % (:tickerId (first tick-list)))
              (map :tickerId @*tracking-data*))

      (dosync (alter *tracking-data* (fn [inp]

                                       (let [result-filter (filter #(= (-> % second :tickerId) (:tickerId (first tick-list)))
                                                                   (map-indexed (fn [idx itm] [idx itm]) inp))]

                                         #_(println (str "... 2 > WATCH > result-filter[" (into [] result-filter) "] / integer key[" (first (map first result-filter)) "] / inp[" (into [] inp) "]"))

                                         ;; update-in-place, the existing *tracking-data*
                                         ;; i. find index of relevent entry
                                         (into [] (update-in (into [] inp)
                                                             [(first (map first (into [] result-filter)))]
                                                             (fn [i1]

                                                               #_(println (str "... 3 > WATCH > update-in > inp[" i1 "]"))
                                                               (let [

                                                                     ;; find peaks-valleys
                                                                     peaks-valleys (common/find-peaks-valleys nil tick-list)
                                                                     peaks (:peak (group-by :signal peaks-valleys))

                                                                     stoploss-threshold? (target/stoploss-threshhold? (:orig-trade-price i1) (:last-trade-price (first tick-list)))
                                                                     reached-target? (target/target-threshhold? (:orig-trade-price i1) (:last-trade-price (first tick-list)))


                                                                     ;; ensure we're not below stop-loss
                                                                     ;; are we: at 'target'

                                                                     ;; OR

                                                                     ;; are we: abouve last 2 peaks - hold
                                                                     ;; are we: below first peak, but abouve second peak - hold
                                                                     ;; are we: below previous 2 peaks - sell

                                                                     action (if stoploss-threshold?

                                                                              {:action :down :why :stoploss-threshold}

                                                                              (if (every? #(>= (:last-trade-price (first tick-list))
                                                                                               (:last-trade-price %))
                                                                                          (take 2 peaks))

                                                                                {:action :up :why :abouve-last-2-peaks}

                                                                                (if (and (>= (:last-trade-price (first tick-list))
                                                                                             (:last-trade-price (nth tick-list 2)))
                                                                                         (<= (:last-trade-price (first tick-list))
                                                                                             (:last-trade-price (second tick-list))))

                                                                                  {:action :up :why :abouve-second-below-first-peak}

                                                                                  {:action :down :why :below-first-2-peaks})))


                                                                     price-diff (- (:last-trade-price (first tick-list)) (:orig-trade-price i1))
                                                                     merge-result (merge i1 {:last-trade-price (:last-trade-price (first tick-list))
                                                                                             :last-trade-time (:last-trade-time (first tick-list))
                                                                                             :change-pct (/ price-diff (:orig-trade-price i1))
                                                                                             :change-prc price-diff
                                                                                             :action action})]

                                                                 #_(println (str "... 4 > WATCH > result[" merge-result "]"))
                                                                 merge-result))))))))))

  (defn trim-strategies [tick-list]

    (println (str "... trim-strategies / SELL test[" (some #(= :down (-> % :action :action)) @*tracking-data*)
                  "] / ACTION[" (seq (filter #(= :down (-> % :action :action)) @*tracking-data*))
                  "] / WHY[" (seq (filter #(= :down (-> % :action)) @*tracking-data*)) "]"))
    (dosync (alter *tracking-data*
                   (fn [inp]
                     (into [] (remove #(= :down (-> % :action :action))
                                      inp))))))

  (defn manage-orders [strategy-list result-map tick-list-N]

    ;; track any STRATEGIES
    (let [strategy-list-trimmed (remove nil? (map first strategy-list))]

      (if (-> strategy-list-trimmed empty? not)

        (track-strategies tick-list-N strategy-list-trimmed)))


    ;; watch any STRATEGIES in play
    (if (not (empty? @*tracking-data*))
      (watch-strategies tick-list-N))


    ;; ORDER based on tracking data
    ;; TODO - update access to ewrapper client
    (let [client (:interactive-brokers-client edgar/*interactive-brokers-workbench*)
          tick (first @*tracking-data*)]

      (if (some #(= :up (-> % :action :action))
                (filter #(= (:tickerId %) (-> tick-list-N first :tickerId)) @*tracking-data*))

        ;; only buy that which we are not already :long
        (if-not (= :long
                   (-> (filter #(= (:tickerId %) (-> tick-list-N first :tickerId)) @*tracking-data*)
                       first
                       :position))
          (do

            ;; ... TODO: make sure we don't double-buy yet
            ;; ... TODO: track orderId for sale
            ;; ... TODO: stock-symbol has to be tied to the tickerId
            (println "==> BUY now")
            #_(dosync (alter *tracking-data* (fn [inp]

                                               (let [result-filter (filter #(= (-> % second :tickerId) (:tickerId (first tick-list-N)))
                                                                           (map-indexed (fn [idx itm] [idx itm]) inp))]

                                                 ;; i. find index of relevent entry
                                                 (update-in (into [] inp)
                                                            [(first (map first (into [] result-filter)))]
                                                            (fn [i1]

                                                              #_(println (str "... 3 > BUY > update-in > inp[" i1 "]"))
                                                              (let [merge-result (merge i1 {:order-id *orderid-index*
                                                                                            :position :long
                                                                                            :position-amount 100
                                                                                            :position-price (:last-trade-price (first tick-list-N))})]

                                                                #_(println (str "... 4 > BUY > result[" merge-result "]"))
                                                                (market/buy-stock client @*orderid-index* (:symbol result-map) 100 (:last-trade-price tick))

                                                                merge-result)))))))
            (dosync (alter *orderid-index* inc))))

        (if (some #(= :down (-> % :action :action))
                  (filter #(= (:tickerId %) (-> tick-list-N first :tickerId)) @*tracking-data*))

          (if (= :long
                 (-> (filter #(= (:tickerId %) (-> tick-list-N first :tickerId)) @*tracking-data*)
                     first
                     :position))
            (do

              (println "==> SELL now / test[" (filter #(= (:tickerId %) (-> tick-list-N first :tickerId)) @*tracking-data*) "] ")
              #_(dosync (alter *tracking-data* (fn [inp]

                                                 (let [result-filter (filter #(= (-> % second :tickerId) (:tickerId (first tick-list-N)))
                                                                             (map-indexed (fn [idx itm] [idx itm]) inp))]

                                                   ;; i. find index of relevent entry
                                                   (update-in (into [] inp)
                                                              [(first (map first (into [] result-filter)))]
                                                              (fn [i1]

                                                                (println (str "... 3 > SELL > update-in > inp[" i1 "]"))
                                                                (let [merge-result (merge i1 {:position :short
                                                                                              :position-amount 100
                                                                                              :position-price (:last-trade-price (first tick-list-N))})]

                                                                  (println (str "... 4 > SELL > result[" merge-result "]"))
                                                                  (market/sell-stock client @(:order-id i1) (:symbol result-map) 100 (:last-trade-price tick))
                                                                  merge-result)))))))

              ;; remove tracked stock if sell
              (if (not (empty? @*tracking-data*))
                (trim-strategies tick-list-N)))))))))
