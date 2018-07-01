(ns com.interrupt.edgar.core.analysis.lagging-test
  (:require [clojure.test :refer :all]
            [clojure.test.check :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.spec.test.alpha :as stest]
            [clojure.java.io :as io]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [com.gfredericks.test.chuck.clojure-test :refer [checking]]
            [com.interrupt.edgar.core.analysis.lagging :as sut]

            [clj-time.spec :as ts]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as g]))


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

(deftest sum-test
  (let [test-tick-data-first-twenty (take 20 test-tick-data)]
    (is (= (->> test-tick-data-first-twenty
                (map :last-trade-price)
                (reduce +))
           (sut/sum test-tick-data-first-twenty :last-trade-price)))))

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
    (let [options {}
          tick-list-size 20
          tick-list (->> test-tick-data
                         (take tick-list-size)
                         (map #(assoc % :last-trade-time
                                      (Long/parseLong (:last-trade-time %)))))

          result (sut/simple-moving-average options tick-list)

          average-comparator (as-> tick-list v
                               (map :last-trade-price v)
                               (reduce + v)
                               (/ v tick-list-size))]

      (is (= average-comparator (:last-trade-price-average result))))))

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
