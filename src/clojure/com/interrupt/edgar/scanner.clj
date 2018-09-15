(ns com.interrupt.edgar.scanner
  (:require [clojure.core.async :as async :refer [<! >! go-loop]]))

(defn most-frequent
  [colls]
  (->> colls
       (apply concat)
       (map #(select-keys % [:symbol :sec-type]))
       frequencies
       (sort-by second >)
       ffirst))

(defn scanner-decide
  ([chs-map]
   (scanner-decide chs-map most-frequent))
  ([{:keys [scanner-chs scanner-decision-ch]} decider]
   (let [{:keys [high-opt-imp-volat-ch
                 high-opt-imp-volat-over-hist-ch
                 hot-by-volume-ch
                 top-volume-rate-ch
                 hot-by-opt-volume-ch
                 opt-volume-most-active-ch
                 combo-most-active-ch
                 most-active-usd-ch
                 hot-by-price-ch
                 top-price-range-ch
                 hot-by-price-range-ch]} scanner-chs]
     (go-loop []
       (let [vs [(<! high-opt-imp-volat-ch)
                 (<! high-opt-imp-volat-over-hist-ch)
                 (<! hot-by-volume-ch)
                 (<! top-volume-rate-ch)
                 (<! hot-by-opt-volume-ch)
                 (<! opt-volume-most-active-ch)
                 (<! combo-most-active-ch)
                 (<! most-active-usd-ch)
                 (<! hot-by-price-ch)
                 (<! top-price-range-ch)
                 (<! hot-by-price-range-ch)]]
         (when (every? some? vs)
           (when-let [v (decider vs)]
             (>! scanner-decision-ch v))
           (recur)))))))
