(ns com.interrupt.edgar.core.analysis.confirming-test
  (:require [clj-time.coerce :as c]
            [clj-time.spec :as ts]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as g]
            [clojure.test :refer :all]
            [com.interrupt.edgar.core.analysis.confirming :as sut]))

(def prices [10 10.15 10.17 10.13 10.11 10.15 10.20 10.20 10.22 10.21])

(deftest obv-test
  (let [volumes [25200 30000 25600 32000 23000 40000 36000 20500 23000 27500]
        r-prices (reductions conj [(first prices)] (rest prices))
        r-volumes (reductions conj [(first volumes)] (rest volumes))]
    (is (= [0 30000 55600 23600 600 40600 76600 76600 99600 72100]
           (map sut/obv r-prices r-volumes)))))

(deftest rsi-test
  (is (= 80.0 (sut/rsi prices))))

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
