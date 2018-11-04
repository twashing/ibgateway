(ns com.interrupt.ibgateway.component.ewrapper
  (:require [environ.core :refer [env]]
            [mount.core :as mount :refer [defstate]]
            [clojure.core.async :as async :refer [close!]]
            [com.interrupt.ibgateway.component.account.contract :as contract]
            [com.interrupt.ibgateway.component.ewrapper-impl :as ewi]
            [com.interrupt.ibgateway.component.account.common :refer [next-reqid! release-reqid!]])
  (:import [com.ib.client Order]))


(def tws-host (env :tws-host "tws"))
(def tws-port (env :tws-port 4002))
(def client-id (atom (next-reqid!)))

(defstate default-chs-map
  :start {:publisher (-> 1000 async/sliding-buffer async/chan)
          :account-updates (-> 1000 async/sliding-buffer async/chan)}
  :stop (->> default-chs-map vals (map close!)))

(defstate ewrapper
  :start (ewi/ewrapper tws-host tws-port @client-id default-chs-map)
  :stop (let [{client :client} ewrapper]
          (when (.isConnected client)
            (.eDisconnect client))
          (release-reqid! @client-id)
          (reset! client-id -1)))



(comment


  ;; (openOrder [^Integer orderId
  ;;             ^Contract contract
  ;;             ^Order order
  ;;             ^OrderState orderState]
  ;;
  ;;            (info "openOrder / orderId " orderId
  ;;                  " Contract::symbol / " (.symbol contract)
  ;;                  " Contract::secType / " (.secType contract)
  ;;                  " Contract::exchange / " (.exchange contract)
  ;;                  " Order::action / " (.action order)
  ;;                  " Order::orderType / " (.orderType order)
  ;;                  " Order::totalQuantity / " (.totalQuantity order)
  ;;                  " OrderState::status / " (.status orderState)))
  ;;
  ;; (orderStatus [^Integer orderId
  ;;                 ^String status
  ;;                 ^Double filled
  ;;                 ^Double remaining
  ;;                 ^Double avgFillPrice
  ;;                 ^Integer permId
  ;;                 ^Integer parentId
  ;;                 ^Double lastFillPrice
  ;;                 ^Integer clientId
  ;;                 ^String whyHeld]
  ;;
  ;;     (info "orderStatus /"
  ;;           " Id / " orderId
  ;;           " Status / " status
  ;;           " Filled" filled
  ;;           " Remaining / " remaining
  ;;           " AvgFillPrice / " avgFillPrice
  ;;           " PermId / " permId
  ;;           " ParentId / " parentId
  ;;           " LastFillPrice / " lastFillPrice
  ;;           " ClientId / " clientId
  ;;           " WhyHeld / " whyHeld))
  ;;
  ;; (execDetails [^Integer reqId
  ;;               ^Contract contract
  ;;               ^Execution execution]
  ;;
  ;;              (info "execDetails / "
  ;;                    " reqId / " reqId
  ;;                    " symbol / " (.symbol contract)
  ;;                    " secType / " (.secType contract)
  ;;                    " currency / " (.currency contract)
  ;;                    " execId / " (.execId execution)
  ;;                    " orderId / " (.orderId execution)
  ;;                    " shares / " (.shares execution)))
  ;;
  ;; (commissionReport [^CommissionReport commissionReport]
  ;;
  ;;                   (info "commissionReport / "
  ;;                         " execId / " (.-m_execId commissionReport)
  ;;                         " commission / " (.-m_commission commissionReport)
  ;;                         " currency / " (.-m_currency commissionReport)
  ;;                         " realizedPNL / " (.-m_realizedPNL commissionReport)))


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
  :realized-pnl

  )

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



  ;; MKT buy
  ;; INFO [2018-10-29 14:33:25,814] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  3  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x6665526d BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x748abff5 MKT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x2fa975eb PreSubmitted]
  ;; INFO [2018-10-29 14:33:25,834] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  3  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  652797928  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate
  ;; INFO [2018-10-29 14:33:26,129] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl execDetails /   reqId /  -1  symbol /  AAPL  secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  currency /  USD  execId /  00018037.5beb36f0.01.01  orderId /  3  shares /  10.0
  ;; INFO [2018-10-29 14:33:26,137] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  3  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x6665526d BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x748abff5 MKT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x2618165c Filled]
  ;; INFO [2018-10-29 14:33:26,141] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  3  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  218.96  PermId /  652797928  ParentId /  0  LastFillPrice /  218.96  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-29 14:33:26,158] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  3  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x6665526d BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x748abff5 MKT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x2618165c Filled]
  ;; INFO [2018-10-29 14:33:26,227] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  3  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  218.96  PermId /  652797928  ParentId /  0  LastFillPrice /  218.96  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-29 14:33:26,239] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl commissionReport /   execId /  00018037.5beb36f0.01.01  commission /  0.382257  currency /  USD  realizedPNL /  1.7976931348623157E308

  ;; MKT sell
  ;; INFO [2018-10-29 14:35:32,889] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  4  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x6a3a723c SELL]  Order::orderType /  #object[com.ib.client.OrderType 0x748abff5 MKT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x2fa975eb PreSubmitted]
  ;; INFO [2018-10-29 14:35:32,892] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  4  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  652797929  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate
  ;; INFO [2018-10-29 14:35:32,965] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl execDetails /   reqId /  -1  symbol /  AAPL  secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  currency /  USD  execId /  00018037.5beb3971.01.01  orderId /  4  shares /  10.0
  ;; INFO [2018-10-29 14:35:32,968] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  4  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x6a3a723c SELL]  Order::orderType /  #object[com.ib.client.OrderType 0x748abff5 MKT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x2618165c Filled]
  ;; INFO [2018-10-29 14:35:32,972] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  4  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  218.6  PermId /  652797929  ParentId /  0  LastFillPrice /  218.6  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-29 14:35:32,975] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  4  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x6a3a723c SELL]  Order::orderType /  #object[com.ib.client.OrderType 0x748abff5 MKT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x2618165c Filled]
  ;; INFO [2018-10-29 14:35:32,978] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  4  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  218.6  PermId /  652797929  ParentId /  0  LastFillPrice /  218.6  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-29 14:35:32,980] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl commissionReport /   execId /  00018037.5beb3971.01.01  commission /  0.411865  currency /  USD  realizedPNL /  -4.394122



  ;; LMT buy
  ;; INFO [2018-10-30 16:16:10,078] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl position /   Account /  DU542121  Symbol /  AAPL  SecType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Currency /  USD  Position /  20.0  Avg cost /  218.530225
  ;; INFO [2018-10-30 16:16:11,171] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  7  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x2d6307b4 BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x3ab8c6b2 LMT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x4b3b8f6 PreSubmitted]
  ;; INFO [2018-10-30 16:16:11,174] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  7  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  1441238025  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate
  ;; INFO [2018-10-30 16:16:11,219] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl position /   Account /  DU542121  Symbol /  AAPL  SecType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Currency /  USD  Position /  30.0  Avg cost /  216.52348333333333
  ;; INFO [2018-10-30 16:16:11,392] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl execDetails /   reqId /  -1  symbol /  AAPL  secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  currency /  USD  execId /  00018037.5bed40e4.01.01  orderId /  7  shares /  10.0
  ;; INFO [2018-10-30 16:16:11,397] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  7  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x2d6307b4 BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x3ab8c6b2 LMT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x3814f2dd Filled]
  ;; INFO [2018-10-30 16:16:11,399] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  7  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  212.51  PermId /  1441238025  ParentId /  0  LastFillPrice /  212.51  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-30 16:16:11,436] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  7  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x2d6307b4 BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x3ab8c6b2 LMT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x3814f2dd Filled]
  ;; INFO [2018-10-30 16:16:11,438] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  7  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  212.51  PermId /  1441238025  ParentId /  0  LastFillPrice /  212.51  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-30 16:16:11,519] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl commissionReport /   execId /  00018037.5bed40e4.01.01  commission /  0.351257  currency /  USD  realizedPNL /  1.7976931348623157E308

  ;; LMT sell
  ;; INFO [2018-10-30 16:28:57,173] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  8  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x8cef195 SELL]  Order::orderType /  #object[com.ib.client.OrderType 0x3ab8c6b2 LMT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x4b3b8f6 PreSubmitted]
  ;; INFO [2018-10-30 16:28:57,176] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  8  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  1441238026  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate
  ;; INFO [2018-10-30 16:28:57,250] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl position /   Account /  DU542121  Symbol /  AAPL  SecType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Currency /  USD  Position /  20.0  Avg cost /  216.5351919
  ;; INFO [2018-10-30 16:28:57,259] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl execDetails /   reqId /  -1  symbol /  AAPL  secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  currency /  USD  execId /  00018037.5bed44e0.01.01  orderId /  8  shares /  10.0
  ;; INFO [2018-10-30 16:28:57,263] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  8  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x8cef195 SELL]  Order::orderType /  #object[com.ib.client.OrderType 0x3ab8c6b2 LMT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x3814f2dd Filled]
  ;; INFO [2018-10-30 16:28:57,265] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  8  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  213.2  PermId /  1441238026  ParentId /  0  LastFillPrice /  213.2  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-30 16:28:57,267] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  8  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x8cef195 SELL]  Order::orderType /  #object[com.ib.client.OrderType 0x3ab8c6b2 LMT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x3814f2dd Filled]
  ;; INFO [2018-10-30 16:28:57,269] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  8  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  213.2  PermId /  1441238026  ParentId /  0  LastFillPrice /  213.2  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-30 16:28:57,270] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl commissionReport /   execId /  00018037.5bed44e0.01.01  commission /  0.376163  currency /  USD  realizedPNL /  -33.728082
  ;; INFO [2018-10-30 16:20:13,647] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl position /   Account /  DU542121  Symbol /  AAPL  SecType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Currency /  USD  Position /  30.0  Avg cost /  216.5351919



  ;; STP buy
  ;; INFO [2018-10-30 16:30:45,661] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  9  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x2d6307b4 BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x590bcec7 STP]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x4b3b8f6 PreSubmitted]
  ;; INFO [2018-10-30 16:30:45,664] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  9  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  1441238027  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate,trigger
  ;; INFO [2018-10-30 16:30:50,874] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  9  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x2d6307b4 BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x590bcec7 STP]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x4b3b8f6 PreSubmitted]
  ;; INFO [2018-10-30 16:30:50,877] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  9  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  1441238027  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate,trigger
  ;; INFO [2018-10-30 16:30:50,954] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  9  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x2d6307b4 BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x590bcec7 STP]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x420cec91 Submitted]
  ;; INFO [2018-10-30 16:30:50,997] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  9  Status /  Submitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  1441238027  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-30 16:30:50,998] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl position /   Account /  DU542121  Symbol /  AAPL  SecType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Currency /  USD  Position /  30.0  Avg cost /  215.4067946
  ;; INFO [2018-10-30 16:30:51,000] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl execDetails /   reqId /  -1  symbol /  AAPL  secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  currency /  USD  execId /  00018037.5bed45b3.01.01  orderId /  9  shares /  10.0
  ;; INFO [2018-10-30 16:30:51,002] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  9  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x2d6307b4 BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x590bcec7 STP]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x3814f2dd Filled]
  ;; INFO [2018-10-30 16:30:51,004] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  9  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  213.15  PermId /  1441238027  ParentId /  0  LastFillPrice /  213.15  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-30 16:30:51,006] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  9  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x2d6307b4 BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x590bcec7 STP]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x3814f2dd Filled]
  ;; INFO [2018-10-30 16:30:51,007] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  9  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  213.15  PermId /  1441238027  ParentId /  0  LastFillPrice /  213.15  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-30 16:30:51,009] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl commissionReport /   execId /  00018037.5bed45b3.01.01  commission /  0.352257  currency /  USD  realizedPNL /  1.7976931348623157E308

  ;; STP sell
  ;; INFO [2018-10-30 16:32:23,681] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  10  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x8cef195 SELL]  Order::orderType /  #object[com.ib.client.OrderType 0x590bcec7 STP]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x4b3b8f6 PreSubmitted]
  ;; INFO [2018-10-30 16:32:23,685] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  10  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  1441238028  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate,trigger
  ;; INFO [2018-10-30 16:32:28,560] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  10  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x8cef195 SELL]  Order::orderType /  #object[com.ib.client.OrderType 0x590bcec7 STP]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x4b3b8f6 PreSubmitted]
  ;; INFO [2018-10-30 16:32:28,563] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  10  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  1441238028  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate,trigger
  ;; INFO [2018-10-30 16:32:28,650] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl position /   Account /  DU542121  Symbol /  AAPL  SecType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Currency /  USD  Position /  20.0  Avg cost /  215.41853650000002
  ;; INFO [2018-10-30 16:32:28,657] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl execDetails /   reqId /  -1  symbol /  AAPL  secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  currency /  USD  execId /  00018037.5bed466f.01.01  orderId /  10  shares /  10.0
  ;; INFO [2018-10-30 16:32:28,688] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  10  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x8cef195 SELL]  Order::orderType /  #object[com.ib.client.OrderType 0x590bcec7 STP]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x3814f2dd Filled]
  ;; INFO [2018-10-30 16:32:28,690] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  10  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  212.85  PermId /  1441238028  ParentId /  0  LastFillPrice /  212.85  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-30 16:32:28,692] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  10  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x8cef195 SELL]  Order::orderType /  #object[com.ib.client.OrderType 0x590bcec7 STP]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x3814f2dd Filled]
  ;; INFO [2018-10-30 16:32:28,694] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  10  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  212.85  PermId /  1441238028  ParentId /  0  LastFillPrice /  212.85  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-30 16:32:28,700] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl commissionReport /   execId /  00018037.5bed466f.01.01  commission /  0.411118  currency /  USD  realizedPNL /  -26.096483
  ;; INFO [2018-10-30 16:33:43,808] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl position /   Account /  DU542121  Symbol /  AAPL  SecType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Currency /  USD  Position /  20.0  Avg cost /  215.4185365



  ;; STP LMT buy
  ;; INFO [2018-10-30 16:36:03,542] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  11  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x2d6307b4 BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x568de3dc STP LMT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x4b3b8f6 PreSubmitted]
  ;; INFO [2018-10-30 16:36:03,545] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  11  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  1441238029  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate,trigger
  ;; INFO [2018-10-30 16:36:05,580] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  11  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x2d6307b4 BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x568de3dc STP LMT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x4b3b8f6 PreSubmitted]
  ;; INFO [2018-10-30 16:36:05,583] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  11  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  1441238029  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate,trigger
  ;; INFO [2018-10-30 16:36:05,676] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  11  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x2d6307b4 BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x568de3dc STP LMT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x420cec91 Submitted]
  ;; INFO [2018-10-30 16:36:05,679] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  11  Status /  Submitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  1441238029  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  nil

  ;; STP LMT sell
  ;; INFO [2018-10-30 16:57:19,942] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  12  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x8cef195 SELL]  Order::orderType /  #object[com.ib.client.OrderType 0x568de3dc STP LMT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x4b3b8f6 PreSubmitted]
  ;; INFO [2018-10-30 16:57:19,948] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  12  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  1441238030  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate,trigger
  ;; INFO [2018-10-30 16:57:20,902] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  12  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x8cef195 SELL]  Order::orderType /  #object[com.ib.client.OrderType 0x568de3dc STP LMT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x4b3b8f6 PreSubmitted]
  ;; INFO [2018-10-30 16:57:20,914] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  12  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  1441238030  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate,trigger
  ;; INFO [2018-10-30 16:57:20,977] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  12  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x35964723 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x8cef195 SELL]  Order::orderType /  #object[com.ib.client.OrderType 0x568de3dc STP LMT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x420cec91 Submitted]
  ;; INFO [2018-10-30 16:57:20,979] clojure-agent-send-off-pool-4 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  12  Status /  Submitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  1441238030  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  nil



  ;; TRAIL
  ;; INFO [2018-10-29 14:46:09,958] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  6  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x6665526d BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x6b2499af TRAIL]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x2fa975eb PreSubmitted]
  ;; INFO [2018-10-29 14:46:09,967] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  6  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  652797931  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate,trigger
  ;; INFO [2018-10-29 14:46:10,573] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  6  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x6665526d BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x6b2499af TRAIL]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x2fa975eb PreSubmitted]
  ;; INFO [2018-10-29 14:46:10,575] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  6  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  652797931  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate,trigger
  ;; INFO [2018-10-29 14:46:10,651] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl execDetails /   reqId /  -1  symbol /  AAPL  secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  currency /  USD  execId /  00018037.5beb464e.01.01  orderId /  6  shares /  10.0
  ;; INFO [2018-10-29 14:46:10,653] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  6  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x6665526d BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x6b2499af TRAIL]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x2618165c Filled]
  ;; INFO [2018-10-29 14:46:10,682] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  6  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  218.5  PermId /  652797931  ParentId /  0  LastFillPrice /  218.5  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-29 14:46:10,686] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  6  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x6665526d BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x6b2499af TRAIL]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x2618165c Filled]
  ;; INFO [2018-10-29 14:46:10,688] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  6  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  218.5  PermId /  652797931  ParentId /  0  LastFillPrice /  218.5  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-29 14:46:10,690] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl commissionReport /   execId /  00018037.5beb464e.01.01  commission /  0.352257  currency /  USD  realizedPNL /  1.7976931348623157E308


  ;; TRAIL LIMIT
  ;; INFO [2018-10-29 14:41:42,205] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  5  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x6665526d BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x1ef04a1f TRAIL LIMIT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x2fa975eb PreSubmitted]
  ;; INFO [2018-10-29 14:41:42,214] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  5  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  652797930  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate,trigger
  ;; INFO [2018-10-29 14:41:50,963] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  5  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x6665526d BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x1ef04a1f TRAIL LIMIT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x2fa975eb PreSubmitted]
  ;; INFO [2018-10-29 14:41:50,966] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  5  Status /  PreSubmitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  652797930  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate,trigger
  ;; INFO [2018-10-29 14:41:51,037] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  5  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x6665526d BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x1ef04a1f TRAIL LIMIT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x771885e1 Submitted]
  ;; INFO [2018-10-29 14:41:51,038] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  5  Status /  Submitted  Filled 0.0  Remaining /  10.0  AvgFillPrice /  0.0  PermId /  652797930  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-29 14:41:51,046] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl execDetails /   reqId /  -1  symbol /  AAPL  secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  currency /  USD  execId /  00018037.5beb413b.01.01  orderId /  5  shares /  10.0
  ;; INFO [2018-10-29 14:41:51,048] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  5  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x6665526d BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x1ef04a1f TRAIL LIMIT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x2618165c Filled]
  ;; INFO [2018-10-29 14:41:51,050] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  5  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  218.49  PermId /  652797930  ParentId /  0  LastFillPrice /  218.49  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-29 14:41:51,069] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl openOrder / orderId  5  Contract::symbol /  AAPL  Contract::secType /  #object[com.ib.client.Types$SecType 0x43ebe636 STK]  Contract::exchange /  SMART  Order::action /  #object[com.ib.client.Types$Action 0x6665526d BUY]  Order::orderType /  #object[com.ib.client.OrderType 0x1ef04a1f TRAIL LIMIT]  Order::totalQuantity /  10.0  OrderState::status /  #object[com.ib.client.OrderStatus 0x2618165c Filled]
  ;; INFO [2018-10-29 14:41:51,072] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  5  Status /  Filled  Filled 10.0  Remaining /  0.0  AvgFillPrice /  218.49  PermId /  652797930  ParentId /  0  LastFillPrice /  218.49  ClientId /  1  WhyHeld /  nil
  ;; INFO [2018-10-29 14:41:51,074] clojure-agent-send-off-pool-7 - com.interrupt.ibgateway.component.ewrapper-impl commissionReport /   execId /  00018037.5beb413b.01.01  commission /  0.352257  currency /  USD  realizedPNL /  1.7976931348623157E308

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
