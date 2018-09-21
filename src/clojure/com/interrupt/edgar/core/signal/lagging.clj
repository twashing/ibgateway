(ns com.interrupt.edgar.core.signal.lagging
  (:require [com.interrupt.edgar.core.analysis.lagging :as analysis]
            [com.interrupt.edgar.core.analysis.confirming :as confirming]
            [com.interrupt.edgar.core.signal.common :as common]
            [clojure.tools.logging :refer [info] :as log]))


#_(defn join-averages
  "Create a list where i) tick-list ii) sma-list and iii) ema-list are overlaid.

   ** This function assumes the latest tick is on the right**"

  ([tick-window tick-list]

   (let [sma-list (analysis/simple-moving-average nil tick-window tick-list)
         ema-list (analysis/exponential-moving-average nil tick-window tick-list sma-list)]
     (join-averages tick-window tick-list sma-list ema-list)))

  ([tick-window tick-list sma-list ema-list]
   (map (fn [titem sitem eitem]

          ;; 1. ensure that we have the :last-trade-time for simple and exponential items
          ;; 2. ensure that all 3 time items line up
          (if (and (and (not (nil? (:last-trade-time sitem)))
                        (not (nil? (:last-trade-time eitem))))
                   (= (:last-trade-time titem) (:last-trade-time sitem) (:last-trade-time eitem)))

            {:last-trade-time (:last-trade-time titem)
             :last-trade-price (if (string? (:last-trade-price titem))
                                 (read-string (:last-trade-price titem))
                                 (:last-trade-price titem))
             :last-trade-price-average (:last-trade-price-average sitem)
             :last-trade-price-exponential (:last-trade-price-exponential eitem)}

            nil))

        tick-list
        sma-list
        ema-list)))

(defn moving-averages
  "Takes baseline time series, along with 2 other moving averages.

   Produces a list of signals where the 2nd moving average overlaps (abouve or below) the first.
   By default, this function will produce a Simple Moving Average and an Exponential Moving Average.

   ** This function assumes the latest tick is on the right**"
  [tick-window {:keys [tick-list sma-list ema-list] :as joined-map}]

  ;; create a list where i) tick-list ii) sma-list and iii) ema-list are overlaid
  (let [;; joined-list (join-averages tick-window tick-list sma-list ema-list)
        ;; _ (log/info "joined-list" joined-list)
        ;; merged-map (->> joined-map vals (apply merge))
        partitioned-join (partition 2 1 (remove nil? joined-map))]


    ;; find time points where ema-list (or second list) crosses over the sma-list (or 1st list)
    (reduce (fn [rslt ech]

              (let [fst (first ech)
                    snd (second ech)

                    ;; in the first element, has the ema crossed abouve the sma from the second element
                    signal-up (and (< (:last-trade-price-exponential snd) (:last-trade-price-average snd))
                                   (> (:last-trade-price-exponential fst) (:last-trade-price-average fst)))

                    ;; in the first element, has the ema crossed below the sma from the second element
                    signal-down (and (> (:last-trade-price-exponential snd) (:last-trade-price-average snd))
                                     (< (:last-trade-price-exponential fst) (:last-trade-price-average fst)))

                    raw-data fst]

                ;; return either i) :up signal, ii) :down signal or iii) nothing, with just the raw data
                (if signal-up
                  (concat rslt
                          (-> raw-data
                              (assoc :signals [{:signal :up
                                                :why :moving-average-crossover
                                                :arguments [fst snd]}])
                              list))
                  (if signal-down
                    (concat rslt
                            (-> raw-data
                                (assoc :isgnals [{:signal :down
                                                  :why :moving-average-crossover
                                                  :arguments [fst snd]}])
                                list))
                    (concat rslt (list raw-data))))))
            []
            partitioned-join)))

(defn sort-bollinger-band [bband]
  (->> bband
       (remove nil?)
       (map (fn [inp] (assoc inp :difference (- (:upper-band inp) (:lower-band inp)))))
       (sort-by :difference)))

