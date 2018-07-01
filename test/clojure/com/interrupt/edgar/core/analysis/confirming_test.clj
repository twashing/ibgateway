(ns com.interrupt.edgar.core.analysis.confirming-test
  (:require [clojure.test :refer :all]
            [clj-time.coerce :as c]
            [clj-time.spec :as ts]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as g]
            [com.interrupt.edgar.core.analysis.confirming :as sut]))


(deftest on-balance-volume-test

  (testing "Time ticks order from left to right"
    (let [date-list (g/sample (s/gen ::ts/date-time))
          tick-list (map (fn [e]
                           {:last-trade-time (c/to-long e)
                            :last-trade-price 2.25})
                         date-list)]
      (is (thrown? AssertionError (sut/on-balance-volume tick-list))))))

(deftest stochastic-oscillator-test

  (testing "Time ticks order from left to right"
    (let [date-list (g/sample (s/gen ::ts/date-time))
          tick-window 40
          tick-list (map (fn [e]
                           {:last-trade-time (c/to-long e)
                            :last-trade-price 2.25})
                         date-list)]
      (is (thrown? AssertionError (sut/relative-strength-index tick-window tick-list))))))
