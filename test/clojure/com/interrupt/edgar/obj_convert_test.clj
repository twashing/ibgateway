(ns com.interrupt.edgar.obj-convert-test
  (:require [clojure.test :refer :all]
            [com.interrupt.edgar.obj-convert :as sut]))

(deftest nil-test
  (is (nil? (sut/convert nil))))

(deftest object-test
  (let [obj (Object.)]
    (is (identical? obj (sut/convert obj)))))

(deftest to-map-test
  (is (= {:f1 1 :f2 2 :f3 3}
         (sut/to-map (->TestType 1 2 3)))))
