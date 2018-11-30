(ns com.interrupt.ibgateway.component.switchboard.mock
  (:require [com.interrupt.ibgateway.component.account.contract :as contract])
  (:import [com.ib.client Order OrderState OrderStateWrapper Execution CommissionReport]))


(defn ->openOrder [wrapper symbol account orderId orderType
                   action quantity status]

  (let [;; symbol "AAPL"
        ;; account "DU542121"
        ;; orderId 1

        contract (contract/create symbol)
        order (doto (Order.)
                (.action action)
                (.orderType orderType)
                (.totalQuantity quantity)
                (.account account))

        status status
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

    (.openOrder wrapper orderId contract order orderState)))

(defn ->orderStatus [wrapper orderId status filled remaining avgFillPrice lastFillPrice]

  (let [;; orderId 3
        ;; status "PreSubmitted"
        ;; filled 0.0
        ;; remaining 10.0
        ;; avgFillPrice 0.0
        permId 652797928
        parentId 0
        ;; lastFillPrice 0.0
        clientId 1
        whyHeld "locate"]

    (.orderStatus wrapper orderId status filled remaining avgFillPrice permId parentId lastFillPrice clientId whyHeld)))

(defn ->execDetails [wrapper symbol orderId shares price avgPrice reqId]

  (let [;; symbol "AAPL"

        execId "00018037.5beb36f0.01.01"
        ;; orderId 3
        ;; shares 10

        p_orderId orderId
        p_client 0
        p_execId execId
        p_time "time"
        p_acctNumber "acctNumber"
        p_exchange "exchange"
        p_side "side"
        p_shares shares
        ;; p_price 0.0
        p_permId 0
        p_liquidation 0
        p_cumQty 0
        ;; p_avgPrice 0.0
        p_orderRef "orderRef"
        p_evRule "evRule"
        p_evMultiplier 0.0
        extra_undocumented_arguemnt "Foobar"

        ;; reqId 1
        contract (contract/create symbol)
        execution (Execution. p_orderId p_client p_execId p_time
                              p_acctNumber p_exchange p_side p_shares
                              price p_permId p_liquidation p_cumQty
                              avgPrice p_orderRef p_evRule p_evMultiplier extra_undocumented_arguemnt)]

    (.execDetails wrapper reqId contract execution)))

(defn ->commissionReport [wrapper commission currency realizedPNL]

  (let [commissionReport (CommissionReport.)]

    (set! (.-m_execId commissionReport) "00018037.5beb36f0.01.01")
    ;; (set! (.-m_commission commissionReport) 0.382257)
    ;; (set! (.-m_currency commissionReport) "USD")
    ;; (set! (.-m_realizedPNL commissionReport) 1.7976931348623157E308)
    (set! (.-m_commission commissionReport) commission)
    (set! (.-m_currency commissionReport) currency)
    (set! (.-m_realizedPNL commissionReport) realizedPNL)

    (.commissionReport wrapper commissionReport)))

(defn ->position [wrapper symbol account pos avgCost]

  (let [;; symbol "AAPL"
        ;; account "DU542121"
        contract (contract/create symbol)
        ;; pos 20.0
        ;; avgCost 218.530225
        ]

    (.position wrapper account contract pos avgCost)))
