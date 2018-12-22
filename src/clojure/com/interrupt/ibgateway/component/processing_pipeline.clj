(ns com.interrupt.ibgateway.component.processing-pipeline
  (:require [clojure.core.async :refer [chan to-chan sliding-buffer close! <! >!
                                        go-loop mult tap mix pipeline onto-chan] :as async]
            [clojure.set :refer [subset?]]
            [mount.core :refer [defstate] :as mount]
            [clojure.tools.logging :refer [debug info warn error] :as log]
            [net.cgrand.xforms :as x]
            [clojure.string :as cs]
            [cljs-uuid.core :as uuid]
            [com.interrupt.ibgateway.component.ewrapper :as ew]
            [com.interrupt.ibgateway.component.common :refer [bind-channels->mult]]
            [com.interrupt.edgar.ib.market :as mkt]
            [com.interrupt.edgar.core.analysis.lagging :as alag]
            [com.interrupt.edgar.core.analysis.leading :as alead]
            [com.interrupt.edgar.core.analysis.confirming :as aconf]
            [com.interrupt.edgar.core.signal.lagging :as slag]
            [com.interrupt.edgar.core.signal.leading :as slead]
            [com.interrupt.edgar.core.signal.confirming :as sconf]
            [prpr.stream.cross :as stream.cross]
            [manifold.stream :as stream]))


(def moving-average-window 20)
(def moving-average-increment 1)
(def rt-volume-time-and-sales-type 48)
(def tick-string-type :tick-string)

(defn rtvolume-time-and-sales? [event]
  (and (= rt-volume-time-and-sales-type (:tick-type event))
       (= tick-string-type (:topic event))))

