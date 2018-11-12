(ns com.interrupt.ibgateway.component.ewrapper
  (:require [clojure.tools.logging :refer [info] :as log]
            [clojure.core.async :as async :refer [<! close!]]
            [environ.core :refer [env]]
            [mount.core :as mount :refer [defstate]]
            [com.interrupt.ibgateway.component.account.contract :as contract]
            [com.interrupt.ibgateway.component.ewrapper-impl :as ewi]
            [com.interrupt.ibgateway.component.account.common :refer [next-reqid! release-reqid!]])
  (:import [com.ib.client Order OrderState OrderStateWrapper Execution CommissionReport]))


(def tws-host (env :tws-host "tws"))
(def tws-port (env :tws-port 4002))
(def client-id (atom (next-reqid!)))

(defstate default-chs-map
  :start {:publisher (-> 1000 async/sliding-buffer async/chan)
          :account-updates (-> 1000 async/sliding-buffer async/chan)
          :order-updates (-> 1000 async/sliding-buffer async/chan)}
  :stop (->> default-chs-map vals (map close!)))

(defstate ewrapper
  :start (ewi/ewrapper tws-host tws-port @client-id default-chs-map)
  :stop (let [{client :client} ewrapper]
          (when (.isConnected client)
            (.eDisconnect client))
          (release-reqid! @client-id)
          (reset! client-id -1)))



(defmulti order-data-tracking (fn [x y] x))

(defmethod order-data-tracking :MKT [_ c]
  [:order-id :shares :order-type :symbol :security-type :action :quantity :status
   :last-fill-price :avg-fill-price :filled :remaining :currency :commission :realized-pnl])

(defmethod order-data-tracking :LMT [_ c]
  [:order-id :symbol :security-type :currency :average-cost :order-type :action :total-quantity :status
   :last-fill-price :avg-fill-price :filled :remaining :commission :realized-pnl])

;; :status ;; (PreSubmitted -> Submitted -> Filled)
(defmethod order-data-tracking :STP [_ c]
  [:order-id :symbol :security-type :currency :average-cost :order-type :action :total-quantity
   :status :last-fill-price :avg-fill-price :filled :remaining :position :commission :realized-pnl])

(defmethod order-data-tracking :STP-LMT [_ c]
  [:order-id :shares :order-type :symbol :security-type :action :quantity :status
   :last-fill-price :avg-fill-price :filled :remaining :currency :commission :realized-pnl])

(defmethod order-data-tracking :TRAIL [_ c]
  [:order-id :symbol :security-type :currency :average-cost :order-type :action :total-quantity :status
   :last-fill-price :avg-fill-price :filled :remaining :commission :realized-pnl])

(defmethod order-data-tracking :TRAIL-LIMIT [_ c]
  [:order-id :symbol :security-type :currency :average-cost :order-type :action :total-quantity :status
   :last-fill-price :avg-fill-price :filled :remaining :commission :realized-pnl])


