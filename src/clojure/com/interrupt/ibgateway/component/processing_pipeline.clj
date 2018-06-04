(ns com.interrupt.ibgateway.component.processing-pipeline
  (:require [clojure.core.async :refer [chan to-chan sliding-buffer close! <! >!
                                        go-loop mult tap mix pipeline onto-chan] :as async]
            [clojure.set :refer [subset?]]
            [mount.core :refer [defstate] :as mount]
            [clojure.tools.logging :refer [debug info warn error] :as log]
            [net.cgrand.xforms :as x]
            [com.interrupt.edgar.core.edgar :as edg]
            [com.interrupt.ibgateway.component.ewrapper :as ew]
            [com.interrupt.edgar.ib.market :as mkt]
            [com.interrupt.edgar.ib.handler.live :refer [feed-handler] :as live]
            [com.interrupt.edgar.core.analysis.lagging :as alag]
            [com.interrupt.edgar.core.analysis.leading :as alead]
            [com.interrupt.edgar.core.analysis.confirming :as aconf]
            [com.interrupt.edgar.core.signal.lagging :as slag]
            [com.interrupt.edgar.core.signal.leading :as slead]
            [com.interrupt.edgar.core.signal.confirming :as sconf]

            [manifold.stream :as stream]
            [prpr.stream.cross :as stream.cross]
            prpr.stream
            [prpr.promise :refer [ddo]]
            [xn.transducers :as xn]
            [com.rpl.specter :refer :all]))


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

