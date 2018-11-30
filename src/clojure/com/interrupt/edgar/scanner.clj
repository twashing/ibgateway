(ns com.interrupt.edgar.scanner
  (:require [clojure.core.async :as async :refer [<! go-loop]]
            [clojure.string :as str]
            [com.interrupt.edgar.subscription :as sub]
            [inflections.core :as inflections])
  (:import com.ib.client.ScannerSubscription
           com.ib.controller.ScanCode))

(def scan-codes (->> ScanCode .getEnumConstants (map str) sort))

(def relevant-scan-codes
  [;; "HIGH_OPT_IMP_VOLAT"
   ;; "HIGH_OPT_IMP_VOLAT_OVER_HIST"
   ;; "HOT_BY_VOLUME"
   ;; "TOP_VOLUME_RATE"
   ;; "HOT_BY_OPT_VOLUME"
   ;; "OPT_VOLUME_MOST_ACTIVE"
   "MOST_ACTIVE_USD"
   "HOT_BY_PRICE"
   "TOP_PRICE_RANGE"
   "HOT_BY_PRICE_RANGE"])

(defn scan-code->ch-kw
  [code]
  (keyword (format "%s-ch" (str/lower-case (inflections/dasherize code)))))

(defn ch-kw->scan-code
  [ch-kw]
  (let [s (name ch-kw)]
    (-> s
        (subs 0 (- (count s) 3))
        inflections/underscore
        str/upper-case)))

(def req-id-base 1000)

(def req-id->ch-kw
  (->> (map-indexed (fn [i code]
                      [(+ req-id-base i) (scan-code->ch-kw code)])
                    scan-codes)
       (into {})))

(defn ch-kw->req-id
  [ch-kw]
  (-> {}
      (into (for [[k v] req-id->ch-kw] [v k]))
      (get ch-kw)))

(def ch-kw->ch
  "Map of scan code channel kws to channels."
  (into {} (for [code relevant-scan-codes]
             [(scan-code->ch-kw code)
              (->> (remove #(= [::data-end] %))
                   (comp (partition-by #(= ::data-end %)))
                   (async/chan (async/sliding-buffer 100)))])))

(def ch-kw->atom
  "Map of scan code channel kws to atoms."
  (into {} (for [code relevant-scan-codes]
             [(scan-code->ch-kw code) (atom nil)])))

(defn scanner-subscription
  [instrument location-code scan-code]
  (doto (ScannerSubscription.)
    (.instrument instrument)
    (.locationCode location-code)
    (.scanCode scan-code)
    (.numberOfRows 10)))

(defrecord ScannerSubSubscription [client
                                   ^ScannerSubscription subscription
                                   options]
  sub/Subscription
  (subscribe [_]
    (let [ch-kw (-> subscription .scanCode scan-code->ch-kw)
          ticker-id (ch-kw->req-id ch-kw)
          ch (get ch-kw->ch ch-kw)]
      (.reqScannerSubscription client (int ticker-id) subscription options)
      ch))
  (unsubscribe [_]
    (let [ticker-id (-> subscription
                        .scanCode
                        scan-code->ch-kw
                        ch-kw->req-id)]
      (.cancelScannerSubscription client (int ticker-id)))))

(defn start
  [client]
  (doseq [[k ch] ch-kw->ch]
    (let [subscription (->> k
                            ch-kw->scan-code
                            (scanner-subscription "STK" "STK.US.MAJOR"))]
      (sub/subscribe (->ScannerSubSubscription client subscription nil)))
    (go-loop []
      (when-let [v (<! ch)]
        (reset! (get ch-kw->atom k) v)
        (recur)))))

(defn stop
  [client]
  (doseq [[k _] ch-kw->ch]
    (.cancelScannerSubscription client (ch-kw->req-id k))))

(defn most-frequent
  [n colls]
  (->> colls
       (apply concat)
       (map #(select-keys % [:symbol :sec-type]))
       frequencies
       (sort-by second >)
       (take n)
       (map first)))

(defn scanner-decide
  ([]
   (scanner-decide (vals ch-kw->atom)))
  ([atoms]
   (scanner-decide atoms (partial most-frequent 20)))
  ([atoms decider]
   (decider (map deref atoms))))
