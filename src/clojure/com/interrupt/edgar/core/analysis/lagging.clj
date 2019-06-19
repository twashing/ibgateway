(ns com.interrupt.edgar.core.analysis.lagging
  (:require [clojure.tools.logging :refer [debug info]]
            [clojure.tools.trace :refer [trace]]
            [com.interrupt.edgar.core.analysis.common :refer [time-increases-left-to-right?]]
            [com.interrupt.edgar.math :as math]
            [clojure.data.csv :as csv]))


(defn simple-moving-average
  "Takes the ticks, and moves back as far as the tick window will take it.

   Returns a simple average value for the input list

   Options are:
   :input - input key function will look for (defaults to :last-trade-price)
   :output - output key function will emit (defaults to :last-trade-price-average)
   :etal - other keys to emit in each result map

   ** This function assumes the latest tick is on the right"
  [options ticks]
  {:pre [(not-empty ticks)
         (time-increases-left-to-right? ticks)]}

  (let [{:keys [input output etal]
         :or {input :last-trade-price
              output :last-trade-price-average
              etal [:last-trade-price :last-trade-time :total-volume :uuid]}} options
        xs (map input ticks)]
    (-> (last ticks)
        (select-keys etal)
        (assoc output (math/mean xs) :population ticks))))

(defn ema
  "Exponential moving average (EMA)."
  [xs]
  (let [n (count xs)
        k (/ 2 (inc n))]
    (reduce (fn [acc x] (+ (* k x) (* (- 1 k) acc)))
            (math/mean xs)
            xs)))

(defn ema-ticks
  "EMA on ticks."
  [options ticks]
  {:pre [(not-empty ticks)
         (time-increases-left-to-right? ticks)]}
  (let [{:keys [input output etal]
         :or {input :last-trade-price
              output :last-trade-price-exponential
              etal [:last-trade-price :last-trade-time :uuid]}} options
        xs (map input ticks)]
    (-> (last ticks)
        (select-keys etal)
        (assoc output (ema xs) :population ticks))))

(defn exponential-moving-average
  "From a simple moving average, generates an accompanying exponential moving average list.

     EMA = price(today) * k + EMA(yesterday) * (1 - k)
     k = 2 / N + 1
     N = number of days

   Returns a list, equal in length to the tick-list, but only with slots filled,
   where preceding tick-list allows.

   Options are:
   :input - input key function will look for (defaults to :last-trade-price)
   :output - output key function will emit (defaults to :last-trade-price-exponential)
   :etal - other keys to emit in each result map

   ** This function assumes the latest tick is on the right"

  [options tick-window sma-list]
  {:pre [(time-increases-left-to-right? sma-list)]}

  ;; 1. calculate 'k'
  ;; k = 2 / N + 1
  ;; N = number of days
  (let [k (/ 2 (+ tick-window 1))
        {input-key :input
         output-key :output
         etal-keys :etal
         :or {input-key :last-trade-price
              output-key :last-trade-price-exponential
              etal-keys [:last-trade-price :last-trade-time :last-trade-price-average
                         :total-volume :population :uuid]}} options]

    ;; 2. get the simple-moving-average for a given tick - 1
    (reduce (fn [rslt ech]

              ;; 3. calculate the EMA ( for the most recent tick, EMA(yesterday) = MA(yesterday) )
              (let [;; price (today)
                    ltprice (input-key ech)

                    ;; EMA(yesterday)
                    ema-last (if (output-key (last rslt))
                               (output-key (last rslt))
                               (input-key ech))

                    ;; ** EMA now = price(today) * k + EMA(yesterday) * (1 - k)
                    ;; ema-now (+ (* k ltprice)
                    ;;            (* ema-last (- 1 k)))

                    ;; EMA(now) = {Close - EMA(previous day)} x multiplier + EMA(previous day)
                    ema-now (->> (- ltprice ema-last)
                                 (* k)
                                 (+ ema-last))]

                (concat rslt

                        ;; Will produce a map of etal-keys, with associated values in ech
                        ;; and merge the output key to the map
                        (as-> etal-keys ek
                            (zipmap ek ((apply juxt etal-keys) ech))
                            (assoc ek output-key ema-now)
                            (list ek)))))
            []
            sma-list)))

(defn bollinger
  "Bollinger band [lower middle upper]."
  [xs]
  (let [avg (math/mean xs)
        sd (math/sd xs)]
    [(- avg (* 2 sd)) avg (+ avg (* 2 sd))]))

(defn bollinger-ticks
  [options ticks]
  {:pre [(not-empty ticks)
         (time-increases-left-to-right? ticks)]}
  (let [{:keys [input etal]
         :or {input :last-trade-price
              etal [:last-trade-price :last-trade-time :uuid]}} options
        xs (map input ticks)
        [lower middle upper] (bollinger xs)]
    (-> (last ticks)
        (select-keys etal)
        (assoc :last-trade-price-average middle
               :upper-band upper
               :lower-band lower
               :population ticks))))

(defn average [list-sum list-count]
  (/ list-sum list-count))

(defn bollinger-band
  "From a tick-list, generates an accompanying list with upper-band and lower-band

     Upper Band: K times an N-period standard deviation above the moving average (MA + Kσ)
     Lower Band: K times an N-period standard deviation below the moving average (MA − Kσ)
     K: number of standard deviations
     N: period, or tick-window we are looking at

   Returns a list, equal in length to the tick-list, but only with slots filled,
   where preceding tick-list allows.

   ** This function assumes the latest tick is on the right"
  [tick-window_UNUSED sma-list]
  {:pre [(time-increases-left-to-right? sma-list)]}

  ;; At each step, the Standard Deviation will be:
  ;; the square root of the variance (average of the squared differences from the Mean)
  (reduce (fn [rslt ech]

            (let [ma (:last-trade-price-average ech)
                  mean (average (reduce (fn [rslt ech]
                                          (+ (:last-trade-price ech)
                                             rslt))
                                        0
                                        (:population ech))
                                (count (:population ech)))

                  ;; Then for each number: subtract the mean and square the result (the squared difference)
                  sq-diff-list (map (fn [ech]
                                      (let [diff (- (:last-trade-price ech) mean)]
                                        (* diff diff)))
                                    (:population ech))

                  variance (/ (reduce + sq-diff-list) (count (:population ech)))
                  standard-deviation (. Math sqrt variance)
                  etal-keys [:uuid :last-trade-time :last-trade-price :total-volume
                             :last-trade-price-average :last-trade-price-exponential]]

              (as-> etal-keys v
                (zipmap v (map #(% ech) etal-keys))
                (assoc v
                       :variance variance
                       :standard-deviation standard-deviation
                       :upper-band (+ ma (* 2 standard-deviation))
                       :lower-band (- ma (* 2 standard-deviation)))
                (list v)
                (concat rslt v))))
          []
          sma-list))
