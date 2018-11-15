(ns com.interrupt.ibgateway.component.account
  (:require [mount.core :refer [defstate] :as mount]
            [automata.core :as u]))


(def state (atom nil))

(defstate account
  :start (reset! state [])
  :stop (reset! state nil))


(comment

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

(comment  ;; Mock callbacks for orders

  ;; (->openOrder [symbol account orderId wrapper action quantity status])
  ;; (->orderStatus [orderId status filled remaining avgFillPrice lastFillPrice])
  ;; (->execDetails [symbol shares price avgPrice reqId])
  ;; (->commissionReport [commission currency realizedPNL])
  ;; (->position [symbol account pos avgCost])


  ;; TODO

  ;; Redo order pairs

  ;; MKT (buy)
  ;; TRAIL (sell) - https://www.interactivebrokers.com/en/index.php?f=605 (greater protection for fast-moving stocks)
  ;; vs TRAIL LIMIT (sell) - https://www.interactivebrokers.com/en/index.php?f=606 (Not guaranteed an execution)
  ;;   https://www.fidelity.com/learning-center/trading-investing/trading/stop-loss-video
  ;;   https://www.thestreet.com/story/10273105/1/ask-thestreet-limits-and-losses.html
  ;;   https://money.stackexchange.com/questions/89018/stop-limit-vs-stop-market-vs-trailing-stop-limit-vs-trailing-stop-market


  ;; ** Protocolize
  ;;   stock level (which stock, how much)
  ;;   cash level (how much)


  ;; As the market price rises,
  ;; both the i. stop price and the ii. limit price rise by the
  ;;          i.i trail amount and  ii.i limit offset respectively,

  ;; stop price > trail amount
  ;; limit price > limit offset


  ;; (let [action "BUY"
  ;;       quantity 10
  ;;       lmtPriceOffset 0.1
  ;;       trailingAmount 0.2
  ;;       trailStopPrice 218.49])


  ;; callbacks
  ;; wrapper



  (require '[automata.core :as u])

  (def a (u/automaton [:pre-submitted :submitted :filled]))
  (-> a (u/advance :pre-submitted))
  (-> a (u/advance :pre-submitted) (u/advance :b :submitted))



  (mount/stop #'ew/default-chs-map #'ew/ewrapper)

  (do

    (mount/start #'ew/default-chs-map #'ew/ewrapper)

    (def wrapper (:wrapper ew/ewrapper))

    (let [{:keys [order-updates]} ew/default-chs-map]
      (go-loop [{:keys [topic] :as val} (<! order-updates)]
        (info "go-loop / order-updates / topic /" val)
        (recur (<! order-updates)))))


  ;; TRAIL - buy (https://www.interactivebrokers.com/en/index.php?f=605)

  ;; STP LMT
  ;; STP

  ;; LMT
  ;; MKT

  )

(comment  ;; Place orders

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


  ;; TRAIL - https://www.interactivebrokers.com/en/index.php?f=605 (greater protection for fast-moving stocks)
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


  ;; TRAIL LIMIT - https://www.interactivebrokers.com/en/index.php?f=606 (Not guaranteed an execution)
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

(comment  ;; MKT (buy)

  )

(comment  ;; TRAIL LIMIT (sell)

    ;; 1
  (let [symbol "AAPL"
        account "DU542121"
        orderId 5
        action "BUY"
        orderType "TRAIL LIMIT"
        quantity 10.0
        status "PreSubmitted"]
      (->openOrder wrapper symbol account orderId action orderType quantity status))

  (let [orderId 5
        status "PreSubmitted"
        filled 0.0
        remaining 10.0
        avgFillPrice 0.0
        lastFillPrice 0.0]
    (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))



  ;; 2
  (let [symbol "AAPL"
        account "DU542121"
        orderId 5
        action "BUY"
        orderType "TRAIL LIMIT"
        quantity 10.0
        status "PreSubmitted"]
    (->openOrder wrapper symbol account orderId action orderType quantity status))

  (let [orderId 5
        status "PreSubmitted"
        filled 0.0
        remaining 10.0
        avgFillPrice 0.0
        lastFillPrice 0.0]
    (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))



  ;; 3
  (let [symbol "AAPL"
        account "DU542121"
        orderId 5
        action "BUY"
        orderType "TRAIL LIMIT"
        quantity 10.0
        status "Submitted"]
    (->openOrder wrapper symbol account orderId action orderType quantity status))

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
        account "DU542121"
        orderId 5
        action "BUY"
        orderType "TRAIL LIMIT"
        quantity 10.0
        status "Filled"]
    (->openOrder wrapper symbol account orderId action orderType quantity status))

  (let [orderId 5
        status "Filled"
        filled 10.0
        remaining 0.0
        avgFillPrice 218.49
        lastFillPrice 218.49]
    (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))



  ;; 5
  (let [symbol "AAPL"
        account "DU542121"
        orderId 5
        action "BUY"
        orderType "TRAIL LIMIT"
        quantity 10.0
        status "Filled"]
    (->openOrder wrapper symbol account orderId action orderType quantity status))

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
