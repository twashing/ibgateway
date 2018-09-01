(ns com.interrupt.edgar.core.analysis.leading-test
  (:require [clojure.test :refer :all]
            [clj-time.coerce :as c]
            [clj-time.spec :as ts]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as g]
            [com.interrupt.edgar.core.analysis.leading :as sut]))


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
