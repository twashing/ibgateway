(ns com.interrupt.edgar.core.analysis.confirming
  (:require [com.interrupt.edgar.core.analysis.common
             :refer [time-increases-left-to-right?]]))

(defn obv
  [prices volumes]
  (->> (map vector (rest prices) (rest volumes))
       (reduce (fn [[prev-p prev-v] [p v]]
                 [p (case (compare prev-p p)
                      -1 (+ prev-v v)
                      0 prev-v
                      1 (- prev-v v))])
               [(first prices) 0])
       second))

(defn obv-ticks
  [ticks]
  {:pre [(time-increases-left-to-right? ticks)]}
  (let [prices (map :last-trade-price ticks)
        volumes (map :total-volume ticks)]
    (-> (last ticks)
        (assoc :obv (obv prices volumes)))))

(defn on-balance-volume
  "On Balance Volume (OBV) measures buying and selling pressure as a cumulative indicator that i) adds volume on up days and ii) subtracts volume on down days. We'll look for divergences between OBV and price to predict price movements or use OBV to confirm price trends.

   The On Balance Volume (OBV) line is a running total of positive and negative volume. i) A tick's volume is positive when the close is above the prior close. Or ii) a tick's volume is negative when the close is below the prior close.

    If closing price is above prior:
      Current OBV = Previous OBV + Current Volume

    If closing price is below prior:
      Current OBV = Previous OBV  -  Current Volume

    If closing price equals prior:
      Current OBV = Previous OBV (no change)

    ** The first OBV value is the first period's positive/negative volume.
    ** This function assumes the latest tick is on the right"
  [tick-list]
  {:pre [(time-increases-left-to-right? tick-list)]}

  ;; accumulate OBV on historical tick-list
  (let [obv-list (reduce (fn [acc ech]

                           (if-let [prev-obv (:obv (last acc))]    ;; handling case where last will not have an OBV

                             ;; normal case
                             (let [current-price (:last-trade-price (last ech))
                                   prev-price (:last-trade-price (-> ech butlast last))
                                   current-volume (:total-volume (last ech))

                                   obv (if (= current-price prev-price)
                                         prev-obv
                                         (if (> current-price prev-price)
                                           (+ prev-obv current-volume)
                                           (- prev-obv current-volume)))]

                               (concat acc (list {:obv obv
                                                  :total-volume (:total-volume (last ech))
                                                  :last-trade-price (:last-trade-price (last ech))
                                                  :last-trade-time (:last-trade-time (last ech))})))

                             ;; otherwise we seed the list with the last entry
                             (concat acc (list {:obv (:total-volume (last ech))
                                                :total-volume (:total-volume (last ech))
                                                :last-trade-price (:last-trade-price (last ech))
                                                :last-trade-time (:last-trade-time (last ech))}))))
                         []
                         (partition 2 1 tick-list))]

    obv-list))

(defn rsi
  [prices]
  (let [rs (->> (rest prices)
                (reduce (fn [[prev-p gain loss] p]
                          (let [diff (- p prev-p)]
                            (cons p (cond
                                      (pos? diff) [(+ gain diff) loss]
                                      (neg? diff) [gain (- loss diff)]
                                      :else [gain loss]))))
                        [(first prices) 0 0])
                rest
                (apply /))]
    (- 100 (/ 100 (inc rs)))))

(defn rsi-ticks
  [ticks]
  {:pre [(time-increases-left-to-right? ticks)]}
  (let [prices (map :last-trade-price ticks)]
    (-> (last ticks)
        (assoc :rsi (rsi prices)))))

(defn relative-strength-index
  "The Relative Strength Index (RSI) is a momentum oscillator that measures the speed and change of price movements.
   It oscillates between zero and 100.

   If no 'tick-window' is given, it defaults to 14

   ** This function assumes the latest tick is on the right"
  [tick-window tick-list]
  {:pre [(time-increases-left-to-right? tick-list)]}

  (let [twindow (if tick-window
                  tick-window 7 ; 14 35% of 40
                  )
        window-list (partition twindow 1 tick-list)]

    (reduce (fn [rslt ech]

              ;; The tick-list will be a population of tick-window (default of 14)
              (let [pass-one (reduce (fn [rslt ech]

                                       (let [fst (:last-trade-price (last ech))
                                             snd (:last-trade-price (-> ech butlast last))

                                             up? (> fst snd)
                                             down? (< fst snd)
                                             sideways? (and (not up?) (not down?))]

                                         (if (or up? down?)
                                           (if up?
                                             (conj rslt (assoc (last ech) :signal :up))
                                             (conj rslt (assoc (last ech) :signal :down)))
                                           (conj rslt (assoc (last ech) :signal :sideways)))))
                                     []
                                     (partition 2 1 tick-list))


                    up-list (:up (group-by :signal pass-one))
                    down-list (:down (group-by :signal pass-one))

                    avg-gains (/ (apply + (map :last-trade-price up-list))
                                 tick-window)

                    avg-losses (/ (apply + (map :last-trade-price down-list))
                                  tick-window)

                    rs (if-not (= 0 avg-losses)
                         (/ avg-gains avg-losses)
                         0)

                    rsi (- 100 (/ 100 (+ 1 rs)))]

                (concat rslt (list {:last-trade-time (:last-trade-time (last tick-list))
                                    :last-trade-price (:last-trade-price (last tick-list))
                                    :rs rs
                                    :rsi rsi}))))
            []
            window-list)))
