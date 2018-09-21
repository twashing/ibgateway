(ns com.interrupt.edgar.core.signal.lagging
  (:require [com.interrupt.edgar.core.analysis.lagging :as analysis]
            [com.interrupt.edgar.core.analysis.confirming :as confirming]
            [com.interrupt.edgar.core.signal.common :as common]
            [clojure.tools.logging :refer [info] :as log]))


(defn join-averages
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
  (let [joined-list (join-averages tick-window tick-list sma-list ema-list)
        partitioned-join (partition 2 1 (remove nil? joined-list))]


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
                                (assoc :signals [{:signal :down
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
  [tick-window {:keys [tick-list bollinger-band]}]

  (let [;; Track widest & narrowest band over the last 'n' (3) ticks
        sorted-bands (sort-bollinger-band bollinger-band)
        most-narrow (take 3 sorted-bands)
        most-wide (take-last 3 sorted-bands)

        ;; _ (log/info "each-list: " bollinger-band)
        ;; _ (log/info "Most narrow / wide: " most-narrow most-wide)

        partitioned-list (partition 2 1 bollinger-band)

        upM? (common/up-market? 10 partitioned-list)
        downM? (common/down-market? 10 partitioned-list)

        ;; _ (log/info "upM? / downM?" upM? downM?)

        ;; TODO - determine how far back to look (defaults to 10 ticks) to decide on an UP or DOWN market
        ;; TODO - does tick price fluctuate abouve and below the MA
        ;; TODO - B iv. entry signal -> check if one of next 3 closes are underneath the priors (or are in the opposite direction)
        #_side-market? #_(if (and (not upM?) (not downM?))
                           true false)

        ;; find last 3 peaks and valleys
        peaks-valleys (common/find-peaks-valleys nil tick-list)
        peaks (:peak (group-by :signal peaks-valleys))
        valleys (:valley (group-by :signal peaks-valleys))]

    (if (or upM? downM?)

      ;; A.
      (let [latest-diff (- (-> bollinger-band last :upper-band)
                           (-> bollinger-band last :lower-band))
            less-than-any-narrow? (some #(< latest-diff (:difference %)) most-narrow)]

        (if less-than-any-narrow?

          ;; entry signal -> close is outside of band, and previous swing high/low is inside the band
          (if upM?

            (if (and (< (-> bollinger-band last :last-trade-price)
                        (-> bollinger-band last :lower-band))

                     ;; TODO we probably want to bound the range backwards, by 5 - 7 ticks
                     (> (-> valleys last :last-trade-price)
                        (:lower-band (first (some #(= (:last-trade-time %)
                                                      (-> valleys last :last-trade-time))
                                                  bollinger-band)))))

              (-> bollinger-band
                  last
                  (assoc :signals [{:signal :down
                                    :why :bollinger-close-abouve
                                    :arguments [bollinger-band valleys]}]))

              (-> bollinger-band last))

            (if (and (> (-> bollinger-band last :last-trade-price)
                        (-> bollinger-band last :upper-band))
                     (< (-> peaks last :last-trade-price)
                        (:upper-band (first (some #(= (:last-trade-time %)
                                                      (-> peaks last :last-trade-time)))
                                            bollinger-band))))

              (-> bollinger-band
                  last
                  (assoc :signals [{:signal :up
                                    :why :bollinger-close-below
                                    :arguments [bollinger-band peaks]}]))

              (-> bollinger-band last)))))

      ;; B.
      (let [latest-diff (- (-> bollinger-band last :upper-band)
                           (-> bollinger-band last :lower-band))
            more-than-any-wide? (some (fn [inp] (> latest-diff (:difference inp))) most-wide)]

        (if more-than-any-wide?

          ;; B iii RSI Divergence
          (let [OVER_BOUGHT 80
                OVER_SOLD 20
                rsi-list (confirming/relative-strength-index 14 bollinger-band)


                ;; i. price makes a higher high and
                higher-highPRICE? (if (empty? peaks)
                                    false
                                    (> (-> bollinger-band last :last-trade-price)
                                       (-> peaks last :last-trade-price)))


                ;; ii. rsi devergence makes a lower high
                lower-highRSI? (if (or (empty? peaks)
                                       (some #(nil? (:last-trade-time %)) rsi-list)
                                       (not (nil? rsi-list)))
                                 false
                                 (< (-> rsi-list last :rsi)
                                    (:rsi (first (filter #(= (:last-trade-time %)
                                                             (-> peaks last :last-trade-time))
                                                         rsi-list)))))

                ;; iii. and divergence should happen abouve the overbought line
                divergence-overbought? (> (-> rsi-list last :rsi)
                                          OVER_BOUGHT)



                ;; i. price makes a lower low
                lower-highPRICE? (if (or (empty? valleys)
                                         (some #(nil? (:last-trade-time %)) rsi-list))
                                   false
                                   (< (-> bollinger-band last :last-trade-price)
                                      (-> valleys last :last-trade-price)))

                higher-highRSI? (if (or (empty? valleys)
                                        (not (nil? rsi-list)))
                                  false
                                  (> (-> rsi-list last :rsi)
                                     (:rsi (first (filter #(= (:last-trade-time %)
                                                              (:last-trade-time (first valleys)))
                                                          rsi-list)))))

                divergence-oversold? (< (-> rsi-list last :rsi)
                                        OVER_SOLD)]

            (if (and higher-highPRICE? lower-highRSI? divergence-overbought?)

              (-> bollinger-band
                  last
                  (assoc :signals [{:signal :down
                                    :why :bollinger-divergence-overbought
                                    :arguments [peaks bollinger-band rsi-list]}]))

              (if (and lower-highPRICE? higher-highRSI? divergence-oversold?)

                (-> bollinger-band
                    last
                    (assoc :signals [{:signal :up
                                      :why :bollinger-divergence-oversold
                                      :arguments [valleys bollinger-band rsi-list]}]))

                (-> bollinger-band last))))

          (-> bollinger-band last))))
    (-> bollinger-band last)))
