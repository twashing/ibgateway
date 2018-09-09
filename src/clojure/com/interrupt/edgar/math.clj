(ns com.interrupt.edgar.math
  (:import [org.apache.commons.math3.stat.descriptive.moment Mean StandardDeviation]))

(defn mean
  [xs]
  (.evaluate (Mean.) (double-array (count xs) xs)))

(defn sd
  [xs]
  (.evaluate (StandardDeviation. true) (double-array (count xs) xs)))
