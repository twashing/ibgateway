(ns com.interrupt.ibgateway.component.account
  (:refer-clojure :exclude [*])
  (:require [mount.core :refer [defstate] :as mount]
            [com.rpl.specter :as s]
            [clojure.tools.logging :refer [info] :as log]
            [clojure.core.async :as async :refer [chan go-loop <!! <!]]
            [automata.core :as au]
            [com.interrupt.ibgateway.component.account.contract :as contract]
            [com.interrupt.ibgateway.component.ewrapper :as ew]
            [com.interrupt.ibgateway.component.switchboard.mock :refer :all])
  (:import [com.ib.client Order OrderStatus]))


(comment  ;; Place orders


  ;; Place order until 8pm EST
  ;; "outside rth" = true
  ;; Order.m_outsideRth = true ;; up to 8pm EST (for US stocks)
  ;; https://github.com/benofben/interactive-brokers-api/blob/master/JavaClient/src/com/ib/client/Order.java


  ;; https://interactivebrokers.github.io/tws-api/order_submission.html
  ;; https://interactivebrokers.github.io/tws-api/basic_orders.html


  (do
    (mount/stop #'ew/default-chs-map #'ew/ewrapper #'account)
    (mount/start #'ew/default-chs-map #'ew/ewrapper #'account)

    (def client (:client ew/ewrapper))
    (def wrapper (:wrapper ew/ewrapper))
    (def account-name "DU542121")
    (def valid-order-id (chan))
    (def order-filled-notification-ch (chan))

    (consume-order-updates ew/default-chs-map valid-order-id order-filled-notification-ch))


  (.cancelOrder client valid-order-id)
  (.cancelOrder client 3)
  ;; (.reqIds client -1)
  ;; (<!! valid-order-id)


  (.reqAllOpenOrders client)
  (.reqOpenOrders client)
  (.reqAutoOpenOrders client true)

  (.reqPositions client)
  (.reqAccountSummary client 9001 "All" account-summary-tags)

  ;; BUY
  (.placeOrder client
               valid-order-id
               (contract/create "AAPL")
               (doto (Order.)
                 (.action "BUY")
                 (.orderType "MKT")
                 (.totalQuantity 10)
                 (.account account-name)))

  ;; SELL
  (.placeOrder client
               4 ;;valid-order-id
               (contract/create "AAPL")
               (doto (Order.)
                 (.action "SELL")
                 (.orderType "MKT")
                 (.totalQuantity 10)
                 (.account account-name)))

  (.reqIds client -1)

  (.placeOrder client
               valid-order-id
               (contract/create "AMZN")
               (doto (Order.)
                 (.action "BUY")
                 (.orderType "MKT")
                 (.totalQuantity 20)
                 (.account account-name)))

  (.placeOrder client
               valid-order-id
               (contract/create "AAPL")
               (doto (Order.)
                 (.action "BUY")
                 (.orderType "MKT")
                 (.totalQuantity 50)
                 (.account account-name)))

  ;; LMT
  (let [action "BUY"
        quantity 10
        limitPrice 213.46]

    (.placeOrder client
                 7 ;;valid-order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType "LMT")
                   (.totalQuantity quantity)
                   (.lmtPrice limitPrice))))

  (let [action "SELL"
        quantity 10
        limitPrice 213.11]

    (.placeOrder client
                 8 ;;valid-order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType "LMT")
                   (.totalQuantity quantity)
                   (.lmtPrice limitPrice))))


  ;; STP
  (let [action "BUY"
        quantity 10
        stopPrice 213.11]

    (.placeOrder client
                 9 ;; valid-order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType "STP")
                   (.auxPrice stopPrice)
                   (.totalQuantity quantity))))

  (let [action "SELL"
        quantity 10
        stopPrice 212.88]

    (.placeOrder client
                 10 ;; valid-order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType "STP")
                   (.auxPrice stopPrice)
                   (.totalQuantity quantity))))


  ;; STP LMT
  (let [action "BUY"
        quantity 10
        stopPrice 212.13
        limitPrice 212.13]

    (.placeOrder client
                 11 ;; valid-order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType "STP LMT")
                   (.lmtPrice limitPrice)
                   (.auxPrice stopPrice)
                   (.totalQuantity quantity))))

  (let [action "SELL"
        quantity 10
        stopPrice 212.85
        limitPrice 212.85]

    (.placeOrder client
                 12 ;; valid-order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType "STP LMT")
                   (.lmtPrice limitPrice)
                   (.auxPrice stopPrice)
                   (.totalQuantity quantity))))


  ;; TRAIL - https://www.interactivebrokers.com/en/index.php?f=605 (greater protection for fast-moving stocks)
  (let [action "SELL"
        quantity 10
        trailingPercent 1
        trailStopPrice 176.26]

    (.placeOrder client
                 @valid-order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType "TRAIL")
                   (.trailingPercent trailingPercent)
                   (.trailStopPrice trailStopPrice)
                   (.totalQuantity quantity))))


  ;; TRAIL LIMIT - https://www.interactivebrokers.com/en/index.php?f=606 (Not guaranteed an execution)
  (let [action "SELL"
        quantity 10
        lmtPriceOffset 0.1
        trailingAmount 0.2
        trailStopPrice 177.18]

    (.placeOrder client
                 @valid-order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType "TRAIL LIMIT")
                   (.lmtPriceOffset lmtPriceOffset)
                   (.auxPrice trailingAmount)
                   (.trailStopPrice trailStopPrice)
                   (.totalQuantity quantity)))))

