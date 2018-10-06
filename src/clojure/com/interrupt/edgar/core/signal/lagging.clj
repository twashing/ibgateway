(ns com.interrupt.edgar.core.signal.lagging
  (:require [com.interrupt.edgar.core.analysis.lagging :as analysis]
            [com.interrupt.edgar.core.analysis.confirming :as confirming]
            [com.interrupt.edgar.core.signal.common :as common]
            [clojure.tools.logging :refer [info] :as log]))


(defn moving-averages
  "Takes baseline time series, along with 2 other moving averages.

   Produces a list of signals where the 2nd moving average overlaps (abouve or below) the first.
   By default, this function will produce a Simple Moving Average and an Exponential Moving Average.

   ** This function assumes the latest tick is on the right"
  [joined-list]

  (let [lst (last joined-list)
        snd (-> joined-list butlast first)

        ;; in the first element, has the ema crossed abouve the sma from the second element
        signal-up (and (< (:last-trade-price-exponential snd) (:last-trade-price-average snd))
                       (> (:last-trade-price-exponential lst) (:last-trade-price-average lst)))

        ;; in the first element, has the ema crossed below the sma from the second element
        signal-down (and (> (:last-trade-price-exponential snd) (:last-trade-price-average snd))
                         (< (:last-trade-price-exponential lst) (:last-trade-price-average lst)))]

    ;; return either i) :up signal, ii) :down signal or iii) nothing, with just the raw data
    (if signal-up
      (-> lst
          (assoc :signals [{:signal :up
                            :why :moving-average-crossover}]))
      (if signal-down
        (-> lst
            (assoc :signals [{:signal :down
                              :why :moving-average-crossover}]))
        lst))))

(defn sort-bollinger-band [bband]
  (->> bband
       (remove nil?)
       (map (fn [inp] (assoc inp :difference (- (:upper-band inp) (:lower-band inp)))))
       (sort-by :difference)))

(defn bind-result [item a]
  (update-in item [:result] conj a))

(defn analysis-rsi-divergence [{:keys [tick-list bollinger-band] :as item} most-wide peaks-valleys]

  (let [peaks (:peak (group-by :signal peaks-valleys))
        valleys (:valley (group-by :signal peaks-valleys))
        latest-diff (- (-> bollinger-band last :upper-band)
                       (-> bollinger-band last :lower-band))
        more-than-any-wide? (some (fn [inp] (> latest-diff (:difference inp))) most-wide)]

    (if more-than-any-wide?

      ;; B iii RSI Divergence
      (let [OVER_BOUGHT 80
            OVER_SOLD 20

            rsi-list (confirming/relative-strength-index 14 tick-list)


            ;; i. price makes a higher high and
            ;; TODO
            ;; bollinger band with is wider than the previous wide AND
            ;; price makes a: higher high price AND
            ;; rsi makes a: lower high (abouve teh overbought line) AND
            ;; bar should close underneath the prior 3 bars
            higher-highPRICE? (if (empty? peaks)
                                false
                                (> (-> bollinger-band last :last-trade-price)
                                   (-> peaks last :last-trade-price)))

            _ (info "peaks /" peaks)
            _ (info "rsi-list /" rsi-list)
            _ (info "lhs /" (-> rsi-list last :rsi))
            _ (info "rhs /" (->> rsi-list
                                 (filter #(= (:last-trade-time %)
                                             (-> peaks last :last-trade-time)))))


            ;; ii. rsi devergence makes a lower high
            lower-highRSI? (if (or (empty? peaks)
                                   (some #(nil? (:last-trade-time %)) rsi-list))
                             false
                             (let [matching-rsi (->> rsi-list
                                                     (filter #(= (:last-trade-time %)
                                                                 (-> peaks last :last-trade-time)))
                                                     first
                                                     :rsi)]
                               (and matching-rsi
                                    (< (-> rsi-list last :rsi)
                                       matching-rsi))))

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
                                    (empty? rsi-list))
                              false
                              (let [matching-rsi (->> rsi-list
                                                      (filter #(= (:last-trade-time %)
                                                                  (:last-trade-time (first valleys))))
                                                      first
                                                      :rsi)]

                                (and matching-rsi
                                     (> (-> rsi-list last :rsi)
                                        matching-rsi))))

            divergence-oversold? (< (-> rsi-list last :rsi)
                                    OVER_SOLD)]

        (if (and higher-highPRICE? lower-highRSI? divergence-overbought?)

          (-> bollinger-band
              last
              (assoc :signals [{:signal :down
                                :why :bollinger-divergence-overbought}])
              ((partial bind-result item)))

          (if (and lower-highPRICE? higher-highRSI? divergence-oversold?)

            (-> bollinger-band
                last
                (assoc :signals [{:signal :up
                                  :why :bollinger-divergence-oversold}])
                ((partial bind-result item)))

            (-> bollinger-band last ((partial bind-result item))))))

      (-> bollinger-band last ((partial bind-result item))))))

(defn valley-inside-lower+price-below-lower? [bollinger-band valleys]

  (and (< (-> bollinger-band last :last-trade-price)
          (-> bollinger-band last :lower-band))

       ;; NOTE Maybe we want to bound the range backwards, by 5 - 7 ticks.
       (> (-> valleys last :last-trade-price)
          (->> bollinger-band
               (filter #(= (:last-trade-time %)
                           (-> valleys last :last-trade-time)))
               first
               :lower-band))))

(defn peak-inside-upper+price-abouve-upper? [bollinger-band peaks]
  (and (> (-> bollinger-band last :last-trade-price)
          (-> bollinger-band last :upper-band))
       (< (-> peaks last :last-trade-price)
          (->> bollinger-band
               (filter #(= (:last-trade-time %)
                           (-> peaks last :last-trade-time)))
               first
               :upper-band))))

