(ns com.interrupt.edgar.core.signal.common)


(defn find-peaks-valleys
  "** This function assumes the latest tick is on the right**"
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
  "** This function assumes the latest tick is on the right**"
  [period partitioned-list]
  (every? (fn [inp]
            (> (:last-trade-price (first inp))
               (:last-trade-price (second inp))))
          (take period partitioned-list)))

(defn down-market?
  "** This function assumes the latest tick is on the right**"
  [period partitioned-list]
  (every? (fn [inp]
            (< (:last-trade-price (first inp))
               (:last-trade-price (second inp))))
          (take period partitioned-list)))

(defn divergence-up?
  "** This function assumes the latest tick is on the right**"
  [options ech-list price-peaks-valleys macd-peaks-valleys]

  (let [last-ech (last ech-list)
        last-price (last price-peaks-valleys)
        last-macd (last macd-peaks-valleys)

        {input-top :input-top
         input-bottom :input-bottom
         :or {input-top :last-trade-price
              input-bottom :last-trade-macd}} options


        both-exist-price? (and (not (empty? (remove nil? ech-list)))
                               (not (empty? (remove nil? price-peaks-valleys))))
        price-higher-high? (and (-> (input-top last-ech) nil? not)
                                (-> (input-top last-price) nil? not)

                                both-exist-price?
                                (> (input-top last-ech) (input-top last-price)))

        both-exist-macd? (and (not (empty? (remove nil? ech-list)))
                              (not (empty? (remove nil? macd-peaks-valleys))))
        macd-lower-high? (and (-> (input-bottom last-ech) nil? not)
                              (-> (input-bottom last-macd) nil? not)

                              both-exist-macd?
                              (< (input-bottom last-ech) (input-bottom last-macd)))]

    (and price-higher-high? macd-lower-high?)))

(defn divergence-down?
  "** This function assumes the latest tick is on the right**"
  [options ech-list price-peaks-valleys macd-peaks-valleys]

  (let [last-ech (last ech-list)
        last-price (last price-peaks-valleys)
        last-macd (last macd-peaks-valleys)

        {input-top :input-top
         input-bottom :input-bottom
         :or {input-top :last-trade-price
              input-bottom :last-trade-macd}} options


        both-exist-price? (and (not (empty? (remove nil? ech-list)))
                               (not (empty? (remove nil? price-peaks-valleys))))
        price-lower-high? (and (-> (input-top last-ech) nil? not)
                               (-> (input-top last-price) nil? not)

                               both-exist-price?
                               (< (input-top last-ech) (input-top last-price)))

        both-exist-macd? (and (not (empty? (remove nil? ech-list)))
                              (not (empty? (remove nil? macd-peaks-valleys))))
        macd-higher-high? (and (-> (input-top last-ech) nil? not)
                               (-> (input-top last-price) nil? not)

                               both-exist-macd?
                               (> (input-bottom last-ech) (input-bottom last-macd)))]

    (and price-lower-high? macd-higher-high?)))
