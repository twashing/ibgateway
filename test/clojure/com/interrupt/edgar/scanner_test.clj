(ns com.interrupt.edgar.scanner-test
  (:require [clojure.test :refer :all]
            [com.interrupt.edgar.scanner :as sut]
            [clojure.core.async :as async :refer [<!!]]))

(deftest most-frequent-test
  (is (= {:symbol "A" :sec-type "STK"}
         (sut/most-frequent
          [[{:symbol "A" :sec-type "STK"} {:symbol "B" :sec-type "STK"}]
           [{:symbol "A" :sec-type "STK"} {:symbol "C" :sec-type "STK"}]]))))

(def scanner-chs
  {:high-opt-imp-volat-ch (async/chan 1)
   :high-opt-imp-volat-over-hist-ch (async/chan 1)
   :hot-by-volume-ch (async/chan 1)
   :top-volume-rate-ch (async/chan 1)
   :hot-by-opt-volume-ch (async/chan 1)
   :opt-volume-most-active-ch (async/chan 1)
   :combo-most-active-ch (async/chan 1)
   :most-active-usd-ch (async/chan 1)
   :hot-by-price-ch (async/chan 1)
   :top-price-range-ch (async/chan 1)
   :hot-by-price-range-ch (async/chan 1)})

(deftest scanner-decide-test
  (let [{:keys [scanner-chs scanner-decision-ch] :as chs-map}
        {:scanner-chs scanner-chs
         :scanner-decision-ch (async/chan 1)}
        decider #(->> %
                      (apply concat)
                      frequencies
                      (sort-by second >)
                      ffirst)]
    (doseq [[_ ch] scanner-chs]
      (async/put! ch (conj (range 5) 10)))
    (sut/scanner-decide chs-map decider)
    (is (= 10 (<!! scanner-decision-ch)))))