(defn analysis-overbought-oversold [{:keys [bollinger-band] :as item} peaks-valleys]

  (let [peaks (:peak (group-by :signal peaks-valleys))
        valleys (:valley (group-by :signal peaks-valleys))]

    (cond
      (valley-inside-lower+price-below-lower? bollinger-band valleys)
      (-> bollinger-band
          last
          (assoc :signals [{:signal :down
                            :why :bollinger-close-abouve}])
          ((partial bind-result item)))

      (peak-inside-upper+price-abouve-upper? bollinger-band peaks)
      (-> bollinger-band
          last
          (assoc :signals [{:signal :up
                            :why :bollinger-close-below}])
          ((partial bind-result item)))

      :else (-> bollinger-band last ((partial bind-result item))))))

(defn analysis-up-market+bollinger-band-squeeze [{:keys [bollinger-band] :as item} valleys]

  (if (valley-inside-lower+price-below-lower? bollinger-band valleys)
    (-> bollinger-band
        last
        (assoc :signals [{:signal :down
                          :why :bollinger-close-abouve}])
        ((partial bind-result item)))
    (-> bollinger-band last ((partial bind-result item)))))

(defn analysis-down-market+bollinger-band-squeeze [{:keys [bollinger-band] :as item} peaks]

  (if (peak-inside-upper+price-abouve-upper? bollinger-band peaks)
    (-> bollinger-band
        last
        (assoc :signals [{:signal :up
                          :why :bollinger-close-below}])
        ((partial bind-result item)))
    (-> bollinger-band last ((partial bind-result item)))))

(defn bollinger-band
  "Implementing signals for analysis/bollinger-band. Taken from these videos:
     i. http://www.youtube.com/watch?v=tkwUOUZQZ3s
     ii. http://www.youtube.com/watch?v=7PY4XxQWVfM


      A. when the band width is very low, can indicate that price will breakout sooner than later;

      i. MA is in an UP or DOWN market
      ii. check for narrow bollinger band width; less than the most previous narrow band width
      iii. close is outside of band, and previous swing high/low is inside the band


      B. When the band width is very high (high volatility), can mean that the trend is ending soon
         and can i. change direction or ii. consolidate

      i. MA is in a sideways (choppy) market -> check if many closes that are abouve or below the bollinger band
      ii. check for a wide bollinger band width ; greater than the most previous wide band
      iii. RSI Divergence;
        i. price makes a higher high and
        ii. rsi devergence makes a lower high and
        iii. divergence should happen abouve the overbought line
      iv. entry signal -> check if one of next 3 closes are underneath the priors (or are in the opposite direction)

   ** This function assumes the latest tick is on the right"
  [tick-window {:keys [tick-list bollinger-band] :as item}]

  ;; TODO up-market definition includes an MA that is abouve the tick line
  ;; TODO - determine how far back to look (defaults to 5 ticks) to decide on an UP or DOWN market
  ;; TODO - does tick price fluctuate abouve and below the MA
  ;; TODO - B iv. entry signal -> check if one of next 3 closes are underneath the priors (or are in the opposite direction)

  ;; TODO for an up market, also consider if
  ;;   i. latest tick swings abouve the MA (previous tick was below) AND
  ;;   ii. latest tick is abouve the most recent peak

  ;; TODO any market + a BB squeeze
  ;; Look for a failed attempt to take out a prior high after a BB squeeze breakout
  ;;   That breakout has failed to go abouve the previous breakout
  ;; Look for a close underneath the BB squeeze low

  ;; TODO Sideways market definition can include - MA croses abouve and below tick line

  ;; TODO Overbought - price (preferably an ask) lands abouve the upper band, then a close below
  ;;   - target exit price is the lower band

  ;; TODO Oversold - price (preferably a bid) lands below the lower band

  (let [;; Track widest & narrowest band over the last 'n' (3) ticks
        market-trend-by-ticks 5
        sorted-bands (sort-bollinger-band bollinger-band)
        most-narrow (take 2 sorted-bands)
        most-wide (take-last 2 sorted-bands)

        partitioned-list (->> (partition 2 1 tick-list)
                              (take-last market-trend-by-ticks))

        any-market? true
        up-market? (common/up-market? partitioned-list)
        down-market? (common/down-market? partitioned-list)
        sideways-market? (not (and up-market? down-market?))

        latest-diff (- (-> bollinger-band last :upper-band)
                       (-> bollinger-band last :lower-band))

        bollinger-band-squeeze? (some #(< latest-diff (:difference %)) most-narrow)

        ;; Find last 3 peaks and valleys
        peaks-valleys (common/find-peaks-valleys nil tick-list)
        peaks (:peak (group-by :signal peaks-valleys))
        valleys (:valley (group-by :signal peaks-valleys))
        payload (assoc item :result [])

        consolidate-signals (fn [bitem]
                              (let [result-list (:result bitem)
                                    latest-bband (-> bitem :bollinger-band last)
                                    conditionally-bind-signals #(if (not-empty %)
                                                                  (assoc latest-bband :signals %)
                                                                  latest-bband)]
                                (->> (map :signals result-list)
                                     (apply concat)
                                     conditionally-bind-signals)))]

    (cond-> payload
      ;; any-market? (analysis-rsi-divergence most-wide peaks-valleys)
      sideways-market? (analysis-overbought-oversold peaks-valleys)
      (and up-market? bollinger-band-squeeze?) (analysis-up-market+bollinger-band-squeeze valleys)
      (and down-market? bollinger-band-squeeze?) (analysis-down-market+bollinger-band-squeeze peaks)
      :always (consolidate-signals))))
