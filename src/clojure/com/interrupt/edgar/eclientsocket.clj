(ns com.interrupt.edgar.eclientsocket
  (:import  [com.ib.client EClientSocket]
            [com.interrupt.ibgateway EWrapperImpl]))

#_(defn connect-to-tws []

  (let [wr (defonce EWRAPPER (ref (EWrapperImpl.)))
        es (defonce esocket (EClientSocket. @EWRAPPER))]
    (.eConnect esocket "localhost" 7497 0)
    { :esocket esocket :ewrapper @EWRAPPER }))

#_(defn disconnect-from-tws []

  (if (.isConnected esocket)
    (.eDisconnect esocket)))
