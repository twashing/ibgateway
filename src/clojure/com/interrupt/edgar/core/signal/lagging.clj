(ns com.interrupt.edgar.core.signal.lagging
  (:require [clojure.core.match :refer [match]]
            [clojure.tools.logging :refer [info] :as log]
            [clojure.tools.trace :refer [trace]]
            [com.rpl.specter :refer :all]

            [com.interrupt.edgar.core.analysis.lagging :as analysis]
            [com.interrupt.edgar.core.analysis.confirming :as confirming]
            [com.interrupt.edgar.core.signal.common :as common]
            [com.interrupt.edgar.core.utils :refer [not-nil? opposite-sign]]
            [com.interrupt.edgar.math :refer [mean]]
            [com.interrupt.ibgateway.component.common :refer [exists?]]))


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

(defn analysis-bollinger-band-squeeze [{:keys [bollinger-band] :as item}]
  (assoc item :signals [{:signal :up
                         :why :bollinger-band-squeeze}
                        {:signal :down
                         :why :bollinger-band-squeeze}]))

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


;; A - Bollinger Band squeeze

;; TRY i - are difference of last 4 under 11% of the middle band
;; last-four-averages (->> sma-list
;;                         (take-last 4)
;;                         (map #(select-keys % [:last-trade-price-average])))
;;
;; last-four-differences (->> bollinger-band
;;                            (take-last 4)
;;                            (map #(select-keys % [:upper-band :lower-band]))
;;                            (map #(assoc % :difference (- (:upper-band %) (:lower-band %)))))
;;
;; difference-below-11-percent?
;; (->> (map #(merge (select-keys %1 [:last-trade-price-average])
;;                   (select-keys %2 [:upper-band :lower-band :difference]))
;;           last-four-averages last-four-differences)
;;
;;      (map #(assoc % :difference-to-average
;;                   (/ (:difference %) (:last-trade-price-average %))))
;;
;;      (map #(assoc % :difference-below-11-percent?
;;                   (< (:difference-to-average %) (:last-trade-price-average %)))))
(defn mean-lhs-mean-rhs_fn [bollinger-band]
  (->> bollinger-band
       (map #(select-keys % [:upper-band :lower-band]))
       (map #(assoc % :difference (- (:upper-band %) (:lower-band %))))
       (split-at 16)
       (map #(mean (map :difference %)))))

(defn last-4-differences-lowest?_fn [mean-rhs mean-lhs]
  (< (/ mean-rhs mean-lhs) 0.3))


;; B - Volume spike
(defn latest-volume-increase-abouve-20?_fn [partitioned-list]
  (->> (last partitioned-list)
       (map (comp double :total-volume))
       (apply #(/ %2 %1))
       ;; trace
       (#(< 1.015 %))  ;; 1.015 seems to be the low threshold
       ;;trace
       ))


;; C - %B = (Current Price - Lower Band) / (Upper Band - Lower Band)
(defn percent-b_fn [bollinger-band]
  (-> bollinger-band
      last
      (select-keys [:upper-band :lower-band :last-trade-price])
      ((fn [{:keys [upper-band lower-band last-trade-price]}]
         (/ (- last-trade-price lower-band)
            (- upper-band lower-band))))
      ;; trace
      ))


;; D - Peaks / troughs
(defn block-closed? [{carry :carry}]
  (= (-> [:price-change-start :price-change-end :count] sort)
     (-> carry keys sort)))

(defn conditionally-track-block [acc a]

  (let [price-change (:price-change a)
        previous (-> acc last)
        carry (-> acc last :carry)
        cnt (-> acc last :carry :count)
        cnt-threshold 6

        open-block (fn [a price-change]
                     (assoc a :carry {:price-change-start price-change
                                      :count 1}))

        close-block (fn [this carry price-change]
                      (->> (assoc carry :price-change-end price-change)
                           (assoc this :carry)))

        conditionally-close-block (fn [{{cnt :count
                                         previous-price-change :price-change-start
                                         :as carry}
                                        :carry}
                                       this price-change]

                                    (if (and (opposite-sign previous-price-change price-change)
                                             (< cnt cnt-threshold))

                                      (close-block this carry price-change)
                                      (open-block this price-change)))

        update-count (fn [{carry :carry} this]
                       (->> (update-in carry [:count] inc)
                            (assoc this :carry)))

        conditionally-update-count (fn [{{cnt :count end :price-change-end} :carry :as previous}
                                        this price-change]

                                     (match [(< cnt cnt-threshold)
                                             (not-nil? end)]

                                            [true false] (update-count previous this)
                                            [_ _] this
                                            ;; [true true] this
                                            ;; [false _] (open-block this price-change)
                                            ))]

    (match [(not-nil? price-change)
            (not-nil? carry)
            (block-closed? previous)]

           [false false _] a

           [true false _] (open-block a price-change)
           [true true false] (conditionally-close-block previous a price-change)
           [true true true] a

           [false true false] (conditionally-update-count previous a price-change)
           [false true true] a)))

(defn tag-peak-or-trough [{{start :price-change-start
                            end :price-change-end} :carry :as a}]

  (cond
    (and (pos? start) (neg? end)) (update-in a [:carry] assoc :tag :peak)
    (and (neg? start) (pos? end)) (update-in a [:carry] assoc :tag :trough)
    :else a))

(defn tag-peaks-troughs [a]
  (if (block-closed? a)
    (tag-peak-or-trough a)
    (dissoc a :carry)))

(defn peaks-troughs_fn [bollinger-band]
  (->> bollinger-band
       (partition 2 1)
       ;; last
       (map (fn [[{left-price :last-trade-price left-sd :standard-deviation :as left}
                 {right-price :last-trade-price right-sd :standard-deviation :as right}]]

              (let [price-diff (- right-price left-price)]
                (if (> (java.lang.Math/abs price-diff) (* 0.25 left-sd))
                  (assoc right :price-change price-diff)
                  right))))

       (reduce (fn [acc a]
                 (->> (conditionally-track-block acc a)
                      (conj acc)))
               [])

       (map tag-peaks-troughs)))


;; E - Fibonacci lines

;;   [~] Howto determine start / end points
;;     - moving peaks troughs over the last 20, 40, 100, 200
;;     - dynamically query for Fibonacci retracement windows

;;   [~] Howto dynamically select partition points... Or do we just need to track by the last 120, 240 ticks
;;     - pick an extended window, and have working windows in the interim

;;   [x] Use seqs as a stand in for channels, for development
;;     - don't see a way to do this; try increasing parallelism factor, reducing consum delay

(def fibonacci-levels
  {:level-zero 0
   :level-point-two 0.238
   :level-point-three 0.382
   :level-point-five 0.5
   :level-point-six 0.618
   :level-one 1})

(defn apply-fibonacci-levels [[{price-left :last-trade-price
                                time-left :last-trade-time
                                uuid-left :uuid
                                {lhs :price-change-end} :carry :as a}

                               {price-right :last-trade-price
                                time-right :last-trade-time
                                uuid-right :uuid
                                {rhs :price-change-start} :carry :as b}]]

  (let [difference (java.lang.Math/abs (- price-right price-left))
        direction-positive? (pos? (- price-right price-left))

        apply-levels (fn [[level-key level-percentage]]
                       (let [change (* level-percentage difference)
                             direction-fn (if direction-positive? + -)]
                         {level-key {:percentage level-percentage
                                     :change change
                                     :amount (direction-fn price-right change)}}))]

    (->> (select-keys fibonacci-levels [:level-point-two :level-point-three :level-point-five :level-point-six])
         seq
         (map apply-levels)
         (concat [{:time-left time-left
                   :uuid-left uuid-left
                   :time-right time-right
                   :uuid-right uuid-right}]))))

(defn overlay-fibonacci-at-peaks-and-troughs [bollinger-with-peaks-troughs]

  ;; find :carry(s)
  (->> (filter :carry bollinger-with-peaks-troughs)

       ;; partition 2 at a time -> fibonacci start / end points
       (partition 2 1)

       ;; apply fibanacci levels at each end
       (map apply-fibonacci-levels)

       (map (partial apply merge))))

(defn bollinger-with-peaks-troughs-fibonacci_fn [peaks-troughs fibonacci-at-peaks-and-troughs]
  (-> (map (fn [{uuid :uuid :as pt}]

             (if-let [fibonacci (-> (filter (fn [{fuuid :uuid-right}]
                                              (= uuid fuuid))
                                            fibonacci-at-peaks-and-troughs)
                                    first)]
               (assoc pt :fibonacci fibonacci)
               pt))
           peaks-troughs)
      last
      ;; trace
      ))

(defn analysis-day-trading-strategy-bollinger-bands-squeeze [{:keys [bollinger-band] :as item} partitioned-list]

  (let [;; A - Bollinger Band squeeze
        [mean-lhs mean-rhs] (mean-lhs-mean-rhs_fn bollinger-band)
        last-4-differences-lowest? (last-4-differences-lowest?_fn mean-rhs mean-lhs)


        ;; B - Volume spike
        latest-volume-increase-abouve-20? (latest-volume-increase-abouve-20?_fn partitioned-list)


        ;; C - %B = (Current Price - Lower Band) / (Upper Band - Lower Band)
        percent-b (percent-b_fn bollinger-band)


        ;; D - Peaks / troughs
        peaks-troughs (peaks-troughs_fn bollinger-band)
        fibonacci-at-peaks-and-troughs (overlay-fibonacci-at-peaks-and-troughs peaks-troughs)


        ;; Overlay fibanacci calculations over original list
        bollinger-with-peaks-troughs-fibonacci (bollinger-with-peaks-troughs-fibonacci_fn peaks-troughs fibonacci-at-peaks-and-troughs)


        last-bollinger (last bollinger-band)]

    (cond-> last-bollinger

      (when-not (:signals last-bollinger)) (assoc :signals [])

      last-4-differences-lowest? (update-in [:signals] conj {:signal :either
                                                             :why :bollinger-band-squeeze})

      latest-volume-increase-abouve-20? (update-in [:signals] conj {:signal :either
                                                                    :why :volume-spike})

      ;; (> percent-b 0.8) (update-in [:signals] conj {:signal :down
      ;;                                               :why :percent-b-abouve-80})
      ;;
      ;; (< percent-b 0.2) (update-in [:signals] conj {:signal :up
      ;;                                               :why :percent-b-below-20})

      (> percent-b 0.5) (update-in [:signals] conj {:signal :up
                                                    :why :percent-b-abouve-50})

      (< percent-b 0.5) (update-in [:signals] conj {:signal :down
                                                    :why :percent-b-below-50})

      (:fibonacci bollinger-with-peaks-troughs-fibonacci) (update-in [:signals] conj
                                                                     (-> (select-keys bollinger-with-peaks-troughs-fibonacci [:fibonacci :carry])
                                                                         (assoc :signal :fibonacci)))

      :always ((partial bind-result item)))))

(defn bollinger-band
  "** This function assumes the latest tick is on the right"
  [tick-window {:keys [tick-list sma-list bollinger-band] :as item}]

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

      ;; TODO > Implementing signals for analysis/bollinger-band. Taken from these videos
      ;; https://www.youtube.com/watch?v=tkwUOUZQZ3s
      ;; https://www.youtube.com/watch?v=7PY4XxQWVfM
      ;;
      ;;
      ;; > A. when the band width is very low, can indicate that price will breakout sooner than later;
      ;;
      ;; i. MA is in an UP or DOWN market
      ;; ii. check for narrow bollinger band width; less than the most previous narrow band width
      ;; iii. close is outside of band, and previous swing high/low is inside the band
      ;;
      ;;
      ;; > B. When the band width is very high (high volatility), can mean that the trend is ending soon
      ;; and can i. change direction or ii. consolidate
      ;;
      ;; i. MA is in a sideways (choppy) market -> check if many closes that are abouve or below the bollinger band
      ;; ii. check for a wide bollinger band width ; greater than the most previous wide band
      ;; iii. RSI Divergence;
      ;; i. price makes a higher high and
      ;; ii. rsi devergence makes a lower high and
      ;; iii. divergence should happen abouve the overbought line
      ;; iv. entry signal -> check if one of next 3 closes are underneath the priors (or are in the opposite direction)
      ;;
      ;;
      ;; > Summary
      ;;
      ;; i. look for a choppy market (big swings up and down)
      ;;   Simple MA is relatively flat and sideways
      ;;   price moves above and below the Simple MA
      ;; ii. look for a high reading in the bollinger band width
      ;; iii. look for RSI Divergence - i. price makes a higher high, ii. rsi makes a lower high
      ;; iv. (entry signal) a close beneath the prior 3 bars
      ;;
      ;; any-market? (analysis-rsi-divergence most-wide peaks-valleys)


      ;; TODO
      ;; sideways-market? (analysis-overbought-oversold peaks-valleys)
      ;; (and up-market? bollinger-band-squeeze?) (analysis-up-market+bollinger-band-squeeze valleys)
      ;; (and down-market? bollinger-band-squeeze?) (analysis-down-market+bollinger-band-squeeze peaks)


      ;; TODO > Day Trading Strategy Bollinger Bands Squeeze
      ;; https://www.youtube.com/watch?v=E2h-LLIC6yc
      ;;

      ;; > Entry (long @ 6m50s)
      ;;   i. BBs contract
      ;;   ii. Band width showing squeeze
      ;;   iii. %B > 0.5 before breakout
      ;;   iv. volume increase
      ;;   v. breaking resistance

      ;; > Exit
      ;;   Pulling away from Upper Band
      ;;   Initial stop below base
      ;;   Hard trailing stop - closes below 20 MA

      ;; > Entry (short @ 5m25s)
      ;;   i. BBs contract
      ;;   ii. Band width showing squeeze
      ;;   iii. %B < 0.5 before breakout
      ;;   iv. volume increase
      ;;   v. breaking support

      ;; > Exit
      ;;   Pulling away from Lower Band
      ;;   Initial stop abouve base
      ;;   Hard trailing stop - closes abouve 20 MA
      ;;
      ;; [ok] A) Measure squeeze over entire tick window (20 ticks)
      ;; [ok] BandWidth is considered
      ;;   narrow as it approaches the lows of range
      ;;   wide as it approaches the high end.
      ;;   last 4 ticks under 20% of the average of the last 20
      ;; [x] The width of the bands (last 4) is equal to 10% of the middle band.
      ;;
      ;; [ok] B) track volume increase (@ 2m45s , 6m05s)
      ;;   we want to see volume increase on breakout (or break down)
      ;;   [x] try an exponential moving average, cross over a simple moving average
      ;;   [ok] volume spike of over 1.5%
      ;;
      ;; [ok] C) bollinger-band %B analytic and chart.
      ;;   Where price is in reltion to the band
      ;;   80, 50, 20 - whether price is closer to upper or lower band.
      ;;   %B = (Current Price - Lower Band) / (Upper Band - Lower Band)
      ;;
      ;; TODO Is %B abouve / below the midpoint for... a while (same amount of time as BB squeeze)?
      ;; TODO place a stop abouve a high / below a low
      ;; TODO exit is when i. we pull away from the BB -> then ii. close abouve /below the 20 MA

      ;; [ok] Peaks & troughs

      ;; [~] Support & Resistance
      ;;  Fibonacci
      ;;    https://www.youtube.com/watch?v=Ytr__kKKYBAm
      ;;    https://www.youtube.com/watch?v=Kz6-8nQLRHM
      ;;    https://www.youtube.com/watch?v=878iiQ4yQOk
      ;;    https://www.stock-market-strategy.com/tutorials/fibonacci-retracements-extensions
      ;;    https://www.investopedia.com/terms/f/fibonacciretracement.asp
      ;;    https://www.investopedia.com/ask/answers/05/fibonacciretracement.asp
      ;;    https://stockcharts.com/school/doku.php?id=chart_school:chart_analysis:fibonacci_retracemen
      ;;  Pivot points
      ;;    https://www.fidelity.com/learning-center/trading-investing/technical-analysis/technical-indicator-guide/pivot-points-resistance-support
      ;;    https://www.investopedia.com/terms/p/pivotpoint.asp
      ;;    https://stockcharts.com/school/doku.php?id=chart_school:technical_indicators:pivot_points
      ;;  Derivatives
      ;;    https://stackoverflow.com/questions/8587047/support-resistance-algorithm-technical-analysis

      ;; TODO track supports over the last 20 ticks... highest / lowest price over the last 20 ticks
      ;;   resistance is most recent crests / troughs. https://www.youtube.com/watch?v=vJ-sRke6lzE&t=4m20s
      ;;   peaks / troughs are 1 - 4 ticks long?
      ;;   price move, between peaks / troughs are more than.. some threshold (fibonacci move?)
      ;;     https://www.investopedia.com/trading/support-and-resistance-basics/
      ;;   entry is when we take out the resistance
      ;;
      ;;   ! in isolation, support/resistance should be used in a sideways market (not a trend)
      true (analysis-day-trading-strategy-bollinger-bands-squeeze partitioned-list)
      :always (consolidate-signals))))


(comment

  ;; (require '[automata.core :refer [automaton advance] :as au])
  (require '[automata.refactor :refer [automata advance] :as a])

  ;; :bollinger-band-squeeze
  ;; :volume-spike
  ;; :percent-b-abouve-80
  ;; :percent-b-below-20
  ;; :fibonacci
  (def a (automata [:market-uptrend :bollinger-band-squeeze :percent-b-abouve-50 :fibonacci :volume-spike]))
  (-> a
      (advance :bollinger-band-squeeze)
      (advance :percent-b-abouve-50)
      (advance :fibonacci)
      (advance :volume-spike))


  (def b (automata [(a/or :bollinger-band-squeeze :volume-spike)
                    (a/or :bollinger-band-squeeze :volume-spike)
                    (a/or :percent-b-abouve-50 :percent-b-below-50) :fibonacci]))
  (-> b
      (advance :bollinger-band-squeeze)
      (advance :volume-spike)
      (advance :percent-b-abouve-50)
      (advance :fibonacci))

  (-> b
      (advance :volume-spike)
      (advance :bollinger-band-squeeze)
      (advance :percent-b-below-50)
      (advance :fibonacci))


  (def c (automata [(a/+ :bollinger-band-squeeze)
                    (a/+ :volume-spike)
                    (a/+ :percent-b-abouve-50)])))
