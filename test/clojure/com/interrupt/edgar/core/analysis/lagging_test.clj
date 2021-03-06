(ns com.interrupt.edgar.core.analysis.lagging-test
  (:require [clj-time.coerce :as c]
            [clj-time.core :as t]
            [clj-time.spec :as ts]
            [clojure.java.io :as io]
            [clojure.tools.trace :refer [trace]]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as g]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.test.check :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [com.gfredericks.test.chuck.clojure-test :refer [checking]]
            [com.interrupt.edgar.core.analysis.lagging :as sut]
            [com.interrupt.edgar.core.utils :refer [sine]]
            [com.interrupt.edgar.utils :as utils]))

(deftest ema-test
  (let [xs [1 2 3 4 5]]
    (are [e v] (= (utils/double->str e) (utils/double->str v))
      3.6585 (sut/ema xs)
      2.3416 (sut/ema (reverse xs)))))

(deftest bollinger-test
  (let [xs [1 2 3 4 5]]
    (are [e v] (= (map utils/double->str e) (map utils/double->str v))
      [-0.1622 3.0 6.1623] (sut/bollinger xs)
      [-0.1622 3.0 6.1623] (sut/bollinger (reverse xs)))))

(def test-tick-data
  (->> "tick-data.4.edn"
       io/resource
       slurp
       read-string))

(defn successful-check? [results]
  (every? #(-> % :clojure.spec.test.check/ret :result true?)
          results))

(deftest data-test
  (testing "Time always increases from left to right"
    (is (->> test-tick-data
             (partition 2)
             (every? (fn [[l r]]
                       (t/before? (-> l :last-trade-time Long/parseLong c/from-long)
                                  (-> r :last-trade-time Long/parseLong c/from-long))))))))

(deftest average-test
  (testing "Spec is maintained under test conditions"
    (is (successful-check? (stest/check `sut/average)))))

(deftest simple-moving-average-test

  (testing "Time ticks order from left to right"
    (let [date-list (g/sample (s/gen ::ts/date-time))
          options {}
          tick-list (map (fn [e]
                           {:last-trade-time (c/to-long e)
                            :last-trade-price 2.25})
                         date-list)]
      (is (thrown? AssertionError (sut/simple-moving-average options tick-list)))))

  (testing "Basic moving average calculation"
    (let [n 20
          tick-list (take n test-tick-data)

          {:keys [last-trade-price-average]}
          (sut/simple-moving-average {} tick-list)

          average-comparator (->> tick-list
                                  (map :last-trade-price)
                                  (reduce +)
                                  (* (/ 1 n)))]

      (is (= (utils/double->str average-comparator)
             (utils/double->str last-trade-price-average))))))

(deftest exponential-moving-average-test

  (let [date-list (g/sample (s/gen ::ts/date-time))
        options {}
        tick-window 20
        sma-list (map (fn [e]
                        {:last-trade-time (c/to-long e)
                         :last-trade-price 2.25})
                      date-list)]
    (is (thrown? AssertionError (sut/exponential-moving-average options tick-window sma-list)))))

(deftest bollinger-band-test

  (let [date-list (g/sample (s/gen ::ts/date-time))
        tick-window 20
        sma-list (map (fn [e]
                        {:last-trade-time (c/to-long e)
                         :last-trade-price 2.25
                         :last-trade-price-average 2.225
                         :population (take 20 (repeat {:last-trade-price 2.25}))})
                      date-list)]
    (is (thrown? AssertionError (sut/bollinger-band tick-window sma-list)))))
