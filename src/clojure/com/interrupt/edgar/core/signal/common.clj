(ns com.interrupt.edgar.core.signal.common)

(defn peak?
  [x y z]
  (and (every? number? [x y z]) (> y x) (> y z)))

(defn valley?
  [x y z]
  (and (every? number? [x y z]) (< y x) (< y z)))

(defn find-peaks-valleys
  "Find peaks and valleys in ticks."
  [{:keys [input] :or {input :last-trade-price} :as options} ticks]
  (->> (partition 3 1 ticks)
       (reduce (fn [acc triple]
                 (let [[x y z] (map input triple)
                       middle (second triple)]
                   (cond
                     (peak? x y z) (conj acc (assoc middle :signal :peak))
                     (valley? x y z) (conj acc (assoc middle :signal :valley))
                     :else acc)))
               [])))

(defn market-trend?
  [{:keys [input] :or {input :last-trade-price} :as options} compare-fn n xs]
  (->> xs
       (take n)
       (every? #(apply compare-fn (map input %)))))

(def ^{:arglists '([period partitioned-list])}
  up-market? (partial market-trend? {} >))

(def ^{:arglists '([period partitioned-list])}
  down-market? (partial market-trend? {} <))

(defn both-exist?
  [xs ys]
  (and (not-every? nil? xs) (not-every? nil? ys)))

(defn lower-high?
  [input xs ys]
  (let [[vx vy] (map input [(last xs) (last ys)])]
    (and (both-exist? xs ys)
         (every? number? [vx vy])
         (< vx vy))))

(defn higher-high?
  [input xs ys]
  (let [[vx vy] (map input [(last xs) (last ys)])]
    (and (both-exist? xs ys)
         (every? number? [vx vy])
         (> vx vy))))

(defn divergence-up?
  "** This function assumes the latest tick is on the right**"
  [options echs price-peaks-valleys macd-peaks-valleys]
  (let [{:keys [input-top input-bottom]
         :or {input-top :last-trade-price
              input-bottom :last-trade-macd}} options]
    (and (higher-high? input-top echs price-peaks-valleys)
         (lower-high? input-bottom echs macd-peaks-valleys))))

(defn divergence-down?
  "** This function assumes the latest tick is on the right**"
  [options echs price-peaks-valleys macd-peaks-valleys]
  (let [{:keys [input-top input-bottom]
         :or {input-top :last-trade-price
              input-bottom :last-trade-macd}} options]
    (and (lower-high? input-top echs price-peaks-valleys)
         (higher-high? input-bottom echs macd-peaks-valleys))))
