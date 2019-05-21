(ns com.interrupt.ibgateway.component.account
  (:refer-clojure :exclude [*])
  (:require [mount.core :refer [defstate] :as mount]
            [com.rpl.specter :as s]
            [clojure.tools.logging :refer [info] :as log]
            [environ.core :refer [env]]
            [clojure.core.match :refer [match]]
            [clojure.core.async :as async :refer [go-loop <! >!!]]
            [automata.core :as au]
            [com.interrupt.ibgateway.component.common :refer [exists?]]
            [com.interrupt.ibgateway.component.account.contract :as contract]
            [com.interrupt.ibgateway.component.switchboard.mock :refer :all])
  (:import [com.ib.client Order OrderStatus]))


;; MKT (buy)
;; TRAIL (sell) - https://www.interactivebrokers.com/en/index.php?f=605 (greater protection for fast-moving stocks)
;; TRAIL LIMIT (sell) - https://www.interactivebrokers.com/en/index.php?f=606 (Not guaranteed an execution)
;;   https://www.fidelity.com/learning-center/trading-investing/trading/stop-loss-video
;;   https://www.thestreet.com/story/10273105/1/ask-thestreet-limits-and-losses.html
;;   https://money.stackexchange.com/questions/89018/stop-limit-vs-stop-market-vs-trailing-stop-limit-vs-trailing-stop-market

;; MKT
;; LMT
;; STP
;; STP LMT
;; TRAIL
;; TRAIL LIMIT

;; As the market price rises,
;; both the i. stop price and the ii. limit price rise by the
;;          i.i trail amount and  ii.i limit offset respectively,

;; stop price > trail amount
;; limit price > limit offset

(def example
  {:stock [{:symbol "AAPL"
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
                              :history [nil]}}]}

           {:symbol "TSLA"
            :amount 10
            :avgFillPrice 218.97
            :orders [{:orderId 4
                      :orderType "MKT"
                      :action "BUY"
                      :quantity 10.0
                      :price 218.95
                      :state {:states ({:matcher :pre-submitted} {:matcher :submitted} {:matcher :filled})
                              :run ({:matcher :pre-submitted} {:matcher :submitted} {:matcher :filled})
                              :state nil
                              :history [nil]}}]}]
   :cash 1000000})

;; (def account-name "DU542121"
;; (def account-name "U1932856"))
(def account-name (env :account-name "DU542121"))

(defstate account
  :start (atom {:stock [] :cash 0.0})
  :stop (reset! account nil))


(def account-summary-tags
  "AccountType,NetLiquidation,TotalCashValue,SettledCash,AccruedCash,BuyingPower,EquityWithLoanValue,PreviousEquityWithLoanValue,GrossPositionValue,ReqTEquity,ReqTMargin,SMA,InitMarginReq,MaintMarginReq,AvailableFunds,ExcessLiquidity,Cushion,FullInitMarginReq,FullMaintMarginReq,FullAvailableFunds,FullExcessLiquidity,LookAheadNextChange,LookAheadInitMarginReq ,LookAheadMaintMarginReq,LookAheadAvailableFunds,LookAheadExcessLiquidity,HighestSeverity,DayTradesRemaining,Leverage")

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

