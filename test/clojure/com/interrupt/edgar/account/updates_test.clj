(ns com.interrupt.edgar.account.updates-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async :refer [<!!]]
            [com.interrupt.edgar.account.updates :as sut]))

(def u1 {:key "k1" :value 1})
(def u2 {:key "k2" :value 2})
(def u3 {:key "k3" :value 3})
(def u4 {:key "k4" :value 4})
(def u5 {:key "k5" :value 5})
(def not-ready {:key "AccountReady" :value "false"})
(def ready {:key "AccountReady" :value "true"})

(def updates [u1 u2 not-ready u3 u4 ready u5])

(deftest remove-not-ready-test
  (is (= [u1 u2 u5] (sut/remove-not-ready updates))))

(deftest ch-xform-test
  (let [ch (async/chan 10 sut/ch-xform)]
    (doseq [u updates] (async/put! ch u))
    (async/put! ch ::sut/download-end)
    (is (= [u1 u2 u5] (<!! ch)))))
