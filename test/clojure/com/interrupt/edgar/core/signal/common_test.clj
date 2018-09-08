(ns com.interrupt.edgar.core.signal.common-test
  (:require [clojure.test :refer :all]
            [com.interrupt.edgar.core.signal.common :as sut]))

(deftest peak?-test
  (is (sut/peak? 1 3 2))
  (is (not (sut/peak? 1 2 2)))
  (is (not (sut/peak? 2 2 1)))
  (is (not (sut/peak? 1 2 3)))
  (is (not (sut/peak? 3 2 1))))

(deftest valley?-test
  (is (sut/valley? 2 1 3))
  (is (not (sut/valley? 1 2 2)))
  (is (not (sut/valley? 2 2 1)))
  (is (not (sut/valley? 1 2 3)))
  (is (not (sut/valley? 3 2 1))))

(defn- make-ticks
  [prices]
  (map #(hash-map :last-trade-price %) prices))

(deftest find-peaks-valleys-test
  (is (= (for [[p s] [[1 :valley] [4 :peak] [5 :peak]]]
           {:last-trade-price p :signal s})
         (sut/find-peaks-valleys {} (make-ticks [3 1 4 2 0 0 5 3])))))
