(ns com.interrupt.ibgateway.component.account.contract-test
  (:require [clojure.test :refer :all]
            [com.interrupt.ibgateway.component.account.contract :as sut]
            [com.interrupt.edgar.obj-convert :as obj-convert])
  (:import [com.ib.client DeltaNeutralContract]))

(deftest contract-convert-test
  (is (= {:conid 0,
          :symbol "TSLA",
          :sec-type "STK",
          :strike 0.0,
          :exchange "SMART",
          :currency "USD",
          :include-expired false,
          :combo-legs []}
         (obj-convert/convert (sut/create "TSLA")))))

(deftest delta-neutral-contract-convert-test
  (is (= {:conid 1, :delta 2.0, :price 3.0}
         (obj-convert/convert (DeltaNeutralContract. 1 2 3)))))
