(ns com.interrupt.edgar.core.analysis.common)

(defn time-increases-left-to-right? [tick-list]
  (->> tick-list
       (partition 2 1)
       (every? (fn [[l r]]
                 (let [{ltime :last-trade-time} l
                       {rtime :last-trade-time} r]
                   (neg? (compare ltime rtime)))))))
