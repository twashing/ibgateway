(ns com.interrupt.edgar.core.analysis.lagging
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as g]
            [clojure.spec.test.alpha :as stest]
            [clojure.test.check.generators :as gen]
            [com.interrupt.edgar.core.analysis.common :refer [time-increases-left-to-right?]]))


;; ==>

(s/def ::input-key #{:foo :bar})

(def a-tuple (g/bind (s/gen ::input-key)
                     (fn [x]
                       (g/tuple (g/return x)
                                (g/double* {:min 1.0 :infinite? false, :NaN? false})))))

(def b-tuple (g/tuple (g/return :b)
                      (g/large-integer)))

(def my-map (gen/let [at a-tuple
                      tt b-tuple]

              (->> [:input-key (first at)]
                   (concat at tt)
                   (apply array-map))))

(g/generate my-map)

;; ==>


(s/def ::input-key #{:last-trade-price :last-trade-price-average})
(s/def ::last-trade-time (set (range 10000 90000)))
(s/def ::tick-entry (s/keys :req [::input-key ::last-trade-time]))

(def tick-price-tuple (g/bind (s/gen ::input-key)
                              (fn [x]
                                (g/tuple (g/return x)
                                         (g/double* {:min 1.0 :infinite? false, :NaN? false})))))

(def tick-time-tuple (g/tuple (g/return :last-trade-time)
                              (g/large-integer)))

(def tick-event (gen/let [price-tuple tick-price-tuple
                          time-tuple tick-time-tuple]

                  (->> [:input-key (first price-tuple)]
                       (concat price-tuple time-tuple)
                       (apply array-map))))

(g/generate tick-event)

(s/def ::my-map (s/merge (s/keys :req [::input-key ::last-trade-time])
                         (s/map-of #{::input-key ::last-trade-time} #_::tick-price-tuple any?)))

(g/generate (s/gen ::my-map))

(s/def ::my-list (s/coll-of ::my-map))

(g/generate (s/gen ::my-list))

;; ==>


(defn average [list-sum list-count]
  (/ list-sum list-count))

(s/fdef average
        :args (s/and (s/cat :list-sum float? :list-count integer?)
                     #(not (zero? (:list-count %))))
        :ret number?)

(defn sum [tick-list input-key]
  (reduce #(let [ltprice (input-key %2)]
             (+ ltprice %1))
          0 tick-list))

#_(s/fdef sum
        :args (s/cat :tick-list (comp not empty?) :input-key keyword?)
        :ret number?)

(defn simple-moving-average
  "Takes the tick-list, and moves back as far as the tick window will take it.

   Returns a simple average value for the input list

   Options are:
   :input - input key function will look for (defaults to :last-trade-price)
   :output - output key function will emit (defaults to :last-trade-price-average)
   :etal - other keys to emit in each result map

   ** This function assumes the latest tick is on the right**"
  [options tick-list]
  {:pre [(time-increases-left-to-right? tick-list)]}

  (let [{input-key :input
         output-key :output
         etal-keys :etal
         :or {input-key :last-trade-price
              output-key :last-trade-price-average
              etal-keys [:last-trade-price :last-trade-time :uuid]}} options]

    (as-> tick-list v
      (sum v input-key)
      (average v (count tick-list))
      (-> etal-keys
          (zipmap (map #(% (last tick-list)) etal-keys))
          (assoc output-key v
                 :population tick-list)))))

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

   ** This function assumes the latest tick is on the right**"

  [options tick-window sma-list]
  {:pre [(time-increases-left-to-right? sma-list)]}

  ;; 1. calculate 'k'
  ;; k = 2 / N + 1
  ;; N = number of days
  (let [k (/ 2 (+ tick-window 1))
        {input-key :input
         output-key :output
         etal-keys :etal
         :or {input-key :last-trade-price-average
              output-key :last-trade-price-exponential
              etal-keys [:last-trade-price :last-trade-time :uuid]}} options]

    ;; 2. get the simple-moving-average for a given tick - 1
    (reduce (fn [rslt ech]

              ;; 3. calculate the EMA ( for the most recent tick, EMA(yesterday) = MA(yesterday) )
              (let [;; price(today)
                    ltprice (input-key ech)

                    ;; EMA(yesterday)
                    ema-last (if (output-key (last rslt))
                               (output-key (last rslt))
                               (input-key ech))

                    ;; ** EMA now = price(today) * k + EMA(yesterday) * (1 - k)
                    ema-now (+ (* k ltprice)
                               (* ema-last (- 1 k)))]

                (concat rslt

                        ;; will produce a map of etal-keys, with associated values in ech
                        ;; and merge the output key to the map
                        (-> etal-keys
                            (zipmap (map #(% ech) etal-keys))
                            (assoc output-key ema-now)
                            list))))
            []
            sma-list)))

(defn bollinger-band
  "From a tick-list, generates an accompanying list with upper-band and lower-band

     Upper Band: K times an N-period standard deviation above the moving average (MA + Kσ)
     Lower Band: K times an N-period standard deviation below the moving average (MA − Kσ)
     K: number of standard deviations
     N: period, or tick-window we are looking at

   Returns a list, equal in length to the tick-list, but only with slots filled,
   where preceding tick-list allows.

   ** This function assumes the latest tick is on the right**"
  [tick-window sma-list]
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
                  etal-keys [:last-trade-price :last-trade-time :uuid]]

              (as-> etal-keys v
                (zipmap v (map #(% ech) etal-keys))
                (assoc v
                       :upper-band (+ ma (* 2 standard-deviation))
                       :lower-band (- ma (* 2 standard-deviation)))
                (list v)
                (concat rslt v))))
          []
          sma-list))