(comment

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

(defn pipeline-analysis-lagging [concurrency options
                                 sma-list-ch tick-list->sma-ch
                                 ema-list-ch sma-list->ema-ch
                                 bollinger-band-ch sma-list->bollinger-band-ch]

  (pipeline concurrency sma-list-ch (map (partial alag/simple-moving-average options)) tick-list->sma-ch)
  (pipeline concurrency ema-list-ch (map (partial alag/exponential-moving-average options live/moving-average-window)) sma-list->ema-ch)
  (pipeline concurrency bollinger-band-ch (map (partial alag/bollinger-band live/moving-average-window)) sma-list->bollinger-band-ch))

(defn pipeline-analysis-leading [concurrency options moving-average-window
                                 macd-ch sma-list->macd-ch
                                 stochastic-oscillator-ch tick-list->stochastic-osc-ch]

  (pipeline concurrency macd-ch (map (partial alead/macd options moving-average-window)) sma-list->macd-ch)
  (pipeline-stochastic-oscillator concurrency stochastic-oscillator-ch tick-list->stochastic-osc-ch))

(defn pipeline-analysis-confirming [concurrency on-balance-volume-ch tick-list->obv-ch
                                    relative-strength-ch tick-list->relative-strength-ch]

  (pipeline concurrency on-balance-volume-ch (map aconf/on-balance-volume) tick-list->obv-ch)
  (pipeline-relative-strength-index concurrency relative-strength-ch tick-list->relative-strength-ch))

(defn pipeline-signals-lagging [concurrency moving-average-window strategy-merged-averages merged-averages strategy-moving-averages-ch
                                strategy-bollinger-band merged-bollinger-band strategy-bollinger-band-ch]

  (pipeline concurrency strategy-merged-averages (map (partial join-averages (atom {}))) merged-averages)
  (pipeline concurrency strategy-moving-averages-ch (map (partial slag/moving-averages live/moving-average-window)) strategy-merged-averages)

  (pipeline concurrency strategy-bollinger-band (map (partial join-averages (atom {}) #{:tick-list :sma-list})) merged-bollinger-band)
  (pipeline concurrency strategy-bollinger-band-ch (map (partial slag/bollinger-band live/moving-average-window)) strategy-bollinger-band))

(defn pipeline-signals-leading [concurrency moving-average-window strategy-macd-ch macd->macd-strategy
                                strategy-stochastic-oscillator-ch stochastic-oscillator->stochastic-oscillator-strategy
                                strategy-on-balance-volume-ch on-balance-volume->on-balance-volume-ch ]

  (pipeline concurrency strategy-macd-ch (map slead/macd) macd->macd-strategy)

  (pipeline concurrency strategy-stochastic-oscillator-ch (map slead/stochastic-oscillator)
            stochastic-oscillator->stochastic-oscillator-strategy)

  (pipeline concurrency strategy-on-balance-volume-ch (map (partial sconf/on-balance-volume live/moving-average-window))
            on-balance-volume->on-balance-volume-ch))



(defn channel-analytics []
  {:tick-list-ch (chan (sliding-buffer 100) (x/partition live/moving-average-window live/moving-average-increment (x/into [])))
   :sma-list-ch (chan (sliding-buffer 100) (x/partition live/moving-average-window live/moving-average-increment (x/into [])))
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

(defn channel-tracer [sma-list-ch ema-list-ch bollinger-band-ch macd-ch stochastic-oscillator-ch
                      on-balance-volume-ch relative-strength-ch strategy-moving-averages-ch strategy-bollinger-band-ch
                      strategy-macd-ch strategy-stochastic-oscillator-ch strategy-on-balance-volume-ch]

  (let [sma-list-ch->tracer (chan (sliding-buffer 100))
        ema-list-ch->tracer (chan (sliding-buffer 100))
        bollinger-band-ch->tracer (chan (sliding-buffer 100))
        macd-ch->tracer (chan (sliding-buffer 100))
        stochastic-oscillator-ch->tracer (chan (sliding-buffer 100))
        on-balance-volume-ch->tracer (chan (sliding-buffer 100))
        relative-strength-ch->tracer (chan (sliding-buffer 100))
        strategy-moving-averages-ch->tracer (chan (sliding-buffer 100))
        strategy-bollinger-band-ch->tracer (chan (sliding-buffer 100))
        strategy-macd-ch->tracer (chan (sliding-buffer 100))
        strategy-stochastic-oscillator-ch->tracer (chan (sliding-buffer 100))
        strategy-on-balance-volume-ch->tracer (chan (sliding-buffer 100))]

    (bind-channels->mult sma-list-ch sma-list-ch->tracer)
    (bind-channels->mult ema-list-ch ema-list-ch->tracer)
    (bind-channels->mult bollinger-band-ch bollinger-band-ch->tracer)
    (bind-channels->mult macd-ch macd-ch->tracer)
    (bind-channels->mult stochastic-oscillator-ch stochastic-oscillator-ch->tracer)
    (bind-channels->mult on-balance-volume-ch on-balance-volume-ch->tracer)
    (bind-channels->mult relative-strength-ch relative-strength-ch->tracer)
    (bind-channels->mult strategy-moving-averages-ch strategy-moving-averages-ch->tracer)
    (bind-channels->mult strategy-bollinger-band-ch strategy-bollinger-band-ch->tracer)
    (bind-channels->mult strategy-macd-ch strategy-macd-ch->tracer)
    (bind-channels->mult strategy-stochastic-oscillator-ch strategy-stochastic-oscillator-ch->tracer)
    (bind-channels->mult strategy-on-balance-volume-ch strategy-on-balance-volume-ch->tracer)

    {:sma-list-ch->tracer sma-list-ch->tracer
     :ema-list-ch->tracer ema-list-ch->tracer
     :bollinger-band-ch->tracer bollinger-band-ch->tracer
     :macd-ch->tracer macd-ch->tracer
     :stochastic-oscillator-ch->tracer stochastic-oscillator-ch->tracer
     :on-balance-volume-ch->tracer on-balance-volume-ch->tracer
     :relative-strength-ch->tracer relative-strength-ch->tracer
     :strategy-moving-averages-ch->tracer strategy-moving-averages-ch->tracer
     :strategy-bollinger-band-ch->tracer strategy-bollinger-band-ch->tracer
     :strategy-macd-ch->tracer strategy-macd-ch->tracer
     :strategy-stochastic-oscillator-ch->tracer strategy-stochastic-oscillator-ch->tracer
     :strategy-on-balance-volume-ch->tracer strategy-on-balance-volume-ch->tracer}))

;; PIPELINES
(defn setup-publisher-channel [stock-name concurrency ticker-id-filter]

  ;; TODO Remove :population
  (let [options {:stock-match {:symbol stock-name :ticker-id-filter ticker-id-filter}}

        ;; Channels Analytics
        {:keys [tick-list-ch sma-list-ch ema-list-ch
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
    (pipeline concurrency tick-list-ch live/handler-xform (ew/ewrapper :publisher))


    ;; ANALYSIS
    (pipeline-analysis-lagging concurrency options sma-list-ch tick-list->sma-ch ema-list-ch sma-list->ema-ch
                               bollinger-band-ch sma-list->bollinger-band-ch)

    (pipeline-analysis-leading concurrency options live/moving-average-window macd-ch sma-list->macd-ch
                               stochastic-oscillator-ch tick-list->stochastic-osc-ch)

    (pipeline-analysis-confirming concurrency on-balance-volume-ch tick-list->obv-ch
                                  relative-strength-ch tick-list->relative-strength-ch)

    ;; SIGNALS
    (pipeline-signals-lagging concurrency live/moving-average-window
                              strategy-merged-averages merged-averages strategy-moving-averages-ch
                              strategy-bollinger-band merged-bollinger-band strategy-bollinger-band-ch)

    (pipeline-signals-leading concurrency live/moving-average-window strategy-macd-ch macd->macd-strategy
                              strategy-stochastic-oscillator-ch stochastic-oscillator->stochastic-oscillator-strategy
                              strategy-on-balance-volume-ch on-balance-volume->on-balance-volume-ch)

    (let [{:keys [sma-list-ch->tracer ema-list-ch->tracer bollinger-band-ch->tracer macd-ch->tracer
                  stochastic-oscillator-ch->tracer on-balance-volume-ch->tracer relative-strength-ch->tracer
                  strategy-moving-averages-ch->tracer strategy-bollinger-band-ch->tracer strategy-macd-ch->tracer
                  strategy-stochastic-oscillator-ch->tracer strategy-on-balance-volume-ch->tracer]}
          (channel-tracer sma-list-ch ema-list-ch bollinger-band-ch macd-ch stochastic-oscillator-ch
                          on-balance-volume-ch relative-strength-ch strategy-moving-averages-ch strategy-bollinger-band-ch
                          strategy-macd-ch strategy-stochastic-oscillator-ch strategy-on-balance-volume-ch)]

      (go-loop [r (<! strategy-macd-ch->tracer)]
          (info "result: " r)
          (when r
            (recur (<! strategy-macd-ch->tracer)))))

    {:tick-list-ch tick-list-ch
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

     :strategy-merged-averages strategy-merged-averages
     :strategy-moving-averages-ch strategy-moving-averages-ch}))

(defn teardown-publisher-channel [processing-pipeline]
  (doseq [vl (vals processing-pipeline)]
    (close! vl)))

(defstate processing-pipeline
  :start (setup-publisher-channel "TSLA" 1 0)
  :stop (teardown-publisher-channel processing-pipeline))

(comment

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
    (go-loop [r #_{:keys [tick-list sma-list] :as r} (<! connector-ch)]
      (info "record: " r)
      (if-not r
        r
        (recur (<! connector-ch))))

    (onto-chan tick-list->macd-ch [{:id :a :val 1} {:id :b :val 2} {:id :c :val 3}])
    (onto-chan sma-list->macd-ch [{:id :a :val 2} {:id :b :val 3} {:id :c :val 4}])))
