(ns com.interrupt.edgar.core.analysis.leading-test
  (:require [clj-time.coerce :as c]
            [clj-time.spec :as ts]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as g]
            [clojure.test :refer :all]
            [com.interrupt.edgar.core.analysis.leading :as sut]
            [com.interrupt.edgar.utils :as utils]))

(def prices [10 10.15 10.17 10.13 10.11 10.15 10.20 10.20 10.22 10.21])

(deftest so-test
  (is (= (utils/double->str 95.4546)
         (utils/double->str (sut/so prices)))))

(deftest macd-test

  (testing "Time ticks order from left to right"
    (let [date-list (g/sample (s/gen ::ts/date-time))
          options {}
          tick-window 40
          sma-list (map (fn [e]
                           {:last-trade-time (c/to-long e)
                            :last-trade-price 2.25})
                         date-list)]
      (is (thrown? AssertionError (sut/macd options tick-window sma-list))))))

(deftest stochastic-oscillator-test

  (testing "Time ticks order from left to right"
    (let [date-list (g/sample (s/gen ::ts/date-time))
          stochastic-tick-window 14
          trigger-window 3
          trigger-line 3
          tick-list (map (fn [e]
                           {:last-trade-time (c/to-long e)
                            :last-trade-price 2.25})
                         date-list)]
      (is (thrown? AssertionError (sut/stochastic-oscillator
                                    stochastic-tick-window trigger-window trigger-line tick-list))))))
