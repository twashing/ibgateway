(ns com.interrupt.edgar.core.analysis.leading
  (:require [com.interrupt.edgar.core.analysis.lagging :as lagging]
            [com.interrupt.edgar.core.analysis.common :refer [time-increases-left-to-right?]]))

(defn macd-ticks
  ([ticks]
   (macd-ticks ticks 12 26 9))
  ([ticks fast-window slow-window signal-window]
   {:pre [(time-increases-left-to-right? ticks)
          (< fast-window slow-window)
          (let [n (count ticks)]
            (= n (max n slow-window signal-window fast-window)))]}
   (let [prices (map :last-trade-price ticks)
         ema-slow (lagging/ema (take-last slow-window prices))
         ema-signal (lagging/ema (take-last signal-window prices))
         ema-fast (lagging/ema (take-last fast-window prices))
         macd (- ema-fast ema-slow)]
     (-> (last ticks)
         (assoc :last-trade-macd macd
                :ema-signal ema-signal
                :histogram (- macd ema-signal))))))

(defn macd
  "The MACD 'oscillator' or 'indicator' is a collection of three signals (or computed data-series), calculated from historical price data. These three signal lines are:

    i) the MACD line: difference between the 12 and 26 days EMAs
      MACD = EMA[stockPrices,12] – EMA[stockPrices,26]

    ii) the signal line (or average line): 9 EMA of the MACD line
      signal = EMA[MACD,9]

    iii) and the difference (or divergence): difference between the blue and red lines
      histogram = MACD – signal

    Options are:
      :macd-window-fast (default is 12)
      :macd-window-slow (default is 26)
      :signal-window (default is 9)

    ** This function assumes the latest tick is on the left**"

  [options tick-window sma-list]
  {:pre [(time-increases-left-to-right? sma-list)]}

  ;; compute the MACD line
  (let [{macd-fast :macd-window-fast
         macd-slow :macd-window-slow
         signal-window :signal-window
         :or {macd-fast 12
              macd-slow 26
              signal-window 9}} options

        ;; 1. compute 12 EMA
        ema-short (lagging/exponential-moving-average nil macd-fast sma-list)

        ;; 2. compute 26 EMA
        ema-long (lagging/exponential-moving-average nil macd-slow sma-list)

        ;; 3. for each tick, compute difference between 12 and 26 EMA
        ;; EMA lists will have a structure like:
        #_({:last-trade-price 203.98,
            :last-trade-time 1368215573010,
            :last-trade-price-exponential 204.00119130504845})

        macd (map (fn [e1 e2]

                    (if (and (-> e1 nil? not)
                             (-> e2 nil? not))

                      {:last-trade-price (:last-trade-price e1)
                       :last-trade-time (:last-trade-time e1)
                       :last-trade-macd (- (:last-trade-price-exponential e1) (:last-trade-price-exponential e2))}))
                  ema-short
                  ema-long)

        ;; Compute 9 EMA of the MACD
        ema-signal (lagging/exponential-moving-average {:input :last-trade-macd :output :ema-signal :etal [:last-trade-price :last-trade-time]} signal-window macd)]

    ;; compute the difference, or divergence
    (map (fn [e-macd e-ema]

           (if (and (-> e-macd nil? not)
                    (-> e-ema nil? not))

             {:last-trade-price (:last-trade-price e-macd)
              :last-trade-time (:last-trade-time e-macd)
              :last-trade-macd (:last-trade-macd e-macd)
              :ema-signal (:ema-signal e-ema)
              :histogram (- (:last-trade-macd e-macd) (:ema-signal e-ema))}))
         macd
         ema-signal)))

(defn so
  [prices]
  (let [c (last prices)
        low (apply min prices)
        high (apply max prices)]
    (if (= low high)
      0
      (* 100 (/ (- c low) (- high low))))))

(defn so-ticks
  [ticks]
  {:pre [(time-increases-left-to-right? ticks)]}
  (let [prices (map :last-trade-price)]
    (-> (last ticks)
        (assoc :highest-price (apply max prices)
               :lowest-price (apply min prices)
               :K (so prices)))))

(defn stochastic-oscillator
  "The stochastic oscillator is a momentum indicator. According to George C. Lane (the inventor), it 'doesn't follow price, it doesn't follow volume or anything like that. It follows the speed or the momentum of price. As a rule, the momentum changes direction before price'. A 3-line Stochastics will give an anticipatory signal in %K, a signal in the turnaround of %D at or before a bottom, and a confirmation of the turnaround in %D-Slow. Smoothing the indicator over 3 periods is standard. Returns a list, equal in length to the tick-list, but only with slots filled, where preceding tick-list allows.

     i) last-price:
       the last closing price

     ii) %K:
       (last-price - low-price / high-price - low-price) * 100

     iii) %D:
       3-period exponential moving average of %K

     iv)  %D-Slow
       3-period exponential moving average of %D

     v) low-price:
       the lowest price over the last N periods

     vi) high-price:
       the highest price over the last N periods


   The inputs to this function are:

     tick-window: the length of Stochastic, or number of ticks under observation (defaults to 14)
     trigger-window: the smoothing line (defaults to 3)
     trigger-line: (defaults to 3)
     tick-list: the input time series (in last trade price)

   ** This function assumes the latest tick is on the left**"

  [tick-window trigger-window trigger-line tick-list]
  {:pre [(time-increases-left-to-right? tick-list)]}

  (let [;; calculate %K
        stochastic-list (reduce (fn [acc ech]

                                  (let [last-time (-> ech last :last-trade-time)
                                        last-price (-> ech last :last-trade-price)
                                        last-price-list (map #(:last-trade-price %) ech)
                                        highest-price (apply max last-price-list)
                                        lowest-price (apply min last-price-list)

                                        ;; calculate %K
                                        %K (try
                                             (/ (- last-price lowest-price) (- highest-price lowest-price))
                                             (catch Exception e
                                               0))]

                                    (concat acc [{:last-trade-price last-price
                                                  :last-trade-time last-time
                                                  :highest-price highest-price
                                                  :lowest-price lowest-price
                                                  :K %K}])))
                                []
                                (partition tick-window 1 tick-list))

        ;; ... TODO - should %D be a simple moving average of %K (instead of exponential moving average)

        ;; calculate %D
        d-list (reduce (fn [acc ech]

                         (let [elist (lagging/exponential-moving-average {:input :K
                                                                          :output :D
                                                                          :etal [:last-trade-time :last-trade-price :highest-price :lowest-price :K]} 3 ech)]
                           (concat acc (-> elist last list))))
                       []
                       (partition trigger-window 1 stochastic-list))]
    d-list))
