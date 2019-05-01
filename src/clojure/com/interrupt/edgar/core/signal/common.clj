(ns com.interrupt.edgar.core.signal.common
  (:require [clojure.tools.trace :refer [trace]]))


(defn seq-all-exists? [s]
  ((comp not empty? (partial remove nil?)) s))

(defn find-peaks-valleys
  "** This function assumes the latest tick is on the right"
  [options tick-list]

  (let [{input-key :input
         :or {input-key :last-trade-price}} options]

    (reduce (fn [rslt ech]
              (let [fst (input-key (first ech))
                    snd (input-key (second ech))
                    thd (input-key (nth ech 2))

                    valley? (and (and (-> fst nil? not) (-> snd nil? not) (-> thd nil? not))
                                 (> fst snd)
                                 (< snd thd))
                    peak? (and (and (-> fst nil? not) (-> snd nil? not) (-> thd nil? not))
                               (< fst snd)
                               (> snd thd))]

                (if (or valley? peak?)
                  (if peak?
                    (concat rslt (-> (second ech)
                                     (assoc :signal :peak)
                                     list))
                    (concat rslt (-> (second ech)
                                     (assoc :signal :valley)
                                     list)))
                  rslt)))
            []
            (partition 3 1 tick-list))))

(defn up-market?
  "** This function assumes the latest tick is on the right"

  ([partitioned-list] (up-market? :last-trade-price partitioned-list))
  ([k partitioned-list]
   (every? (fn [inp]
             (<= (k (first inp))
                 (k (second inp))))
           partitioned-list)))

(defn down-market?
  "** This function assumes the latest tick is on the right"

  ([partitioned-list] (down-market? :last-trade-price partitioned-list))
  ([k partitioned-list]
   (every? (fn [inp]
             (>= (k (first inp))
                 (k (second inp))))
           partitioned-list)))

(defn divergence-up?
  "** This function assumes the latest tick is on the right"
  [options ech-list price-peaks-valleys macd-peaks-valleys]

  (if (not-every? seq-all-exists? [price-peaks-valleys ech-list macd-peaks-valleys])
    false
    (let [{input-top :input-top
           input-bottom :input-bottom
           :or {input-top :last-trade-price
                input-bottom :last-trade-macd}} options

          price-peaks (:peak (group-by :signal price-peaks-valleys))
          macd-peaks (:peak (group-by :signal macd-peaks-valleys))

          last-tick (last ech-list)
          last-price-peak (last price-peaks)
          last-macd-peak (last macd-peaks)

          price-higher-high? (and last-tick last-price-peak
                                  (> (input-top last-tick) (input-top last-price-peak)))
          macd-lower-high? (and last-tick last-macd-peak
                                (< (input-bottom last-tick) (input-bottom last-macd-peak)))]

      (and price-higher-high? macd-lower-high?))))

(defn divergence-down?
  "** This function assumes the latest tick is on the right"
  [options ech-list price-peaks-valleys macd-peaks-valleys]

  (if (not-every? seq-all-exists? [price-peaks-valleys ech-list macd-peaks-valleys])
    false
    (let [{input-top :input-top
           input-bottom :input-bottom
           :or {input-top :last-trade-price
                input-bottom :last-trade-macd}} options

          price-peaks (:peak (group-by :signal price-peaks-valleys))
          macd-peaks (:peak (group-by :signal macd-peaks-valleys))

          ;; TODO also ensure that the last-tick is on a downward trend
          ;; TODO also ensure that macd crosses over the trigger line
          ;; TODO macd histogram i. making a lower high, and ii. goes negative (a down indicator)
          last-tick (last ech-list)
          last-price-peak (last price-peaks)
          last-macd-peak (last macd-peaks)

          price-lower-high? (and last-tick last-price-peak
                                 (< (input-top last-tick) (input-top last-price-peak)))
          macd-higher-high? (and last-tick last-macd-peak
                                 (> (input-bottom last-tick) (input-bottom last-macd-peak)))]

      (and price-lower-high? macd-higher-high?))))