(comment


  (require '[automat.core :as a]
           '[automat.compiler.core :as cc]
           '[automat.viz :refer (view)])

  (def fsm (a/compile [:pre-submitted :submitted :filled]))
  (def result (a/advance fsm nil :pre-submitted))



  (require '[automata.core :as u])

  (def a (u/automaton [:pre-submitted :submitted :filled]))
  (-> a (u/advance :pre-submitted))
  (-> a (u/advance :pre-submitted) (u/advance :b :submitted))



  (mount/stop #'default-chs-map #'ewrapper)
  (mount/start #'default-chs-map #'ewrapper)

  (def wrapper (:wrapper ewrapper))

  ;; openOrder
  (let [symbol "AAPL"
        account "DU542121"

        orderId 1
        contract (contract/create symbol)
        order (doto (Order.)
                (.action "BUY")
                (.orderType "MKT")
                (.totalQuantity 10)
                (.account account))

        status "PreSubmitted"
        initMargin nil
        maintMargin nil
        equityWithLoan nil
        commission 0.0
        minCommission 0.0
        maxCommission 0.0
        commissionCurrency nil
        warningText nil

        ^OrderState orderState (OrderStateWrapper/genOrderState status initMargin maintMargin equityWithLoan commission
                                                                minCommission maxCommission commissionCurrency warningText)]

    (.openOrder wrapper orderId contract order orderState))

  ;; orderStatus
  (let [orderId 3
        status "PreSubmitted"
        filled 0.0
        remaining 10.0
        avgFillPrice 0.0
        permId 652797928
        parentId 0
        lastFillPrice 0.0
        clientId 1
        whyHeld "locate"]

    (.orderStatus wrapper orderId status filled remaining avgFillPrice permId parentId lastFillPrice clientId whyHeld))

  ;; execDetails
  (let [symbol "AAPL"

        execId "00018037.5beb36f0.01.01"
        orderId 3
        shares 10

        p_orderId orderId
        p_client 0
        p_execId execId
        p_time "time"
        p_acctNumber "acctNumber"
        p_exchange "exchange"
        p_side "side"
        p_shares shares
        p_price 0.0
        p_permId 0
        p_liquidation 0
        p_cumQty 0
        p_avgPrice 0.0
        p_orderRef "orderRef"
        p_evRule "evRule"
        p_evMultiplier 0.0
        extra_undocumented_arguemnt "Foobar"

        reqId 1
        contract (contract/create symbol)
        execution (Execution. p_orderId p_client p_execId p_time
                              p_acctNumber p_exchange p_side p_shares
                              p_price p_permId p_liquidation p_cumQty
                              p_avgPrice p_orderRef p_evRule p_evMultiplier extra_undocumented_arguemnt)]

    (.execDetails wrapper  reqId contract execution))

  ;; commissionReport
  (let [commissionReport (CommissionReport.)]

    (set! (.-m_execId commissionReport) "00018037.5beb36f0.01.01")
    (set! (.-m_commission commissionReport) 0.382257)
    (set! (.-m_currency commissionReport) "USD")
    (set! (.-m_realizedPNL commissionReport) 1.7976931348623157E308)

    (.commissionReport wrapper commissionReport))

  ;;position
  (let [symbol "AAPL"

        account "DU542121"
        contract (contract/create symbol)
        pos 20.0
        avgCost 218.530225]

    (.position wrapper account contract pos avgCost))


  ;; MKT order data tracking
  ;; OrderId
  ;; Shares
  ;; OrderType (MKT)
  ;; Symbol
  ;; SecurityType
  ;; Action (BUY|SELL)
  ;; Quantity (<BUY>)
  ;; Status (PreSubmitted -> Filled)
  ;; LastFillPrice (<SELL>)
  ;; AvgFillPrice (<SELL>)
  ;; Filled (<SELL>)
  ;; Remaining (<SELL>)
  ;; Currency
  ;; Commission (<All Filled>)
  ;; realizedPNL (<All Filled>)

  :order-id
  :shares
  :order-type
  :symbol
  :security-type
  :action
  :quantity
  :status
  :last-fill-price
  :avg-fill-price
  :filled
  :remaining
  :currency
  :commission
  :realized-pnl


  ;; LMT order data tracking
  ;; OrderId
  ;; Symbol
  ;; SecurityType
  ;; Currency
  ;; AverageCost
  ;; OrderType (LMT)
  ;; Action (BUY|SELL)
  ;; TotalQuantity
  ;; Status (PreSubmitted -> Filled)
  ;; LastFillPrice (<SELL>)
  ;; AvgFillPrice (<SELL>)
  ;; Filled (<SELL>)
  ;; Remaining
  ;; Currency
  ;; Commission (<All Filled>)
  ;; realizedPNL (<All Filled>)

  :order-id
  :symbol
  :security-type
  :currency
  :average-cost
  :order-type
  :action
  :total-quantity
  :status
  :last-fill-price
  :avg-fill-price
  :filled
  :remaining
  :commission
  :realized-pnl


  ;; STP order data tracking
  :order-id
  :symbol
  :security-type
  :currency
  :average-cost
  :order-type
  :action
  :total-quantity
  :status ;; (PreSubmitted -> Submitted -> Filled)
  :last-fill-price
  :avg-fill-price
  :filled
  :remaining
  :position
  :commission
  :realized-pnl




  ;; STP LMT order data tracking
  :order-id
  :shares
  :order-type
  :symbol
  :security-type
  :action
  :quantity
  :status
  :last-fill-price
  :avg-fill-price
  :filled
  :remaining
  :currency
  :commission
  :realized-pnl


  ;; TRAIL order data tracking
  :order-id
  :symbol
  :security-type
  :currency
  :average-cost
  :order-type
  :action
  :total-quantity
  :status
  :last-fill-price
  :avg-fill-price
  :filled
  :remaining
  :commission
  :realized-pnl


  ;; TRAIL LIMIT order data tracking
  :order-id
  :symbol
  :security-type
  :currency
  :average-cost
  :order-type
  :action
  :total-quantity
  :status
  :last-fill-price
  :avg-fill-price
  :filled
  :remaining
  :commission
  :realized-pnl)

(comment

  ;; ** capture .placeOrder callback from ewrapper_impl

  ;; ** Protocolize
  ;;   stock level (which stock, how much)
  ;;   cash level (how much)

  ;; buy triggers (in execution engine)
  ;;   - conditionally if we haven't already bought


  ;; buy types
  ;; sell types
  ;;   sell callback (for limit orders)



  ;; MKT
  ;; LMT

  ;; STP
  ;; STP LMT

  ;; TRAIL
  ;; TRAIL LIMIT


  ;; Place order until 8pm EST
  ;; "outside rth" = true
  ;; Order.m_outsideRth = true ;; up to 8pm EST (for US stocks)
  ;; https://github.com/benofben/interactive-brokers-api/blob/master/JavaClient/src/com/ib/client/Order.java


  ;; https://interactivebrokers.github.io/tws-api/order_submission.html
  ;; https://interactivebrokers.github.io/tws-api/basic_orders.html

  ;; Order order = new Order();
  ;; order.action(action);
  ;; order.orderType("MKT");
  ;; order.totalQuantity(quantity);

  ;; Order order = new Order();
  ;; order.action(action);
  ;; order.orderType("LMT");
  ;; order.totalQuantity(quantity);
  ;; order.lmtPrice(limitPrice);


  ;; Order order = new Order();
  ;; order.action(action);
  ;; order.orderType("STP");
  ;; order.auxPrice(stopPrice);
  ;; order.totalQuantity(quantity);

  ;; Order order = new Order();
  ;; order.action(action);
  ;; order.orderType("STP LMT");
  ;; order.lmtPrice(limitPrice);
  ;; order.auxPrice(stopPrice);
  ;; order.totalQuantity(quantity);


  ;; Order order = new Order();
  ;; order.action(action);
  ;; order.orderType("TRAIL");
  ;; order.trailingPercent(trailingPercent);
  ;; order.trailStopPrice(trailStopPrice);
  ;; order.totalQuantity(quantity);

  ;; Order order = new Order();
  ;; order.action(action);
  ;; order.orderType("TRAIL LIMIT");
  ;; order.lmtPriceOffset(lmtPriceOffset);
  ;; order.auxPrice(trailingAmount);
  ;; order.trailStopPrice(trailStopPrice);
  ;; order.totalQuantity(quantity);


  (mount/stop #'default-chs-map #'ewrapper)
  (mount/start #'default-chs-map #'ewrapper)

  (do
    (def client (:client ewrapper))
    (def account "DU542121")
    (def valid-order-id (next-reqid!)))

  (.cancelOrder client valid-order-id)
  (.cancelOrder client 3)
  (def valid-order-id 3)


  (.reqAllOpenOrders client)
  (.reqOpenOrders client)
  (.reqAutoOpenOrders client true)

  (.reqPositions client)

  ;; BUY
  (.placeOrder client
               valid-order-id
               (contract/create "AAPL")
               (doto (Order.)
                 (.action "BUY")
                 (.orderType "MKT")
                 (.totalQuantity 10)
                 (.account account)))

  ;; SELL
  (.placeOrder client
               4 ;;valid-order-id
               (contract/create "AAPL")
               (doto (Order.)
                 (.action "SELL")
                 (.orderType "MKT")
                 (.totalQuantity 10)
                 (.account account)))

  (.reqIds client -1)

  (.placeOrder client
               valid-order-id
               (contract/create "AMZN")
               (doto (Order.)
                 (.action "BUY")
                 (.orderType "MKT")
                 (.totalQuantity 20)
                 (.account account)))

  (.placeOrder client
               valid-order-id
               (contract/create "AAPL")
               (doto (Order.)
                 (.action "BUY")
                 (.orderType "MKT")
                 (.totalQuantity 50)
                 (.account account)))

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


  ;; TRAIL - https://www.interactivebrokers.com/en/index.php?f=605
  (let [action "BUY"
        quantity 10
        trailingPercent 1
        trailStopPrice 218.44]

    (.placeOrder client
                 6 ;;valid-order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType "TRAIL")
                   (.trailingPercent trailingPercent)
                   (.trailStopPrice trailStopPrice)
                   (.totalQuantity quantity))))


  ;; TRAIL LIMIT - https://www.interactivebrokers.com/en/index.php?f=606
  (let [action "BUY"
        quantity 10
        lmtPriceOffset 0.1
        trailingAmount 0.2
        trailStopPrice 218.49]

    (.placeOrder client
                 5 ;;valid-order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType "TRAIL LIMIT")
                   (.lmtPriceOffset lmtPriceOffset)
                   (.auxPrice trailingAmount)
                   (.trailStopPrice trailStopPrice)
                   (.totalQuantity quantity)))))
