(ns com.interrupt.ibgateway.component.tx-pipeline
  (:require [clojure.core.async :as async :refer [<! go-loop]]
            [com.interrupt.edgar.core.analysis.lagging :as alag]
            [net.cgrand.xforms :as x]))

(defn avg
  [xs]
  (/ (apply + xs) (count xs)))

(defn different-windows
  "SMA on n = 5, EMA on n = {3, 5}."
  [xs]
  (let [input-ch (async/to-chan xs)
        ch-5 (async/chan 10 (x/partition 5 1))
        ch-5-mult (async/mult ch-5)
        sma-ch (async/chan 10 (map avg))
        ;; Need n = 3 to match up w/ n = 5, so take n = 5 partitions and drop
        ;; first 2 (oldest) elements
        ch-3 (async/chan 10 (map #(drop 2 %)))
        ema-slow-ch (async/chan 10 (map alag/ema))
        ema-fast-ch (async/chan 10 (map alag/ema))]
    (async/pipe input-ch ch-5)
    (doto ch-5-mult
      (async/tap sma-ch)
      (async/tap ema-slow-ch)
      (async/tap ch-3))
    (async/pipe ch-3 ema-fast-ch)

    (go-loop []
      (let [sma (<! sma-ch)
            ema-slow (<! ema-slow-ch)
            ema-fast (<! ema-fast-ch)]
        (when (and sma ema-slow ema-fast)
          (println [sma ema-slow ema-fast])
          (recur))))))