(defn parse-tick-string
  "Input format:

   {:type tickString, :tickerId 0, :tickType 48, :value 412.14;1;1367429375742;1196;410.39618025;true}

   Value format:

   :value       ;0;1522337866199;67085;253.23364232;true
   :value 255.59;1;1522337865948;67077;253.23335428;true"
  [event]

  (let [tvalues (cs/split (:value event) #";")
        tkeys [:last-trade-price :last-trade-size :last-trade-time :total-volume :vwap :single-trade-flag]]

    (as-> (zipmap tkeys tvalues) rm
      (merge rm {:ticker-id (:ticker-id event)
                 :type (:topic event)
                 :uuid (str (uuid/make-random))})
      (assoc rm
             :last-trade-price (if (not (empty? (:last-trade-price rm)))
                                 (read-string (:last-trade-price rm)) 0)
             :last-trade-time (Long/parseLong (:last-trade-time rm))
             :last-trade-size (read-string (:last-trade-size rm))
             :total-volume (read-string (:total-volume rm))
             :vwap (read-string (:vwap rm))))))

(defn empty-last-trade-price? [event]
  (-> event :last-trade-price (<= 0)))


;; track bid / ask with stream (https://interactivebrokers.github.io/tws-api/tick_types.html)
;;   These are the only tickString types I see coming in
;;   48 45 33 32

;; Bid Size	0	IBApi.EWrapper.tickSize
;; Bid Price	1	IBApi.EWrapper.tickPrice
;; Ask Price	2	IBApi.EWrapper.tickPrice
;; Ask Size	3	IBApi.EWrapper.tickSize
;; Last Price	4	IBApi.EWrapper.tickPrice
;; Last Size	5	IBApi.EWrapper.tickSize
;; High	6 IBApi.EWrapper.tickPrice
;; Low	7	IBApi.EWrapper.tickPrice
;; Volume	8	IBApi.EWrapper.tickSize
;; Close Price	9, IBApi.EWrapper.tickPrice

(def handler-xform
    (comp (filter rtvolume-time-and-sales?)
       (map parse-tick-string)

       ;; NOTE For now, ignore empty lots
       (remove empty-last-trade-price?)))

(defn pipeline-stochastic-oscillator [n stochastic-oscillator-ch tick-list->stochastic-osc-ch]
  (let [stochastic-tick-window 14
        trigger-window 3
        trigger-line 3]
    (pipeline n stochastic-oscillator-ch (map (partial alead/stochastic-oscillator stochastic-tick-window trigger-window trigger-line)) tick-list->stochastic-osc-ch)))

(defn pipeline-relative-strength-index [n relative-strength-ch tick-list->relative-strength-ch]
  (let [relative-strength 14]
    (pipeline n relative-strength-ch (map (partial aconf/relative-strength-index relative-strength)) tick-list->relative-strength-ch)))

(defn has-all-lists?
  ([averages-map] (has-all-lists? averages-map #{:tick-list :sma-list :ema-list}))
  ([averages-map completion-set]
   (subset? completion-set (->> averages-map keys (into #{})))))

(defn update-state-and-complete [state uuid c]
  (swap! state dissoc uuid)
  (assoc c :joined true))

(defn pipeline-analysis-lagging [concurrency options
                                 sma-list-ch tick-list->sma-ch
                                 ema-list-ch sma-list->ema-ch
                                 bollinger-band-ch sma-list->bollinger-band-ch]

  (pipeline concurrency sma-list-ch (map (partial alag/simple-moving-average options)) tick-list->sma-ch)
  (pipeline concurrency ema-list-ch (map (partial alag/exponential-moving-average options moving-average-window)) sma-list->ema-ch)
  (pipeline concurrency bollinger-band-ch (map (partial alag/bollinger-band moving-average-window)) sma-list->bollinger-band-ch))

(defn pipeline-analysis-leading [concurrency options moving-average-window
                                 macd-ch sma-list->macd-ch
                                 stochastic-oscillator-ch tick-list->stochastic-osc-ch]

  (pipeline concurrency macd-ch (map (partial alead/macd options moving-average-window)) sma-list->macd-ch)
  (pipeline-stochastic-oscillator concurrency stochastic-oscillator-ch tick-list->stochastic-osc-ch))

(defn pipeline-analysis-confirming [concurrency on-balance-volume-ch tick-list->obv-ch
                                    relative-strength-ch tick-list->relative-strength-ch]

  (pipeline concurrency on-balance-volume-ch (map aconf/on-balance-volume) tick-list->obv-ch)
  (pipeline-relative-strength-index concurrency relative-strength-ch tick-list->relative-strength-ch))

(defn pipeline-signals-moving-average [concurrency connector-ch signal-moving-averages-ch]

  (let [moving-average-signal-window 2
        m (mult connector-ch)

        string-check (fn [k i]
                       (if (string? (k i))
                         (assoc i k (read-string (k i)))
                         i))

        strings->numbers (fn [item]
                           (->> item
                                (string-check :last-trade-price-exponential)
                                (string-check :last-trade-price-average)))

        remove-population-xf (map #(update-in % [:sma-list] dissoc :population))
        partition-xf (x/partition moving-average-signal-window moving-average-increment (x/into []))
        join-xf (map (fn [e]
                       (let [ks [:sma-list :ema-list]]
                         (->> e
                              (map #(merge ((first ks) %) ((second ks) %)))
                              (map strings->numbers)))))

        remove-population-ch (chan (sliding-buffer 100) remove-population-xf)
        partitioned-ch (chan (sliding-buffer 100) partition-xf)
        partitioned-joined-ch (chan (sliding-buffer 100) join-xf)

        mult-moving-averages (mult partitioned-joined-ch)
        tap->moving-averages (chan (sliding-buffer 100))]

    (tap m remove-population-ch)
    (tap mult-moving-averages tap->moving-averages)

    (pipeline concurrency partitioned-ch (map identity) remove-population-ch)
    (pipeline concurrency partitioned-joined-ch (map identity) partitioned-ch)
    (pipeline concurrency signal-moving-averages-ch (map slag/moving-averages) tap->moving-averages)))

(defn pipeline-signals-bollinger-band [concurrency connector-ch signal-bollinger-band-ch]

  (let [bollinger-band-signal-window 24
        bollinger-band-increment 1

        partition-xf (x/partition bollinger-band-signal-window bollinger-band-increment (x/into []))
        matches-window-size? #(= bollinger-band-signal-window
                                 (count %))
        bollinger-band-exists-xf (filter #(->> (filter :bollinger-band %)
                                               matches-window-size?))
        join-xf (map (fn [e]
                       (let [ks [:tick-list :bollinger-band]]
                         (->> e
                              ((juxt #(map (first ks) %) #(map (second ks) %)))
                              (zipmap ks)))))

        partitioned-ch (chan (sliding-buffer 100) partition-xf)
        bollinger-band-exists-ch (chan (sliding-buffer 100) bollinger-band-exists-xf)
        partitioned-joined-ch (chan (sliding-buffer 100) join-xf)]

    (pipeline concurrency partitioned-ch (map identity) connector-ch)
    (pipeline concurrency bollinger-band-exists-ch (map identity) partitioned-ch)
    (pipeline concurrency partitioned-joined-ch (map identity) bollinger-band-exists-ch)
    (pipeline concurrency signal-bollinger-band-ch (map (partial slag/bollinger-band moving-average-window)) partitioned-joined-ch)))

(defn pipeline-signals-leading [concurrency moving-average-window
                                signal-macd-ch macd->macd-signal
                                signal-stochastic-oscillator-ch stochastic-oscillator->stochastic-oscillator-signal]

  (pipeline concurrency signal-macd-ch (map slead/macd) macd->macd-signal)

  (pipeline concurrency signal-stochastic-oscillator-ch (map slead/stochastic-oscillator)
            stochastic-oscillator->stochastic-oscillator-signal))

(defn channel-analytics []
  {:source-list-ch (chan (sliding-buffer 100))
   :tick-list-ch (chan (sliding-buffer 100) (x/partition moving-average-window moving-average-increment (x/into [])))
   :sma-list-ch (chan (sliding-buffer 100) (x/partition moving-average-window moving-average-increment (x/into [])))
   :ema-list-ch (chan (sliding-buffer 100))
   :bollinger-band-ch (chan (sliding-buffer 100))
   :macd-ch (chan (sliding-buffer 100))
   :stochastic-oscillator-ch (chan (sliding-buffer 100))
   :on-balance-volume-ch (chan (sliding-buffer 100))
   :relative-strength-ch (chan (sliding-buffer 100))})

(defn channel-analytics-mults []
  {:tick-list->sma-ch (chan (sliding-buffer 100))
   :tick-list->macd-ch (chan (sliding-buffer 100))

   :sma-list->ema-ch (chan (sliding-buffer 100))
   :sma-list->bollinger-band-ch (chan (sliding-buffer 100))
   :sma-list->macd-ch (chan (sliding-buffer 100))

   :tick-list->stochastic-osc-ch (chan (sliding-buffer 100))
   :tick-list->obv-ch (chan (sliding-buffer 100))
   :tick-list->relative-strength-ch (chan (sliding-buffer 100))})

(defn channel-join-mults []
  {:tick-list->JOIN (chan (sliding-buffer 100) (map last))
   :sma-list->JOIN (chan (sliding-buffer 100) (map last))
   :ema-list->JOIN (chan (sliding-buffer 100) (map last))
   :bollinger-band->JOIN (chan (sliding-buffer 100) (map last))
   :macd->JOIN (chan (sliding-buffer 100) (map last))
   :stochastic-oscillator->JOIN (chan (sliding-buffer 100) (map last))
   :on-balance-volume->JOIN (chan (sliding-buffer 100) (map last))
   :relative-strength->JOIN (chan (sliding-buffer 100) (map last))})

(defn channel-signal-moving-averages []
  {:tick-list->moving-averages-signal (chan (sliding-buffer 100))
   :sma-list->moving-averages-signal (chan (sliding-buffer 100))
   :ema-list->moving-averages-signal (chan (sliding-buffer 100))
   :merged-averages (chan (sliding-buffer 100) (x/partition moving-average-window moving-average-increment (x/into [])))
   :signal-merged-averages (chan (sliding-buffer 100))
   :signal-moving-averages-ch (chan (sliding-buffer 100))})

(defn channel-signal-bollinger-band []

  (let [tick-list->bollinger-band-signal (chan (sliding-buffer 100))
        sma-list->bollinger-band-signal (chan (sliding-buffer 100))]
    {:tick-list->bollinger-band-signal tick-list->bollinger-band-signal
     :sma-list->bollinger-band-signal sma-list->bollinger-band-signal
     :signal-bollinger-band (chan (sliding-buffer 100) (filter :joined))
     :signal-bollinger-band-ch (chan (sliding-buffer 100))}))

(defn signal-join-mults []
  {:tick-list->SIGNAL (chan (sliding-buffer 100) (map last))
   :sma-list->SIGNAL (chan (sliding-buffer 100) (map last))
   :ema-list->SIGNAL (chan (sliding-buffer 100) (map last))
   :bollinger-band->SIGNAL (chan (sliding-buffer 100) (map last))
   :macd->SIGNAL (chan (sliding-buffer 100) (map last))
   :stochastic-oscillator->SIGNAL (chan (sliding-buffer 100) (map last))
   :on-balance-volume->SIGNAL (chan (sliding-buffer 100) (map last))
   :relative-strength->SIGNAL (chan (sliding-buffer 100) (map last))

   :signal-moving-averages->SIGNAL (chan (sliding-buffer 100))
   :signal-bollinger-band->SIGNAL (chan (sliding-buffer 100))
   :signal-macd->SIGNAL (chan (sliding-buffer 100))
   :signal-stochastic-oscillator->SIGNAL (chan (sliding-buffer 100))
   :signal-on-balance-volume->SIGNAL (chan (sliding-buffer 100))})

(defn channel->stream [& channels]
  (map #(->> %
             stream/->source
             (stream.cross/event-source->sorted-stream :last-trade-time))
       channels))

(defn join-analytics->moving-averages [sma-list->JOIN ema-list->JOIN]

  (let [[sma-list->CROSS ema-list->CROSS] (channel->stream sma-list->JOIN ema-list->JOIN)
        moving-averages-connector-ch (chan (sliding-buffer 100))
        result (stream.cross/set-streams-union {:default-key-fn :last-trade-time
                                                :skey-streams {:sma-list sma-list->CROSS
                                                               :ema-list ema-list->CROSS}})]

    (stream/connect @result moving-averages-connector-ch)
    moving-averages-connector-ch))

(defn join-analytics->bollinger-band [tick-list->JOIN bollinger-band->JOIN]

  (let [[tick-list->CROSS bollinger-band->CROSS] (channel->stream tick-list->JOIN bollinger-band->JOIN)
        bollinger-band-connector-ch (chan (sliding-buffer 100))
        result (stream.cross/set-streams-union {:default-key-fn :last-trade-time
                                                :skey-streams {:tick-list tick-list->CROSS
                                                               :bollinger-band bollinger-band->CROSS}})]

    (stream/connect @result bollinger-band-connector-ch)
    bollinger-band-connector-ch))

(defn join-analytics [output-ch tick-list->SIGNAL sma-list->SIGNAL ema-list->SIGNAL bollinger-band->SIGNAL
                      macd->SIGNAL stochastic-oscillator->SIGNAL
                      on-balance-volume->SIGNAL relative-strength->SIGNAL

                      signal-moving-averages->SIGNAL
                      signal-bollinger-band->SIGNAL
                      signal-macd->SIGNAL
                      signal-stochastic-oscillator->SIGNAL
                      signal-on-balance-volume->SIGNAL]

  (let [[tick-list->CROSS sma-list->CROSS ema-list->CROSS bollinger-band->CROSS
         macd->CROSS stochastic-oscillator->CROSS
         on-balance-volume->CROSS relative-strength->CROSS

         signal-moving-averages->CROSS
         signal-bollinger-band->CROSS
         signal-macd->CROSS
         signal-stochastic-oscillator->CROSS
         signal-on-balance-volume->CROSS]

        (channel->stream tick-list->SIGNAL sma-list->SIGNAL ema-list->SIGNAL bollinger-band->SIGNAL
                         macd->SIGNAL stochastic-oscillator->SIGNAL
                         on-balance-volume->SIGNAL relative-strength->SIGNAL

                         signal-moving-averages->SIGNAL
                         signal-bollinger-band->SIGNAL
                         signal-macd->SIGNAL
                         signal-stochastic-oscillator->SIGNAL
                         signal-on-balance-volume->SIGNAL)

        result (stream.cross/set-streams-union {:default-key-fn :last-trade-time
                                                :skey-streams {:tick-list tick-list->CROSS

                                                               ;; lagging
                                                               :sma-list sma-list->CROSS
                                                               :ema-list ema-list->CROSS
                                                               :bollinger-band bollinger-band->CROSS

                                                               ;; leading
                                                               :macd macd->CROSS
                                                               :stochastic-oscillator stochastic-oscillator->CROSS

                                                               ;; confirming
                                                               :on-balance-volume on-balance-volume->CROSS
                                                               :relative-strength relative-strength->CROSS

                                                               ;; signals
                                                               :signal-moving-averages signal-moving-averages->CROSS
                                                               :signal-bollinger-band signal-bollinger-band->CROSS
                                                               :signal-macd signal-macd->CROSS
                                                               :signal-stochastic-oscillator signal-stochastic-oscillator->CROSS
                                                               :signal-on-balance-volume signal-on-balance-volume->CROSS
                                                               }})]

    (stream/connect @result output-ch)
    output-ch))

(defn setup-publisher-channel [source-ch output-ch stock-name concurrency ticker-id-filter]

  (let [options {:stock-match {:symbol stock-name :ticker-id-filter ticker-id-filter}}


        ;; Channels Analytics
        {:keys [source-list-ch tick-list-ch sma-list-ch ema-list-ch
                bollinger-band-ch macd-ch stochastic-oscillator-ch
                on-balance-volume-ch relative-strength-ch]}
        (channel-analytics)


        ;; Channels Analytics Mults
        {:keys [tick-list->sma-ch tick-list->macd-ch
                sma-list->ema-ch sma-list->bollinger-band-ch sma-list->macd-ch
                tick-list->stochastic-osc-ch tick-list->obv-ch tick-list->relative-strength-ch]}
        (channel-analytics-mults)


        ;; Channels Signal: Moving Averages
        {:keys [tick-list->moving-averages-signal sma-list->moving-averages-signal ema-list->moving-averages-signal
                merged-averages signal-merged-averages signal-moving-averages-ch]}
        (channel-signal-moving-averages)


        ;; Channel JOIN Mults
        {:keys [sma-list->JOIN ema-list->JOIN
                tick-list->JOIN bollinger-band->JOIN
                ;; macd->JOIN stochastic-oscillator->JOIN
                ;; on-balance-volume->JOIN relative-strength->JOIN
                ]}
        (channel-join-mults)


        ;; Signal Bollinger Band
        {:keys [tick-list->bollinger-band-signal sma-list->bollinger-band-signal
                signal-bollinger-band signal-bollinger-band-ch]}
        (channel-signal-bollinger-band)


        macd->macd-signal (chan (sliding-buffer 100))
        stochastic-oscillator->stochastic-oscillator-signal (chan (sliding-buffer 100))
        on-balance-volume->on-balance-volume-ch (chan (sliding-buffer 100))
        signal-macd-ch (chan (sliding-buffer 100))
        signal-stochastic-oscillator-ch (chan (sliding-buffer 100))
        signal-on-balance-volume-ch (chan (sliding-buffer 100))

        lagging-signals-moving-averages-ch (join-analytics->moving-averages sma-list->JOIN ema-list->JOIN)
        lagging-signals-bollinger-band-connector-ch (join-analytics->bollinger-band tick-list->JOIN bollinger-band->JOIN)


        ;; Signal JOIN Mults
        {:keys [sma-list->SIGNAL ema-list->SIGNAL
                tick-list->SIGNAL bollinger-band->SIGNAL
                macd->SIGNAL stochastic-oscillator->SIGNAL
                on-balance-volume->SIGNAL relative-strength->SIGNAL

                signal-moving-averages->SIGNAL
                signal-bollinger-band->SIGNAL
                signal-macd->SIGNAL
                signal-stochastic-oscillator->SIGNAL
                signal-on-balance-volume->SIGNAL]}
        (signal-join-mults)]

    (join-analytics output-ch tick-list->SIGNAL sma-list->SIGNAL ema-list->SIGNAL bollinger-band->SIGNAL
                    macd->SIGNAL stochastic-oscillator->SIGNAL
                    on-balance-volume->SIGNAL relative-strength->SIGNAL

                    signal-moving-averages->SIGNAL
                    signal-bollinger-band->SIGNAL
                    signal-macd->SIGNAL
                    signal-stochastic-oscillator->SIGNAL
                    signal-on-balance-volume->SIGNAL)

    (doseq [source+mults [[source-list-ch tick-list-ch]
                          [tick-list-ch tick-list->sma-ch tick-list->macd-ch
                           tick-list->stochastic-osc-ch tick-list->obv-ch
                           tick-list->relative-strength-ch tick-list->JOIN tick-list->SIGNAL]
                          [sma-list-ch sma-list->ema-ch sma-list->bollinger-band-ch
                           sma-list->macd-ch sma-list->JOIN sma-list->SIGNAL]
                          [ema-list-ch ema-list->moving-averages-signal
                           ema-list->JOIN ema-list->SIGNAL]
                          [bollinger-band-ch bollinger-band->JOIN bollinger-band->SIGNAL]
                          [macd-ch macd->macd-signal macd->SIGNAL]
                          [stochastic-oscillator-ch stochastic-oscillator->stochastic-oscillator-signal
                           stochastic-oscillator->SIGNAL]
                          [on-balance-volume-ch on-balance-volume->on-balance-volume-ch
                           on-balance-volume->SIGNAL]
                          [relative-strength-ch relative-strength->SIGNAL]

                          [signal-moving-averages-ch signal-moving-averages->SIGNAL]
                          [signal-bollinger-band-ch signal-bollinger-band->SIGNAL]
                          [signal-macd-ch signal-macd->SIGNAL]
                          [signal-stochastic-oscillator-ch signal-stochastic-oscillator->SIGNAL]
                          [signal-on-balance-volume-ch signal-on-balance-volume->SIGNAL]]]

      (apply bind-channels->mult source+mults))


    ;; TICK LIST
    (pipeline concurrency source-list-ch handler-xform source-ch)
    (pipeline concurrency tick-list-ch handler-xform source-list-ch)


    ;; ANALYSIS
    (pipeline-analysis-lagging concurrency options
                               sma-list-ch tick-list->sma-ch
                               ema-list-ch sma-list->ema-ch
                               bollinger-band-ch sma-list->bollinger-band-ch)

    (pipeline-analysis-leading concurrency options moving-average-window macd-ch sma-list->macd-ch
                               stochastic-oscillator-ch tick-list->stochastic-osc-ch)

    (pipeline-analysis-confirming concurrency on-balance-volume-ch tick-list->obv-ch
                                  relative-strength-ch tick-list->relative-strength-ch)


    ;; SIGNALS
    (pipeline-signals-moving-average concurrency lagging-signals-moving-averages-ch
                                     signal-moving-averages-ch)

    ;; TODO bollinger-band signals should be fleshed out more
    (pipeline-signals-bollinger-band concurrency lagging-signals-bollinger-band-connector-ch
                                     signal-bollinger-band-ch)

    (pipeline-signals-leading concurrency moving-average-window
                              signal-macd-ch macd->macd-signal
                              signal-stochastic-oscillator-ch stochastic-oscillator->stochastic-oscillator-signal)

    ;; TODO on balance volume signals are all down (not up)
    (pipeline concurrency signal-on-balance-volume-ch (map sconf/on-balance-volume)
              on-balance-volume->on-balance-volume-ch)


    #_(go-loop [c 0 r (<! lagging-signals-moving-averages-ch)]
      (info "count: " c " / lagging-signals-moving-averages INPUT " r)
      (when r
        (recur (inc c) (<! lagging-signals-moving-averages-ch))))

    #_(go-loop [c 0 r (<! signal-moving-averages-ch)]
      (info "count: " c " / MA signals: " r)
      (when r
        (recur (inc c) (<! signal-moving-averages-ch))))

    #_(go-loop [c 0 r (<! signal-bollinger-band-ch)]
      (info "count: " c " / BB signals: " r)
      (when r
        (recur (inc c) (<! signal-bollinger-band-ch))))

    #_(go-loop [c 0 r (<! signal-macd-ch)]
      (info "count: " c " / MACD signals: " r)
      (when r
        (recur (inc c) (<! signal-macd-ch))))

    #_(go-loop [c 0 r (<! signal-stochastic-oscillator-ch)]
      (info "count: " c " / SO signals: " r)
      (when r
        (recur (inc c) (<! signal-stochastic-oscillator-ch))))

    #_(go-loop [c 0 r (<! signal-on-balance-volume-ch)]
      (info "count: " c " / OBV signal: " r)
      (when r
        (recur (inc c) (<! signal-on-balance-volume-ch))))

    #_(go-loop [c 0 r (<! output-ch)]
        (info "count: " c " / r: " r)
      (when r
        (recur (inc c) (<! output-ch))))

    {:joined-channel output-ch}))

(defn teardown-publisher-channel [processing-pipeline]
  (close! processing-pipeline))