(defn bollinger-band
  "Implementing signals for analysis/bollinger-band. Taken from these videos:
     i. http://www.youtube.com/watch?v=tkwUOUZQZ3s
     ii. http://www.youtube.com/watch?v=7PY4XxQWVfM


      A. when the band width is very low, can indicate that price will breakout sooner than later;

      i. MA is in an UP or DOWN market
      ii. check for narrow bollinger band width ; less than the most previous narrow band width
      iii. close is outside of band, and previous swing high/low is inside the band


       B. when the band width is very high (high volatility); can mean that the trend is ending soon; can i. change direction or ii. consolidate

       i. MA is in a sideways (choppy) market -> check if many closes that are abouve or below the bollinger band
       ii. check for a wide bollinger band width ; greater than the most previous wide band
       iii. RSI Divergence; i. price makes a higher high and ii. rsi devergence makes a lower high iii. and divergence should happen abouve the overbought line
       iv. entry signal -> check if one of next 3 closes are underneath the priors (or are in the opposite direction)

   ** This function assumes the latest tick is on the right**"
  ([tick-window {:keys [tick-list sma-list]}]
   (bollinger-band tick-window tick-list sma-list))

  ([tick-window tick-list sma-list]

   (let [bband (analysis/bollinger-band tick-window sma-list)]

     (reduce (fn [rslt ech-list]

               (let [;; Track widest & narrowest band over the last 'n' ( 3 ) ticks
                     sorted-bands (sort-bollinger-band ech-list)
                     most-narrow (take 3 sorted-bands)
                     most-wide (take-last 3 sorted-bands)

                     ;; _ (log/info "Most narrow / wide: " most-narrow most-wide)

                     partitioned-list (partition 2 1 ech-list)

                     upM? (common/up-market? 10 partitioned-list)
                     downM? (common/down-market? 10 partitioned-list)

                     ;; _ (log/info "upM? / downM?" upM? downM?)

                     ;; TODO - determine how far back to look (defaults to 10 ticks) to decide on an UP or DOWN market
                     ;; TODO - does tick price fluctuate abouve and below the MA
                     ;; TODO - B iv. entry signal -> check if one of next 3 closes are underneath the priors (or are in the opposite direction)
                     #_side-market? #_(if (and (not upM?) (not downM?))
                                    true false)

                     ;; find last 3 peaks and valleys
                     peaks-valleys (common/find-peaks-valleys nil ech-list)
                     peaks (:peak (group-by :signal peaks-valleys))
                     valleys (:valley (group-by :signal peaks-valleys))

                     ;; _ (log/info "peaks-valleys" peaks-valleys)
                     ]

                 (if (empty? ech-list)

                   (concat rslt (list nil))

                   (if (or upM? downM?)

                     ;; A.
                     (let [latest-diff (- (:upper-band (first ech-list)) (:lower-band (first ech-list)))
                           less-than-any-narrow? (some #(< latest-diff (:difference %)) most-narrow)]

                       (if less-than-any-narrow?

                         ;; entry signal -> close is outside of band, and previous swing high/low is inside the band
                         (if upM?

                           (if (and (< (:last-trade-price (first ech-list)) (:lower-band (first ech-list)))
                                    (> (:last-trade-price (first valleys)) (:lower-band (first (some #(= (:last-trade-time %) (:last-trade-time (first valleys)))
                                                                                                     ech-list)))))

                             (concat rslt (-> ech-list
                                              first
                                              (assoc :signals [{:signal :down
                                                                :why :bollinger-close-abouve
                                                                :arguments [ech-list valleys]}])))

                             (concat rslt (-> ech-list first list)))

                           (if (and (> (:last-trade-price (first ech-list)) (:upper-band (first ech-list)))
                                    (< (:last-trade-price (first peaks)) (:upper-band (first (some #(= (:last-trade-time %) (:last-trade-time (first peaks))))
                                                                                             ech-list))))

                             (concat rslt (-> ech-list
                                              first
                                              (assoc :signals [{:signal :up
                                                                :why :bollinger-close-below
                                                                :arguments [ech-list peaks]}])
                                              list))

                             (concat rslt (-> ech-list first list))))))

                     ;; B.
                     (let [latest-diff (- (:upper-band (first ech-list)) (:lower-band (first ech-list)))
                           more-than-any-wide? (some (fn [inp] (> latest-diff (:difference inp))) most-wide)]

                       (if more-than-any-wide?

                         ;; B iii RSI Divergence
                         (let [OVER_BOUGHT 80
                               OVER_SOLD 20
                               rsi-list (confirming/relative-strength-index 14 ech-list)


                               ;; i. price makes a higher high and
                               higher-highPRICE? (if (empty? peaks)
                                                   false
                                                   (> (:last-trade-price (first ech-list))
                                                      (:last-trade-price (first peaks))))


                               ;; ii. rsi devergence makes a lower high
                               lower-highRSI? (if (or (empty? peaks)
                                                      (some #(nil? (:last-trade-time %)) rsi-list)
                                                      (not (nil? rsi-list)))
                                                false
                                                (< (:rsi (first rsi-list))
                                                   (:rsi (first (filter (fn [inp]

                                                                          (log/info "... signal.lagging/bollinger-band > lower-lowRSI? > rsi-list > ech[" inp "]")
                                                                          (= (:last-trade-time inp)
                                                                             (:last-trade-time (first peaks))))
                                                                        rsi-list)))))

                               ;; iii. and divergence should happen abouve the overbought line
                               divergence-overbought? (> (:rsi (first rsi-list))
                                                         OVER_BOUGHT)



                               ;; i. price makes a lower low
                               lower-highPRICE? (if (or (empty? valleys)
                                                        (some #(nil? (:last-trade-time %)) rsi-list))
                                                  false
                                                  (< (:last-trade-price (first ech-list))
                                                     (:last-trade-price (first valleys))))

                               higher-highRSI? (if (or (empty? valleys)
                                                       (not (nil? rsi-list)))
                                                 false
                                                 (> (:rsi (first rsi-list))
                                                    (:rsi (first (filter (fn [inp]

                                                                           (log/info "... signal.lagging/bollinger-band > higher-highRSI? > RSI-LIST > ech[" inp "]")
                                                                           (= (:last-trade-time inp)
                                                                              (:last-trade-time (first valleys))))
                                                                         rsi-list)))))

                               divergence-oversold? (< (:rsi (first rsi-list))
                                                       OVER_SOLD)]

                           (if (and higher-highPRICE? lower-highRSI? divergence-overbought?)

                             (concat rslt (-> ech-list
                                              first
                                              (assoc :signals [{:signal :down
                                                                :why :bollinger-divergence-overbought
                                                                :arguments [peaks ech-list rsi-list]}])
                                              list))

                             (if (and lower-highPRICE? higher-highRSI? divergence-oversold?)

                               (concat rslt (-> ech-list
                                                first
                                                (assoc :signals [{:signal :up
                                                                  :why :bollinger-divergence-oversold
                                                                  :arguments [valleys ech-list rsi-list]}])
                                                list))

                               (concat rslt (-> ech-list first list)))))

                         (concat rslt (-> ech-list first list))))))
                 (concat rslt (-> ech-list first list))))
             []
             (partition tick-window 1 bband)))))
