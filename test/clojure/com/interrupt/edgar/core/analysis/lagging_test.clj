(ns com.interrupt.edgar.core.analysis.lagging-test
  (:require [clj-time.coerce :as c]
            [clj-time.core :as t]
            [clj-time.spec :as ts]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as g]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.test.check :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.tools.trace :refer [trace]]
            [com.gfredericks.test.chuck.clojure-test :refer [checking]]
            [com.interrupt.edgar.core.analysis.lagging :as sut]
            [com.interrupt.edgar.core.utils :refer [sine]])
  (:import [java.math RoundingMode]
           [java.text DecimalFormat]))

(defn double->str
  ([d]
   (double->str d 4))
  ([d n]
   (let [dfmt (DecimalFormat. (str "#." (str/join (repeat n "#"))))]
     (.setRoundingMode dfmt RoundingMode/CEILING)
     (.format dfmt d))))

(deftest ema-test
  (let [xs [1 2 3 4 5]]
    (are [e v] (= (double->str e) (double->str v))
      3.6584 (sut/ema xs)
      2.3416 (sut/ema (reverse xs)))))

(deftest bollinger-test
  (let [xs [1 2 3 4 5]]
    (are [e v] (= (map double->str e) (map double->str v))
      [-0.1623 3.0 6.1623] (sut/bollinger xs)
      [-0.1623 3.0 6.1623] (sut/bollinger (reverse xs)))))

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

      (is (= (double->str average-comparator)
             (double->str last-trade-price-average))))))

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

(defn time-seq->time-series [time-seq]
  (->> time-seq
       (map (fn [e] [e (sine 2 2 e 0)]))
       (map (fn [[time price]]
              {:last-trade-time time
               :last-trade-price price}))))

(defn time-seq->simple-moving-averages [time-seq]
  (let [time-series (time-seq->time-series time-seq)
        time-series-partitioned (partition 20 1 time-series)]
    (->> time-series-partitioned
         (map #(sut/simple-moving-average {} %)))))

(defn simple->expoential-moving-averages [simple-moving-averages]
  (->> simple-moving-averages
       (sut/exponential-moving-average {} 20)))

(defn time-seq->simple-exponential-pair [time-seq]

  (let [simple-moving-averages (time-seq->simple-moving-averages (range 41))
        expoential-moving-averages (simple->expoential-moving-averages simple-moving-averages)]

    {:sma-list (map #(dissoc % :population) simple-moving-averages)
     :ema-list expoential-moving-averages}))

(deftest exponential-faster-than-simple-moving-average

  (time-seq->simple-exponential-pair (range 41))

  (is true))