(defn stock-exists? [symbol state]
  ((comp not nil?)
   (s/select-one [:stock s/ALL #(= symbol (:symbol %))] state)))

(defn order-exists? [order-id state]
  ((comp not nil?)
   (s/select-one [:stock s/ALL :orders s/ALL #(= order-id (:orderId %))] state)))

(defn order->order-state [{:keys [orderId symbol secType exchange action
                                  orderType totalQuantity status]}]
  {:orderId orderId
   :orderType (str orderType)
   :action (str action)
   :quantity totalQuantity
   :price nil
   :state (au/automaton
            [(au/* :api-pending) (au/* :pending-submit) (au/* :pending-cancel) (au/* :pre-submitted)
             (au/* :submitted) (au/* :api-cancelled) (au/* :cancelled) (au/* :filled) (au/* :inactive)])})

(defn add-stock! [{:keys [orderId symbol secType exchange action
                          orderType totalQuantity status] :as val}
                  state]

  (let [order (order->order-state val)
        stock {:symbol symbol
               :amount totalQuantity
               :avgFillPrice nil
               :orders [order]}]

    (reset! state (s/transform [:stock] #(conj % stock) @state))))

(defn add-order! [{:keys [orderId symbol secType exchange action
                          orderType totalQuantity status] :as val}
                  state]

  (let [order (order->order-state val)]
    (reset! state (s/transform [:stock s/ALL #(= symbol (:symbol %)) :orders] #(conj % order) @state))))

(defn transition-order! [symbol order-id to-state account]

  (let [navigator [:stock s/ALL #(= symbol (:symbol %))
                   :orders s/ALL #(= order-id (:orderId %)) :state]]

    (reset! account (s/transform navigator #(au/advance % to-state) @account))))

(defn order-id->stock [order-id account]
  (s/select [:stock s/ALL s/VAL :orders s/ALL #(= order-id (:orderId %))] @account))

(defn exec-id->stock [state execId]
  (s/select [:stock s/ALL s/VAL :orders s/ALL #(= execId (:exec-id %))] state))

(defn bind-order-id->exec-id! [order-id exec-id state]
  (reset! state (s/transform [:stock s/ALL :orders s/ALL #(= order-id (:orderId %))]
                             #(assoc % :exec-id exec-id)
                             @state)))

(defn bind-exec-id->commission-report! [state {:keys [execId commission realizedPNL] :as val}]
  (reset! state (s/transform [:stock s/ALL :orders s/ALL #(= execId (:exec-id %))]
                             #(assoc % :commission commission :realizedPNL realizedPNL)
                             @state)))

(defn bind-price->order! [{:keys [orderId status filled remaining avgFillPrice permId
                                  parentId lastFillPrice clientId whorderIdyHeld] :as val}
                          state]
  (swap! state
         (fn [s]
           (s/transform [:stock s/ALL :orders s/ALL #(= orderId (:orderId %))]
                        #(assoc % :avgFillPrice avgFillPrice :price lastFillPrice)
                        s))))

(defn conditionally-process-filled [[[stock order]]]
  (info "2 - conditionally-process-filled / " [(exists? stock) (exists? order)]
        " / 1 / " #_(-> order :state)
        " / 2 / " (-> order :state :state)
        " / 3 / " (-> order :state :state :matcher))
  (info "2 - conditionally-process-filled / action / " (:action order))
  (info "2 - conditionally-process-filled / state / " (-> order :state :state :matcher))
  (info "2 - conditionally-process-filled / match? / " (and
                                                         (= "BUY" (:action order))
                                                         (= :filled (-> order :state :state :matcher))))
  (when (and
          (= "BUY" (:action order))
          (= :filled (-> order :state :state :matcher)))
    (info "2 - returning from conditionally-process-filled")
    {:stock stock :order order}))

(defn conditionally-notify-filled [[[stock order]] order-filled-notification-ch]
  (info "3 - conditionally-notify-filled / " [(exists? stock) (exists? order)]
        " / 1 / " #_(-> order :state)
        " / 2 / " (-> order :state :state)
        " / 3 / " (-> order :state :state :matcher))
  (info "3 - conditionally-notify-filled / action / " (:action order))
  (info "3 - conditionally-notify-filled / state / " (-> order :state :state :matcher))
  (info "3 - conditionally-notify-filled / match? / " (and
                                                        (= "BUY" (:action order))
                                                        (= :filled (-> order :state :state :matcher))))
  (when (and
          (= "BUY" (:action order))
          (= :filled (-> order :state :state :matcher)))
    (info "3 - sending to order-filled-notification-ch")
    (>!! order-filled-notification-ch {:stock stock :order order}))
  [stock order])


;; OPEN ORDER
(defmulti handle-open-order (fn [{:keys [action orderType]}]
                              [(str action) (str orderType)]))

(defmethod handle-open-order ["BUY" "MKT"]
  [{:keys [orderId symbol secType exchange action
           orderType totalQuantity status] :as val}]

  (let [sexists? (stock-exists? symbol @account)
        oexists? (order-exists? orderId @account)]

    (match [sexists? oexists?]
           [false false] (add-stock! val account)
           [true false] (add-order! val account)
           :else :noop))

  (let [status-kw (get order-status-map status)]
    (transition-order! symbol orderId status-kw account)))

(defmethod handle-open-order ["SELL" "TRAIL"]
  [{:keys [orderId symbol secType exchange action
           orderType totalQuantity status] :as val}]

  (let [sexists? (stock-exists? symbol @account)
        oexists? (order-exists? orderId @account)]

    (match [sexists? oexists?]
           [false false] (add-stock! val account)
           [true false] (add-order! val account)
           :else :no-match))

  (let [status-kw (get order-status-map status)]
    (transition-order! symbol orderId status-kw account)))

(defmethod handle-open-order ["SELL" "TRAIL LIMIT"]
  [{:keys [orderId symbol secType exchange action
           orderType totalQuantity status] :as val}]

  (let [sexists? (stock-exists? symbol @account)
        oexists? (order-exists? orderId @account)]

    (match [sexists? oexists?]
           [false false] (add-stock! val account)
           [true false] (add-order! val account)
           :else :no-match))

  (let [status-kw (get order-status-map status)]
    (transition-order! symbol orderId status-kw account)))


;; ORDER STATUS
(defn order-status-base [{:keys [orderId status filled remaining avgFillPrice permId
                                 parentId lastFillPrice clientId whorderIdyHeld] :as val}
                         account]

  (let [status-kw (get order-status-map status)
        [{symbol :symbol} order] (order-id->stock orderId account)
        same-status? (= status-kw (-> order :state :state :matcher))]

    (when-not same-status?
      (transition-order! symbol orderId status-kw account))))

(defmulti handle-order-status (fn [{status :status} _] status))

(defmethod handle-order-status "Filled" [val account]
  (bind-price->order! val account)
  (order-status-base val account))

(defmethod handle-order-status :default [val account]
  (order-status-base val account))


;; EXEC DETAILS

;; There are not guaranteed to be orderStatus callbacks for every change in order status.
;; For example with market orders when the order is accepted and executes immediately,
;; there commonly will not be any corresponding orderStatus callbacks.

;; TODO what's the meaning of this callback, if an order hasn't been filled
(defn handle-exec-details [{:keys [reqId symbol secType currency
                                   execId orderId shares] :as val}
                           account]
  (bind-order-id->exec-id! orderId execId account))


;; COMMISSION REPORT
(defn process-commission-report [{:keys [execId commission
                                        currency realizedPNL] :as val}
                                 account]
  (info "2 - handle-commission-report / " val)
  (-> account
      (bind-exec-id->commission-report! val)
      (exec-id->stock execId)
      (conditionally-process-filled)))

(defn handle-commission-report [{:keys [execId commission
                                        currency realizedPNL] :as val}
                                account order-filled-notification-ch]
  (info "2 - handle-commission-report / " val)
  (-> account
      (bind-exec-id->commission-report! val)
      (exec-id->stock execId)
      (conditionally-notify-filled order-filled-notification-ch)))


;; CONSUME ORDER UPDATES
(defn consume-order-updates [{:keys [order-updates valid-order-ids order-filled-notifications]}]
  (go-loop [{:keys [topic] :as val} (<! order-updates)]
    (info "1 - consume-order-updates LOOP / " val)
    (case topic
      :open-order (handle-open-order val)
      :order-status (handle-order-status val account)
      :next-valid-id (let [{oid :order-id} val]
                       (>!! valid-order-ids oid))
      :exec-details (handle-exec-details val account)
      :commission-report :noop ;; (handle-commission-report val account order-filled-notifications)
      :default)

    (recur (<! order-updates))))
