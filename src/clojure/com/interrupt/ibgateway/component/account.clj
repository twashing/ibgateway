(ns com.interrupt.ibgateway.component.account
  (:require [mount.core :refer [defstate] :as mount]
            [com.rpl.specter :as s]
            [clojure.tools.logging :refer [info] :as log]
            [clojure.core.async :as async :refer [go-loop <!]]
            [automata.core :as au]
            [com.interrupt.ibgateway.component.ewrapper :as ew]
            [com.interrupt.ibgateway.component.switchboard.mock :refer :all])
  (:import [com.ib.client OrderStatus]))


;; ** Track
;;   stock level (which stock, how much)
;;   cash level (how much)

(defstate account
  :start (atom {:stock []
                :cash 0.0})
  :stop (reset! account nil))


(def order-status-map
  {com.ib.client.OrderStatus/ApiPending :api-pending
   com.ib.client.OrderStatus/ApiCancelled :api-cancelled
   com.ib.client.OrderStatus/PreSubmitted :pre-submitted
   com.ib.client.OrderStatus/PendingCancel :pending-cancel
   com.ib.client.OrderStatus/Cancelled :cancelled
   com.ib.client.OrderStatus/Submitted :submitted
   com.ib.client.OrderStatus/Filled :filled
   com.ib.client.OrderStatus/Inactive :inactive
   com.ib.client.OrderStatus/PendingSubmit :pending-submit
   com.ib.client.OrderStatus/Unknown :unknown})

(def example {:stock [{:symbol "AAPL"
                       :amount 10
                       :avgFillPrice 218.97
                       :orders [{:orderId 3
                                 :orderType "MKT"
                                 :action "BUY"
                                 :quantity 10.0
                                 :price 218.95
                                 :state {:states ({:matcher :pre-submitted} {:matcher :submitted} {:matcher :filled})
                                         :run ({:matcher :pre-submitted} {:matcher :submitted} {:matcher :filled})
                                         :state nil
                                         :history [nil]}}]}]
              :cash 1000000})


(defn stock-exists? [symbol state]
  ((comp not nil?)
   (s/select-one [:stock s/ALL #(= symbol (:symbol %))] state)))

(defn add-stock! [{:keys [orderId symbol secType exchange action
                          orderType totalQuantity status] :as val}
                  state]

  (let [automaton (au/automaton [(au/* :api-pending) (au/* :pending-submit) (au/* :pending-cancel) (au/* :pre-submitted)
                                 (au/* :submitted) (au/* :api-cancelled) (au/* :cancelled) (au/* :filled) (au/* :inactive)])
        order {:orderId orderId
               :orderType (str orderType)
               :action (str action)
               :quantity totalQuantity
               :price nil
               :state automaton}
        stock {:symbol symbol
               :amount totalQuantity
               :avgFillPrice nil
               :orders [order]}]

    (reset! state (s/transform [:stock] #(conj % stock) @state))))


(defn transition-order! [symbol order-id to-state account]

  (let [navigator [:stock s/ALL #(= symbol (:symbol %))
                   :orders s/ALL #(= order-id (:orderId %)) :state]]

    (reset! account (s/transform navigator #(au/advance % to-state) @account))))

(defmulti handle-order (fn [{:keys [topic action orderType]}]
                         [topic (str action) (str orderType)]))

(defmethod handle-order [:open-order "BUY" "MKT"]
  [{:keys [orderId symbol secType exchange action
           orderType totalQuantity status] :as val}]

  (println "handle-order / " val)

  (s/select [s/ALL] example)
  (s/select-one [:stock s/ALL #(= "AAPL" (:symbol %))] example)
  (stock-exists? "AAPL" example)
  (stock-exists? "FOOB" example)


  (when-not (stock-exists? symbol @account)
    (add-stock! val account))

  (let [status-kw (get order-status-map status)]

    (println (get order-status-map status))
    (transition-order! symbol orderId status-kw account)))


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



  (require '[automata.core :as au])

  (def a (au/automaton [:pre-submitted :submitted :filled]))
  (-> a (au/advance :pre-submitted))
  (-> a (au/advance :pre-submitted) (u/advance :submitted))



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
    (def account-name "DU542121")
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


  (require '[automata.core :as au])
  (def a (au/automaton [(au/* :api-pending) (au/* :pending-submit) (au/* :pending-cancel) (au/* :pre-submitted)
                        (au/* :submitted) (au/* :api-cancelled) (au/* :cancelled) (au/* :filled) (au/* :inactive)]))
  (def a (au/automaton [(au/* :pre-submitted) (au/* :submitted) (au/* :filled)]))


  (-> a (au/advance :pre-submitted) (u/advance :submitted))
  (-> a (au/advance :pre-submitted) (u/advance :submitted) (u/advance :filled))


  (do
    (mount/stop #'ew/default-chs-map #'ew/ewrapper #'account)
    (mount/start #'ew/default-chs-map #'ew/ewrapper #'account)
    (def wrapper (:wrapper ew/ewrapper))
    (def account-name "DU542121")


    ;; NOTE
    ;; this happens after we've submitted a MKT (buy)

    (let [{:keys [order-updates]} ew/default-chs-map]
      (go-loop [{:keys [topic] :as val} (<! order-updates)]
        (info "go-loop / order-updates / topic /" val)

        (handle-order val)

        ;; TODO
        ;; ** track by order Id
        ;; track state transitions
        ;; track stock level

        ;; [ok] dispatch by :topic
        ;; updates account state
        ;;   add or update stock + cash levels
        ;;   track workflow state

        ;; i) when an order is a buy, ii) when an order is filled
        ;;   perform an equal and opposite corresponding TRAIL (sell)

        (recur (<! order-updates)))))


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
        avgFillPrice 218.96
        lastFillPrice 218.96]
    (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

  ;; 8
  (let [commission 0.382257
        currency "USD"
        realizedPNL 1.7976931348623157E308]
    (->commissionReport wrapper commission currency realizedPNL)))

(comment  ;; TRAIL LIMIT (sell)

    ;; 1
  (let [symbol "AAPL"
        account-name "DU542121"
        orderId 5
        action "BUY"
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
        action "BUY"
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
        action "BUY"
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
        action "BUY"
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
        action "BUY"
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