(comment  ;; MKT (buy w/ order-filled-notification)

  (do
    (mount/stop #'ew/default-chs-map #'ew/ewrapper #'account)
    (mount/start #'ew/default-chs-map #'ew/ewrapper #'account)
    (def wrapper (:wrapper ew/ewrapper))
    (def account-name "DU542121")
    (def valid-order-id (atom -1))
    (def order-filled-notification-ch (chan))

    (consume-order-updates ew/default-chs-map valid-order-id order-filled-notification-ch)
    (go-loop [{:keys [stock order] :as stock+order} (<! order-filled-notification-ch)]
      (info "stock+order / " stock+order)
      (recur (<! order-filled-notification-ch))))


  (do

    ;; 1
    (let [symbol "AAPL"
          orderId 3
          orderType "MKT"
          action "BUY"
          quantity 10.0
          status "PreSubmitted"]
      (->openOrder wrapper symbol account-name orderId orderType action quantity status))

    ;; 2
    (let [orderId 3
          status "PreSubmitted"
          filled 0.0
          remaining 10.0
          avgFillPrice 0.0
          lastFillPrice 0.0]
      (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

    ;; 3
    (let [symbol "AAPL"
          orderId 3
          shares 10.0
          price 0.0
          avgPrice 0.0
          reqId 1]
      (->execDetails wrapper symbol orderId shares price avgPrice reqId))

    ;; 4
    (let [symbol "AAPL"
          orderId 3
          orderType "MKT"
          action "BUY"
          quantity 10.0
          status "Filled"]
      (->openOrder wrapper symbol account-name orderId orderType action quantity status))

    ;; 5
    (let [orderId 3
          status "Filled"
          filled 10.0
          remaining 0.0
          avgFillPrice 218.96
          lastFillPrice 218.96]
      (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

    ;; 6
    (let [symbol "AAPL"
          orderId 3
          orderType "MKT"
          action "BUY"
          quantity 10.0
          status "Filled"]
      (->openOrder wrapper symbol account-name orderId orderType action quantity status))

    ;; 7
    (let [orderId 3
          status "Filled"
          filled 10.0
          remaining 0.0
          avgFillPrice 218.97
          lastFillPrice 218.98]
      (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

    ;; 8
    (let [commission 0.382257
          currency "USD"
          realizedPNL 1.7976931348623157E308]
      (->commissionReport wrapper commission currency realizedPNL))))

(comment  ;; TRAIL LIMIT (buy)

  (do
    (mount/stop #'ew/default-chs-map #'ew/ewrapper #'account)
    (mount/start #'ew/default-chs-map #'ew/ewrapper #'account)
    (def wrapper (:wrapper ew/ewrapper))
    (def account-name "DU542121")
    (def valid-order-id (atom -1))
    (def order-filled-notification-ch (chan))

    (consume-order-updates ew/default-chs-map valid-order-id order-filled-notification-ch))


  ;; 1
  (let [symbol "AAPL"
        account-name "DU542121"
        orderId 5
        action "SELL"
        orderType "TRAIL LIMIT"
        quantity 10.0
        status "PreSubmitted"]
    (->openOrder wrapper symbol account-name orderId action orderType quantity status))

  (let [orderId 5
        status "PreSubmitted"
        filled 0.0
        remaining 10.0
        avgFillPrice 0.0
        lastFillPrice 0.0]
    (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))


  ;; 2
  (let [symbol "AAPL"
        account-name "DU542121"
        orderId 5
        action "SELL"
        orderType "TRAIL LIMIT"
        quantity 10.0
        status "PreSubmitted"]
    (->openOrder wrapper symbol account-name orderId action orderType quantity status))

  (let [orderId 5
        status "PreSubmitted"
        filled 0.0
        remaining 10.0
        avgFillPrice 0.0
        lastFillPrice 0.0]
    (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))


  ;; 3
  (let [symbol "AAPL"
        account-name "DU542121"
        orderId 5
        action "SELL"
        orderType "TRAIL LIMIT"
        quantity 10.0
        status "Submitted"]
    (->openOrder wrapper symbol account-name orderId action orderType quantity status))

  (let [orderId 5
        status "Submitted"
        filled 0.0
        remaining 10.0
        avgFillPrice 0.0
        lastFillPrice 0.0]
    (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

  (let [symbol "AAPL"
        orderId 5
        shares 10.0
        price 0.0
        avgPrice 0.0
        reqId 1]
    (->execDetails wrapper symbol orderId shares price avgPrice reqId))


  ;; 4
  (let [symbol "AAPL"
        account-name "DU542121"
        orderId 5
        action "SELL"
        orderType "TRAIL LIMIT"
        quantity 10.0
        status "Filled"]
    (->openOrder wrapper symbol account-name orderId action orderType quantity status))

  (let [orderId 5
        status "Filled"
        filled 10.0
        remaining 0.0
        avgFillPrice 218.49
        lastFillPrice 218.49]
    (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))


  ;; 5
  (let [symbol "AAPL"
        account-name "DU542121"
        orderId 5
        action "SELL"
        orderType "TRAIL LIMIT"
        quantity 10.0
        status "Filled"]
    (->openOrder wrapper symbol account-name orderId action orderType quantity status))

  (let [orderId 5
        status "Filled"
        filled 10.0
        remaining 0.0
        avgFillPrice 218.49
        lastFillPrice 218.49]
    (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

  (let [commission 0.352257
        currency "USD"
        realizedPNL 1.7976931348623157E308]
    (->commissionReport wrapper commission currency realizedPNL)))

(comment  ;; TRAIL (sell)


  (do
    (mount/stop #'ew/default-chs-map #'ew/ewrapper #'account)
    (mount/start #'ew/default-chs-map #'ew/ewrapper #'account)
    (def wrapper (:wrapper ew/ewrapper))
    (def account-name "DU542121")
    (def valid-order-id (atom -1))
    (def order-filled-notification-ch (chan))

    (consume-order-updates ew/default-chs-map valid-order-id order-filled-notification-ch))

  (do
    (let [orderId 13
          symbol "AAPL"
          action "SELL"
          orderType "TRAIL"
          quantity 10.0
          status "PreSubmitted"]
      (->openOrder wrapper symbol account-name orderId orderType action quantity status))

    (let [orderId 13
          status "PreSubmitted"
          filled 0.0
          remaining 10.0
          avgFillPrice 0.0
          lastFillPrice 0.0]
      (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

    (let [orderId 13
          symbol "AAPL"
          action "SELL"
          orderType "TRAIL"
          quantity 10.0
          status "PreSubmitted"]
      (->openOrder wrapper symbol account-name orderId orderType action quantity status))

    (let [orderId 13
          status "PreSubmitted"
          filled 0.0
          remaining 10.0
          avgFillPrice 0.0
          lastFillPrice 0.0]
      (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

    (let [orderId 13
          symbol "AAPL"
          action "SELL"
          orderType "TRAIL"
          quantity 10.0
          status "Submitted"]
      (->openOrder wrapper symbol account-name orderId orderType action quantity status))

    (let [orderId 13
          status "Submitted"
          filled 0.0
          remaining 10.0
          avgFillPrice 0.0
          lastFillPrice 0.0]
      (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

    (let [symbol "AAPL"
          orderId 13
          shares 10.0
          price 0.0
          avgPrice 0.0
          reqId 1]
      (->execDetails wrapper symbol orderId shares price avgPrice reqId))

    (let [orderId 13
          symbol "AAPL"
          action "SELL"
          orderType "TRAIL"
          quantity 10.0
          status "Filled"]
      (->openOrder wrapper symbol account-name orderId orderType action quantity status))

    (let [orderId 13
          status "Filled"
          filled 10.0
          remaining 0.0
          avgFillPrice 176.27
          lastFillPrice 176.27]
      (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

    (let [orderId 13
          symbol "AAPL"
          action "SELL"
          orderType "TRAIL"
          quantity 10.0
          status "Filled"]
      (->openOrder wrapper symbol account-name orderId orderType action quantity status))

    (let [orderId 13
          status "Filled"
          filled 10.0
          remaining 0.0
          avgFillPrice 176.27
          lastFillPrice 176.27]
      (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

    ;; exec-details is missing

    (let [commission 0.355362
          currency "USD"
          realizedPNL -380.989362]
      (->commissionReport wrapper commission currency realizedPNL))))

(comment  ;; TRAIL LIMIT (sell)

  (do
    (mount/stop #'ew/default-chs-map #'ew/ewrapper #'account)
    (mount/start #'ew/default-chs-map #'ew/ewrapper #'account)
    (def wrapper (:wrapper ew/ewrapper))
    (def account-name "DU542121")
    (def valid-order-id (atom -1))
    (def order-filled-notification-ch (chan))

    (consume-order-updates ew/default-chs-map valid-order-id order-filled-notification-ch))


  (do
    (let [orderId 13
          symbol "AAPL"
          action "SELL"
          orderType "TRAIL LIMIT"
          quantity 10.0
          status "PreSubmitted"]
      (->openOrder wrapper symbol account-name orderId orderType action quantity status))

    (let [orderId 13
          status "PreSubmitted"
          filled 0.0
          remaining 10.0
          avgFillPrice 0.0
          lastFillPrice 0.0]
      (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

    (let [orderId 13
          symbol "AAPL"
          action "SELL"
          orderType "TRAIL LIMIT"
          quantity 10.0
          status "PreSubmitted"]
      (->openOrder wrapper symbol account-name orderId orderType action quantity status))

    (let [orderId 13
          status "PreSubmitted"
          filled 0.0
          remaining 10.0
          avgFillPrice 0.0
          lastFillPrice 0.0]
      (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

    (let [symbol "AAPL"
          orderId 13
          shares 10.0
          price 0.0
          avgPrice 0.0
          reqId 1]
      (->execDetails wrapper symbol orderId shares price avgPrice reqId))

    (let [orderId 13
          symbol "AAPL"
          action "SELL"
          orderType "TRAIL LIMIT"
          quantity 10.0
          status "Filled"]
      (->openOrder wrapper symbol account-name orderId orderType action quantity status))

    (let [orderId 13
          status "Filled"
          filled 0.0
          remaining 0.0
          avgFillPrice 177.16
          lastFillPrice 177.16]
      (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

    (let [orderId 13
          symbol "AAPL"
          action "SELL"
          orderType "TRAIL LIMIT"
          quantity 10.0
          status "Filled"]
      (->openOrder wrapper symbol account-name orderId orderType action quantity status))

    (let [orderId 13
          status "Filled"
          filled 0.0
          remaining 0.0
          avgFillPrice 177.16
          lastFillPrice 177.16]
      (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

    (let [commission 0.352478
          currency "USD"
          realizedPNL -372.086478]
      (->commissionReport wrapper commission currency realizedPNL))))
