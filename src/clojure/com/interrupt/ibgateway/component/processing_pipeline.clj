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

(def handler-xform
    (comp (filter rtvolume-time-and-sales?)
       (map parse-tick-string)

       ;; TODO For now, ignore empty lots
       (remove empty-last-trade-price?)))


(defn bind-channels->mult [source-list-ch & channels]
  (let [source-list->sink-mult (mult source-list-ch)]
    (doseq [c channels]
      (tap source-list->sink-mult c))))

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
          (recur (<! oc))))))

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

(defn join-averages
  ([state input] (join-averages state #{:tick-list :sma-list :ema-list} input))
  ([state completion-set input]

   ;; (println input)
   (let [inputF (last input)
         uuid (:uuid inputF)
         entry (cond
                 (:last-trade-price-exponential inputF) {:ema-list input}
                 (:last-trade-price-average inputF) {:sma-list input}
                 (:last-trade-price inputF) {:tick-list input})]

     ;; (log/info "")
     ;; (log/info "state" (with-out-str (clojure.pprint/pprint @state)))

     (if-let [current (get @state uuid)]

       (let [_ (swap! state update-in [uuid] merge entry)
             c' (get @state uuid)]

         (if (has-all-lists? c' completion-set)
           (update-state-and-complete state uuid c')
           input))

       (do (swap! state merge {uuid entry})
           input)))))

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

(defn pipeline-signals-lagging [concurrency moving-average-window
                                strategy-merged-averages merged-averages strategy-moving-averages-ch
                                strategy-bollinger-band merged-bollinger-band strategy-bollinger-band-ch]

  (pipeline concurrency strategy-merged-averages (map (partial join-averages (atom {}))) merged-averages)
  (pipeline concurrency strategy-moving-averages-ch (map (partial slag/moving-averages moving-average-window)) strategy-merged-averages)

  (pipeline concurrency strategy-bollinger-band (map (partial join-averages (atom {}) #{:tick-list :sma-list})) merged-bollinger-band)
  (pipeline concurrency strategy-bollinger-band-ch (map (partial slag/bollinger-band moving-average-window)) strategy-bollinger-band))

(defn pipeline-signals-leading [concurrency moving-average-window strategy-macd-ch macd->macd-strategy
                                strategy-stochastic-oscillator-ch stochastic-oscillator->stochastic-oscillator-strategy
                                strategy-on-balance-volume-ch on-balance-volume->on-balance-volume-ch ]

  (pipeline concurrency strategy-macd-ch (map slead/macd) macd->macd-strategy)

  (pipeline concurrency strategy-stochastic-oscillator-ch (map slead/stochastic-oscillator)
            stochastic-oscillator->stochastic-oscillator-strategy)

  (pipeline concurrency strategy-on-balance-volume-ch (map (partial sconf/on-balance-volume moving-average-window))
            on-balance-volume->on-balance-volume-ch))

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

(defn channel-strategy-moving-averages []

  (let [tick-list->moving-averages-strategy (chan (sliding-buffer 100))
        sma-list->moving-averages-strategy (chan (sliding-buffer 100))
        ema-list->moving-averages-strategy (chan (sliding-buffer 100))]
    {:tick-list->moving-averages-strategy tick-list->moving-averages-strategy
     :sma-list->moving-averages-strategy sma-list->moving-averages-strategy
     :ema-list->moving-averages-strategy ema-list->moving-averages-strategy
     :merged-averages (async/merge [tick-list->moving-averages-strategy
                                    sma-list->moving-averages-strategy
                                    ema-list->moving-averages-strategy])
     :strategy-merged-averages (chan (sliding-buffer 100) (filter :joined))
     :strategy-moving-averages-ch (chan (sliding-buffer 100))}))

(defn channel-strategy-bollinger-band []

  (let [tick-list->bollinger-band-strategy (chan (sliding-buffer 100))
        sma-list->bollinger-band-strategy (chan (sliding-buffer 100))]
    {:tick-list->bollinger-band-strategy tick-list->bollinger-band-strategy
     :sma-list->bollinger-band-strategy sma-list->bollinger-band-strategy
     :merged-bollinger-band (async/merge [tick-list->bollinger-band-strategy
                                          sma-list->bollinger-band-strategy])
     :strategy-bollinger-band (chan (sliding-buffer 100) (filter :joined))
     :strategy-bollinger-band-ch  (chan (sliding-buffer 100))}))

(defn channel->tracer [& channels]
  (for [ch channels
        :let [tracer-ch (chan (sliding-buffer 100))]]
    (do
      (bind-channels->mult ch tracer-ch)
      tracer-ch)))

(defn channel-tracer [source-list-ch tick-list-ch sma-list-ch ema-list-ch bollinger-band-ch macd-ch stochastic-oscillator-ch
                      on-balance-volume-ch relative-strength-ch merged-averages strategy-merged-averages strategy-moving-averages-ch
                      strategy-bollinger-band-ch strategy-macd-ch strategy-stochastic-oscillator-ch strategy-on-balance-volume-ch]

  (let [source-list-ch->tracer (chan (sliding-buffer 100))
        tick-list-ch->tracer (chan (sliding-buffer 100))
        sma-list-ch->tracer (chan (sliding-buffer 100))
        ema-list-ch->tracer (chan (sliding-buffer 100))
        bollinger-band-ch->tracer (chan (sliding-buffer 100))
        macd-ch->tracer (chan (sliding-buffer 100))
        stochastic-oscillator-ch->tracer (chan (sliding-buffer 100))
        on-balance-volume-ch->tracer (chan (sliding-buffer 100))
        relative-strength-ch->tracer (chan (sliding-buffer 100))
        merged-averages->tracer (chan (sliding-buffer 100))
        strategy-merged-averages->tracer (chan (sliding-buffer 100))
        strategy-moving-averages-ch->tracer (chan (sliding-buffer 100))
        strategy-bollinger-band-ch->tracer (chan (sliding-buffer 100))
        strategy-macd-ch->tracer (chan (sliding-buffer 100))
        strategy-stochastic-oscillator-ch->tracer (chan (sliding-buffer 100))
        strategy-on-balance-volume-ch->tracer (chan (sliding-buffer 100))]

    (bind-channels->mult source-list-ch source-list-ch->tracer)
    (bind-channels->mult tick-list-ch tick-list-ch->tracer)
    (bind-channels->mult sma-list-ch sma-list-ch->tracer)
    (bind-channels->mult ema-list-ch ema-list-ch->tracer)
    (bind-channels->mult bollinger-band-ch bollinger-band-ch->tracer)
    (bind-channels->mult macd-ch macd-ch->tracer)
    (bind-channels->mult stochastic-oscillator-ch stochastic-oscillator-ch->tracer)
    (bind-channels->mult on-balance-volume-ch on-balance-volume-ch->tracer)
    (bind-channels->mult relative-strength-ch relative-strength-ch->tracer)
    (bind-channels->mult merged-averages merged-averages->tracer)
    (bind-channels->mult strategy-merged-averages strategy-merged-averages->tracer)
    (bind-channels->mult strategy-moving-averages-ch strategy-moving-averages-ch->tracer)
    (bind-channels->mult strategy-bollinger-band-ch strategy-bollinger-band-ch->tracer)
    (bind-channels->mult strategy-macd-ch strategy-macd-ch->tracer)
    (bind-channels->mult strategy-stochastic-oscillator-ch strategy-stochastic-oscillator-ch->tracer)
    (bind-channels->mult strategy-on-balance-volume-ch strategy-on-balance-volume-ch->tracer)

    {:source-list-ch->tracer source-list-ch->tracer
     :tick-list-ch->tracer tick-list-ch->tracer
     :sma-list-ch->tracer sma-list-ch->tracer
     :ema-list-ch->tracer ema-list-ch->tracer
     :bollinger-band-ch->tracer bollinger-band-ch->tracer
     :macd-ch->tracer macd-ch->tracer
     :stochastic-oscillator-ch->tracer stochastic-oscillator-ch->tracer
     :on-balance-volume-ch->tracer on-balance-volume-ch->tracer
     :relative-strength-ch->tracer relative-strength-ch->tracer
     :merged-averages->tracer merged-averages->tracer
     :strategy-merged-averages->tracer strategy-merged-averages->tracer
     :strategy-moving-averages-ch->tracer strategy-moving-averages-ch->tracer
     :strategy-bollinger-band-ch->tracer strategy-bollinger-band-ch->tracer
     :strategy-macd-ch->tracer strategy-macd-ch->tracer
     :strategy-stochastic-oscillator-ch->tracer strategy-stochastic-oscillator-ch->tracer
     :strategy-on-balance-volume-ch->tracer strategy-on-balance-volume-ch->tracer}))

(defn channel->stream [& channels]
  (map #(->> %
             stream/->source
             (stream.cross/event-source->sorted-stream :last-trade-time))
       channels))

(defn setup-publisher-channel [stock-name concurrency ticker-id-filter]

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


        ;; Channel JOIN Mults
        {:keys [tick-list->JOIN sma-list->JOIN ema-list->JOIN bollinger-band->JOIN
                macd->JOIN stochastic-oscillator->JOIN
                on-balance-volume->JOIN relative-strength->JOIN]}
        (channel-join-mults)


        ;; Channels Strategy: Moving Averages
        {:keys [tick-list->moving-averages-strategy sma-list->moving-averages-strategy ema-list->moving-averages-strategy
                merged-averages strategy-merged-averages strategy-moving-averages-ch]}
        (channel-strategy-moving-averages)


        ;; Strategy: Bollinger Band
        {:keys [tick-list->bollinger-band-strategy sma-list->bollinger-band-strategy
                merged-bollinger-band strategy-bollinger-band strategy-bollinger-band-ch]}
        (channel-strategy-bollinger-band)


        [tick-list->CROSS sma-list->CROSS
         ema-list->CROSS bollinger-band->CROSS
         macd->CROSS
         stochastic-oscillator->CROSS
         on-balance-volume->CROSS
         relative-strength->CROSS]

        (channel->stream tick-list->JOIN sma-list->JOIN ema-list->JOIN bollinger-band->JOIN
                         macd->JOIN
                         stochastic-oscillator->JOIN
                         on-balance-volume->JOIN
                         relative-strength->JOIN)

        result (stream.cross/set-streams-union {:default-key-fn :last-trade-time
                                                :skey-streams {:tick-list tick-list->CROSS
                                                               :sma-list sma-list->CROSS
                                                               :ema-list ema-list->CROSS
                                                               :bollinger-band bollinger-band->CROSS
                                                               :macd macd->CROSS
                                                               :stochastic-oscillator stochastic-oscillator->CROSS
                                                               :on-balance-volume on-balance-volume->CROSS
                                                               :relative-strength relative-strength->CROSS}})

        connector-ch (chan (sliding-buffer 100))]

    ;; (bind-channels->mult macd-ch macd->JOIN)

    (doseq [source+mults [[source-list-ch tick-list-ch]
                          [tick-list-ch tick-list->sma-ch tick-list->macd-ch
                           tick-list->stochastic-osc-ch tick-list->obv-ch
                           tick-list->relative-strength-ch tick-list->JOIN]
                          [sma-list-ch sma-list->ema-ch sma-list->bollinger-band-ch
                           sma-list->macd-ch sma-list->JOIN]
                          [ema-list-ch ema-list->moving-averages-strategy
                           ema-list->JOIN]
                          [bollinger-band-ch bollinger-band->JOIN]
                          [macd-ch macd->JOIN]
                          [stochastic-oscillator-ch stochastic-oscillator->JOIN]
                          [on-balance-volume-ch on-balance-volume->JOIN]
                          [relative-strength-ch relative-strength->JOIN]]]

      (apply bind-channels->mult source+mults))


    (stream/connect @result connector-ch)


    ;; TICK LIST
    (pipeline concurrency source-list-ch handler-xform (ew/ewrapper :publisher))
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
    (pipeline-signals-lagging concurrency moving-average-window
                              strategy-merged-averages merged-averages strategy-moving-averages-ch
                              strategy-bollinger-band merged-bollinger-band strategy-bollinger-band-ch)

    {:joined-channel connector-ch}))

#_(defn setup-publisher-channel [stock-name concurrency ticker-id-filter]

  ;; TODO Remove :population
  (let [options {:stock-match {:symbol stock-name :ticker-id-filter ticker-id-filter}}

        ;; Channels Analytics
        {:keys [source-list-ch tick-list-ch sma-list-ch ema-list-ch
                bollinger-band-ch macd-ch stochastic-oscillator-ch
                on-balance-volume-ch relative-strength-ch]}
        (channel-analytics)

        ;; Channels Analytics Mults
        {:keys [tick-list->sma-ch tick-list->macd-ch sma-list->ema-ch sma-list->bollinger-band-ch sma-list->macd-ch
                tick-list->stochastic-osc-ch tick-list->obv-ch tick-list->relative-strength-ch]}
        (channel-analytics-mults)

        ;; Channels Strategy: Moving Averages
        {:keys [tick-list->moving-averages-strategy sma-list->moving-averages-strategy ema-list->moving-averages-strategy
                merged-averages strategy-merged-averages strategy-moving-averages-ch]}
        (channel-strategy-moving-averages)


        ;; Strategy: Bollinger Band
        {:keys [tick-list->bollinger-band-strategy sma-list->bollinger-band-strategy
                merged-bollinger-band strategy-bollinger-band strategy-bollinger-band-ch]}
        (channel-strategy-bollinger-band)


        macd->macd-strategy (chan (sliding-buffer 100))
        macd->on-balance-volume-strategy (chan (sliding-buffer 100))
        stochastic-oscillator->stochastic-oscillator-strategy (chan (sliding-buffer 100))
        on-balance-volume->on-balance-volume-ch (chan (sliding-buffer 100))
        strategy-macd-ch (chan (sliding-buffer 100))
        strategy-stochastic-oscillator-ch (chan (sliding-buffer 100))
        strategy-on-balance-volume-ch (chan (sliding-buffer 100))]

    (bind-channels->mult source-list-ch
                         tick-list-ch)

    (bind-channels->mult tick-list-ch
                         tick-list->sma-ch
                         tick-list->macd-ch
                         tick-list->stochastic-osc-ch
                         tick-list->obv-ch
                         tick-list->relative-strength-ch
                         tick-list->moving-averages-strategy
                         tick-list->bollinger-band-strategy)

    (bind-channels->mult sma-list-ch
                         sma-list->ema-ch
                         sma-list->bollinger-band-ch
                         sma-list->macd-ch
                         sma-list->moving-averages-strategy
                         sma-list->bollinger-band-strategy)

    (bind-channels->mult ema-list-ch
                         ema-list->moving-averages-strategy)

    (bind-channels->mult macd-ch
                         macd->macd-strategy
                         macd->on-balance-volume-strategy)

    (bind-channels->mult stochastic-oscillator-ch
                         stochastic-oscillator->stochastic-oscillator-strategy)

    (bind-channels->mult on-balance-volume-ch
                         on-balance-volume->on-balance-volume-ch)


    ;; TICK LIST
    (pipeline concurrency source-list-ch handler-xform (ew/ewrapper :publisher))
    ;; (pipeline concurrency tick-list-ch handler-xform source-list-ch)


    ;; ANALYSIS
    (pipeline-analysis-lagging concurrency options sma-list-ch tick-list->sma-ch ema-list-ch sma-list->ema-ch
                               bollinger-band-ch sma-list->bollinger-band-ch)

    (pipeline-analysis-leading concurrency options moving-average-window macd-ch sma-list->macd-ch
                               stochastic-oscillator-ch tick-list->stochastic-osc-ch)

    (pipeline-analysis-confirming concurrency on-balance-volume-ch tick-list->obv-ch
                                  relative-strength-ch tick-list->relative-strength-ch)

    ;; SIGNALS
    (pipeline-signals-lagging concurrency moving-average-window
                              strategy-merged-averages merged-averages strategy-moving-averages-ch
                              strategy-bollinger-band merged-bollinger-band strategy-bollinger-band-ch)

    (pipeline-signals-leading concurrency moving-average-window strategy-macd-ch macd->macd-strategy
                              strategy-stochastic-oscillator-ch stochastic-oscillator->stochastic-oscillator-strategy
                              strategy-on-balance-volume-ch on-balance-volume->on-balance-volume-ch)

    (let [{:keys [source-list-ch->tracer tick-list-ch->tracer sma-list-ch->tracer ema-list-ch->tracer bollinger-band-ch->tracer
                  macd-ch->tracer stochastic-oscillator-ch->tracer on-balance-volume-ch->tracer relative-strength-ch->tracer
                  merged-averages->tracer strategy-merged-averages->tracer strategy-moving-averages-ch->tracer
                  strategy-bollinger-band-ch->tracer strategy-macd-ch->tracer strategy-stochastic-oscillator-ch->tracer strategy-on-balance-volume-ch->tracer]}
          (channel-tracer source-list-ch tick-list-ch sma-list-ch ema-list-ch bollinger-band-ch macd-ch stochastic-oscillator-ch
                          on-balance-volume-ch relative-strength-ch merged-averages strategy-merged-averages strategy-moving-averages-ch strategy-bollinger-band-ch
                          strategy-macd-ch strategy-stochastic-oscillator-ch strategy-on-balance-volume-ch)]

      #_(async/go
          (let [result (async/<!
                         (async/reduce #(concat %1 (list %2))
                                       []
                                       (async/take 100 source-list-ch->tracer)))]
            (log/info result)
            (spit "foo.edn" (apply str result))))

      #_(go-loop [c 0
                r (<! source-list-ch->tracer)]
        (info "count: " c " / result: " r)
        (spit "")
        (when r
          (recur (inc c) (<! source-list-ch->tracer))))

      {:source-list-ch->tracer source-list-ch->tracer
       :tick-list-ch->tracer tick-list-ch->tracer
       :sma-list-ch->tracer sma-list-ch->tracer

       ;; :tick-list-ch tick-list-ch
       :sma-list-ch sma-list-ch
       :ema-list-ch ema-list-ch
       :bollinger-band-ch bollinger-band-ch
       :macd-ch macd-ch
       :stochastic-oscillator-ch stochastic-oscillator-ch
       :on-balance-volume-ch on-balance-volume-ch
       :relative-strength-ch relative-strength-ch
       :tick-list->sma-ch tick-list->sma-ch
       :sma-list->ema-ch sma-list->ema-ch
       :sma-list->bollinger-band-ch sma-list->bollinger-band-ch
       :sma-list->macd-ch sma-list->macd-ch
       ;; :tick-list->macd-ch tick-list->macd-ch
       :tick-list->stochastic-osc-ch tick-list->stochastic-osc-ch
       :tick-list->obv-ch tick-list->obv-ch
       :tick-list->relative-strength-ch tick-list->relative-strength-ch
       :macd->macd-strategy macd->macd-strategy
       :macd->on-balance-volume-strategy macd->on-balance-volume-strategy
       :stochastic-oscillator->stochastic-oscillator-strategy stochastic-oscillator->stochastic-oscillator-strategy
       :on-balance-volume->on-balance-volume-ch on-balance-volume->on-balance-volume-ch
       ;; :strategy-moving-averages strategy-moving-averages
       ;; :strategy-bollinger-band strategy-bollinger-band
       :strategy-macd-ch strategy-macd-ch
       :strategy-stochastic-oscillator-ch strategy-stochastic-oscillator-ch
       :strategy-on-balance-volume-ch strategy-on-balance-volume-ch

       :merged-averages->tracer merged-averages->tracer
       :strategy-merged-averages->tracer strategy-merged-averages->tracer
       :strategy-moving-averages-ch->tracer strategy-moving-averages-ch->tracer})))

(defn teardown-publisher-channel [processing-pipeline]
  #_(doseq [vl (vals processing-pipeline)]
    (close! vl)))

(defstate processing-pipeline
  :start (setup-publisher-channel "TSLA" 1 0)
  :stop (teardown-publisher-channel processing-pipeline))
