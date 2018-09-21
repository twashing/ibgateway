(ns com.interrupt.edgar.contract
  (:require [com.interrupt.edgar.obj-convert :as obj-convert])
  (:import [com.ib.client Contract DeltaNeutralContract]))

(extend-protocol obj-convert/ObjConvert
  Contract
  (convert [this]
    (obj-convert/to-map this))

  DeltaNeutralContract
  (convert [this]
    (obj-convert/to-map this)))

(defn create
  [symbol]
  (doto (Contract.)
    (.symbol symbol)
    (.secType "STK")
    (.exchange "SMART")
    (.currency "USD")))
