(ns com.interrupt.edgar.subscription-test
  (:require [clojure.core.async :as async :refer [<!!]]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [com.interrupt.edgar.subscription :as sut]))

(deftest file-subscription-test
  (let [ch (async/chan 10)
        sub (sut/->FileSubscription (io/resource "simple.edn") ch)]
    (is (= [{:a 1} {:b 2} {:c 3}]
           (->> sub
                sut/subscribe
                (async/into [])
                <!!)))))
