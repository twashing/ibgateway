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
  ;; Exception: No matching method found: openOrder for class java.lang.Class
  ;; INFO [2018-10-26 16:07:18,797] clojure-agent-send-off-pool-5 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  3  Status /  PreSubmitted  Filled 0.0  Remaining /  3.0  AvgFillPrice /  0.0  PermId /  756234963  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate

  ;; Exception: No matching field found: lastLiquidity for class com.ib.client.Execution
  ;; Exception: No matching method found: openOrder for class java.lang.Class

  ;; INFO [2018-10-26 16:07:19,327] clojure-agent-send-off-pool-5 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  3  Status /  Filled  Filled 3.0  Remaining /  0.0  AvgFillPrice /  217.03  PermId /  756234963  ParentId /  0  LastFillPrice /  217.03  ClientId /  1  WhyHeld /  nil

  ;; Exception: No matching method found: openOrder for class java.lang.Class

  ;; INFO [2018-10-26 16:07:19,360] clojure-agent-send-off-pool-5 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  3  Status /  Filled  Filled 3.0  Remaining /  0.0  AvgFillPrice /  217.03  PermId /  756234963  ParentId /  0  LastFillPrice /  217.03  ClientId /  1  WhyHeld /  nil

  ;; INFO [2018-10-26 16:07:19,366] clojure-agent-send-off-pool-5 - com.interrupt.ibgateway.component.ewrapper-impl commissionReport /   execId /  00018037.5be852c5.01.01  commission /  0.359857  currency /  USD  realizedPNL /  1.7976931348623157E308


  ;; MKT sell
  ;; Exception: No matching method found: openOrder for class java.lang.Class

  ;; INFO [2018-10-26 16:10:17,053] clojure-agent-send-off-pool-5 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  4  Status /  PreSubmitted  Filled 0.0  Remaining /  3.0  AvgFillPrice /  0.0  PermId /  756234964  ParentId /  0  LastFillPrice /  0.0  ClientId /  1  WhyHeld /  locate

  ;; Exception: No matching field found: lastLiquidity for class com.ib.client.Execution
  ;; Exception: No matching method found: openOrder for class java.lang.Class

  ;; INFO [2018-10-26 16:10:17,138] clojure-agent-send-off-pool-5 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  4  Status /  Filled  Filled 3.0  Remaining /  0.0  AvgFillPrice /  216.8  PermId /  756234964  ParentId /  0  LastFillPrice /  216.8  ClientId /  1  WhyHeld /  nil

  ;; Exception: No matching method found: openOrder for class java.lang.Class

  ;; INFO [2018-10-26 16:10:17,144] clojure-agent-send-off-pool-5 - com.interrupt.ibgateway.component.ewrapper-impl orderStatus /  Id /  4  Status /  Filled  Filled 3.0  Remaining /  0.0  AvgFillPrice /  216.8  PermId /  756234964  ParentId /  0  LastFillPrice /  216.8  ClientId /  1  WhyHeld /  nil

  ;; INFO [2018-10-26 16:10:17,146] clojure-agent-send-off-pool-5 - com.interrupt.ibgateway.component.ewrapper-impl commissionReport /   execId /  00018037.5be85443.01.01  commission /  0.368669  currency /  USD  realizedPNL /  -1.418527

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

  ;; BUY
  (.placeOrder client
               valid-order-id
               (contract/create "AAPL")
               (doto (Order.)
                 (.action "BUY")
                 (.orderType "MKT")
                 (.totalQuantity 3)
                 (.account account)))

  ;; SELL
  (.placeOrder client
                4 ;;valid-order-id
               (contract/create "AAPL")
               (doto (Order.)
                 (.action "SELL")
                 (.orderType "MKT")
                 (.totalQuantity 3)
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
        limitPrice ".."]

    (.placeOrder client
                 valid-order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType "LMT")
                   (.totalQuantity quantity)
                   (.lmtPrice limitPrice))))


  ;; STP
  (let [action "BUY"
        quantity 10
        stopPrice ".."]

    (.placeOrder client
                 valid-order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType "STP")
                   (.auxPrice stopPrice)
                   (.totalQuantity quantity))))


  ;; STP LMT
  (let [action "BUY"
        quantity 10
        stopPrice ".."
        limitPrice ".."]

    (.placeOrder client
                 valid-order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType "STP LMT")
                   (.lmtPrice limitPrice)
                   (.auxPrice stopPrice)
                   (.totalQuantity quantity))))


  ;; TRAIL
  (let [action "BUY"
        quantity 10
        trailingPercent ".."
        trailStopPrice ".."]

    (.placeOrder client
                 valid-order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType "TRAIL")
                   (.trailingPercent trailingPercent)
                   (.trailStopPrice trailStopPrice)
                   (.totalQuantity quantity))))

  ;; TRAIL LIMIT
  (let [action "BUY"
        quantity 10
        lmtPriceOffset ".."
        trailingAmount ".."
        trailStopPrice ".."]

    (.placeOrder client
                 valid-order-id
                 (contract/create "AAPL")
                 (doto (Order.)
                   (.action action)
                   (.orderType "TRAIL LIMIT")
                   (.lmtPriceOffset lmtPriceOffset)
                   (.auxPrice trailingAmount)
                   (.trailStopPrice trailStopPrice)
                   (.totalQuantity quantity)))))
