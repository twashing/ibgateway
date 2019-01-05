(ns com.interrupt.edgar.core.analysis.common)

(defn time-increases-left-to-right? [tick-list]
  (apply < (map :last-trade-time tick-list)))
