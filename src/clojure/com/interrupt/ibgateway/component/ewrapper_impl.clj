(ns com.interrupt.ibgateway.component.ewrapper-impl
  (:require [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojure.tools.logging :refer [debug info warn error]]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [com.interrupt.ibgateway.component.account.summary :as acct-summary]
            [com.interrupt.ibgateway.component.account.portfolio :as portfolio]
            [com.interrupt.ibgateway.component.account.contract :as contract]

            [com.interrupt.ibgateway.component.common :refer :all :as common]
            [com.interrupt.ibgateway.component.account :as account]

            [com.interrupt.edgar.obj-convert :as obj-convert]
            [com.interrupt.edgar.scanner :as scanner])
  (:import [com.ib.client Contract Order OrderState Execution
            EReader EWrapperMsgGenerator CommissionReport]
           [com.interrupt.ibgateway EWrapperImpl]))

(def datetime-formatter (tf/formatter "yyyyMMdd HH:mm:ss"))

(defn historical-subscribe
  ([client ticker-id]
   (historical-subscribe client ticker-id (contract/create "TSLA")))
  ([client ticker-id contract]
   (->> (t/minus (t/now) (t/days 16))
        (tf/unparse datetime-formatter)
        (historical-subscribe client ticker-id contract)))
  ([client ticker-id contract end-datetime]
   (.reqHistoricalData client ticker-id contract end-datetime
                       "4 M" "1 min" "TRADES" 1 1 nil)
   ticker-id))

(defn historical-unsubscribe
  [client req-id]
  (.cancelHistoricalData client req-id))

(defn scanner-subscribe
  [client instrument location-code scan-code]
  (let [req-id (-> scan-code
                   scanner/scan-code->ch-kw
                   scanner/ch-kw->req-id)
        subscription (scanner/scanner-subscription instrument location-code scan-code)]
    (.reqScannerSubscription client (int req-id) subscription nil)
    req-id))

(defn scanner-unsubscribe [client req-id]
  (.cancelScannerSubscription client req-id))


;; Subscribe / unsubscribe
;; client.reqAccountUpdates(true, "U150462")
;; client.reqAccountUpdates(false, "U150462")

;; client.reqAccountSummary(9002, "All", "$LEDGER");
;; client.cancelAccountSummary(9002);

;; client.reqPositions();
;; client.cancelPositions();

(declare client*)

(defn ewrapper-impl
  [{tick-updates :publisher
    account-updates :account-updates
    position-updates :position-updates
    order-updates :order-updates
    valid-order-ids :valid-order-ids}]

  (proxy [EWrapperImpl] []


    (nextValidId [^Integer order-id]
      (info "nextValidId / " order-id)
      (let [val {:topic :next-valid-id
                 :order-id order-id}]

        ;; (deliver common/next-valid-order-id order-id)
        (async/put! order-updates val)

        ))


    ;; ========
    ;; TICK DATA
    (tickPrice [^Integer ticker-id ^Integer field ^Double price ^Integer can-auto-execute?]
      (let [val {:topic :tick-price
                 :ticker-id ticker-id
                 :field field
                 :price price
                 :can-auto-execute can-auto-execute?}]
        (debug "tickPrice / " val)
        (async/put! tick-updates val)))

    (tickSize [^Integer ticker-id ^Integer field ^Integer size]
      (let [val {:topic :tick-size
                 :ticker-id ticker-id
                 :field field
                 :size size}]
        (debug "tickSize / " val)
        (async/put! tick-updates val)))

    (tickString [^Integer ticker-id ^Integer tickType ^String value]
      (let [val {:topic :tick-string
                 :ticker-id ticker-id
                 :tick-type tickType
                 :value value}]

        (debug "tickString / " val)
        (async/put! tick-updates val)))

    (tickGeneric [^Integer ticker-id ^Integer tickType ^Double value]
      (let [val {:topic :tick-generic
                 :ticker-id ticker-id
                 :tick-type tickType
                 :value value}]
        (debug "New - Tick Generic. Ticker Id:"  ticker-id  ", Field: " tickType  ", Value: "  value)
        (async/put! tick-updates val)))


    ;; ========
    ;; SCANNER DATA
    (scannerData [req-id rank contract-details distance benchmark projection _]
      (let [contract (.contract contract-details)
            val {:topic :scanner-data
                 :req-id req-id
                 :message-end false
                 :symbol (. contract symbol)
                 :sec-type (. contract getSecType)
                 :rank rank}]
        (if-let [scanner-ch (->> req-id
                                 scanner/req-id->ch-kw
                                 scanner/ch-kw->ch)]

          (async/put! scanner-ch val)
          (warn "No scanner channel for req-id " req-id))))

    (scannerDataEnd [req-id]
      (if-let [scanner-ch (->> req-id
                               scanner/req-id->ch-kw
                               scanner/ch-kw->ch)]
        (async/put! scanner-ch ::scanner/data-end)
        (warn "No scanner channel for req-id " req-id)))


    ;; ========
    ;; HISTORICAL DATA
    (historicalData [req-id date open high low close volume count wap gaps?]
      (let [val {:topic :historical-data
                 :req-id req-id
                 :date date
                 :open open
                 :high high
                 :low low
                 :close close
                 :volume volume
                 :count count
                 :wap wap
                 :has-gaps gaps?}]
        (async/put! tick-updates val)))


    ;; ========
    ;; ACCOUNT UPDATES
    (updateAccountValue [^String key
                         ^String value
                         ^String currency
                         ^String account-name]
      (let [val {:topic :update-account-value
                 :account-name account-name
                 :key key
                 :value value
                 :currency currency}]
        (debug "updateAccountValue / val / " val)
        (async/put! account-updates val)))

    (updatePortfolio [^Contract contract
                      ^Double position
                      ^Double market-price
                      ^Double market-value
                      ^Double average-cost
                      ^Double unrealized-pnl
                      ^Double realized-pnl
                      ^String account-name]
      (let [val {:topic :update-portfolio
                 :contract (obj-convert/convert contract)
                 :position position
                 :market-price market-price
                 :market-value market-value
                 :average-cost average-cost
                 :unrealized-pnl unrealized-pnl
                 :realized-pnl realized-pnl
                 :account-name account-name}]
        (debug "updatePortfolio / val / " val)
        (async/put! account-updates val)))

    (updateAccountTime [^String timeStamp]
      (let [val {:topic :update-account-time
                 :time-stamp timeStamp}]
        (debug "updateAccountTime / timeStamp /" val)
        (async/put! account-updates val)))

    (accountDownloadEnd [^String account]
      (let [val {:topic :account-download-end
                 :account account}]
        (debug "accountDownloadEnd / account /" val)
        (async/put! account-updates val)))


    ;; ========
    ;; ACCOUNT SUMMARY
    (accountSummary [req-id account tag value currency]
      (let [val {:topic :account-summary
                 :req-id req-id
                 :account account
                 :tag tag
                 :value (acct-summary/parse-tag-value tag value)
                 :currency currency}]
        (debug "accountSummary / val / " val)
        (async/put! account-updates val)))

    (accountSummaryEnd [^Integer reqId]
      (let [val {:topic :account-summary-end
                 :req-id reqId}]
        (debug "accountSummaryEnd / reqId /" reqId)
        ;; (async/put! account-updates val)
        ))


    ;; ========
    ;; POSITION UPDATES
    (position [^String account
               ^Contract contract
               ^Double pos
               ^Double avgCost]
      (let [val {:topic :position
                 :account account
                 :contract contract
                 :symbol (.symbol contract)
                 :position pos
                 :avg-cost avgCost}]

        (debug "position / "
              " Account / " account
              " Symbol / " (.symbol contract)
              " SecType / " (.secType contract)
              " Currency / " (.currency contract)
              " Position / " pos
              " Avg cost / " avgCost)
        (async/put! position-updates val)))

    (positionEnd []
      (let [val {:topic :position-end}]
        (debug "PositionEnd \n")
        ;; (async/put! account-updates val)
        ))


    ;; ========
    ;; ORDER UPDATES
    (openOrder [^Integer orderId
                ^Contract contract
                ^Order order
                ^OrderState orderState]

      (let [{:keys [orderId symbol secType exchange action
                    orderType totalQuantity status] :as val}
            {:topic :open-order
             :orderId orderId
             :symbol (.symbol contract)
             :secType (.secType contract)
             :exchange (.exchange contract)
             :action (.action order)
             :orderType (.orderType order)
             :totalQuantity (.totalQuantity order)
             :status (.status orderState)}]

        (info "openOrder / orderId " orderId
              " Contract::symbol / " symbol
              " Contract::secType / " secType
              " Contract::exchange / " exchange
              " Order::action / " action
              " Order::orderType / " orderType
              " Order::totalQuantity / " totalQuantity
              " OrderState::status / " status)

        ;; (async/put! order-updates val)
        (account/handle-open-order val)))

    (orderStatus [^Integer orderId
                  ^String status
                  ^Double filled
                  ^Double remaining
                  ^Double avgFillPrice
                  ^Integer permId
                  ^Integer parentId
                  ^Double lastFillPrice
                  ^Integer clientId
                  ^String whyHeld]

      (let [{:keys [orderId status filled remaining avgFillPrice permId
                    parentId lastFillPrice clientId whorderIdyHeld] :as val}
            {:topic :order-status
             :orderId orderId
             :status status
             :filled filled
             :remaining remaining
             :avgFillPrice avgFillPrice
             :permId permId
             :parentId parentId
             :lastFillPrice lastFillPrice
             :clientId clientId
             :whorderIdyHeld whyHeld}]

        (info "orderStatus /"
              " Id / " orderId
              " Status / " status
              " Filled" filled
              " Remaining / " remaining
              " AvgFillPrice / " avgFillPrice
              " PermId / " permId
              " ParentId / " parentId
              " LastFillPrice / " lastFillPrice
              " ClientId / " clientId
              " WhyHeld / " whyHeld)

        ;; (async/put! order-updates val)
        (account/handle-order-status val account/account)))


    (openOrderEnd [] (info "OpenOrderEnd"))


    ;; ========
    ;; EXECUTION UPDATES
    (execDetails [^Integer reqId
                  ^Contract contract
                  ^Execution execution]

      (let [{:keys [reqId symbol secType currency
                    execId orderId shares] :as val}
            {:topic :exec-details
             :reqId reqId
             :symbol (.symbol contract)
             :secType (.secType contract)
             :currency (.currency contract)
             :execId (.execId execution)
             :orderId (.orderId execution)
             :shares (.shares execution)}]
        (info "execDetails / "
              " reqId /" reqId
              " symbol /" symbol
              " secType /" secType
              " currency /" currency
              " execId /" execId
              " orderId /" orderId
              " shares /" shares)

        ;; (async/put! order-updates val)
        (account/handle-exec-details val account/account)))

    (commissionReport [^CommissionReport commissionReport]

      (let [{:keys [execId commission
                    currency realizedPNL] :as val}
            {:topic :commission-report
             :execId (.-m_execId commissionReport)
             :commission (.-m_commission commissionReport)
             :currency (.-m_currency commissionReport)
             :realizedPNL (.-m_realizedPNL commissionReport)}]
        (info "commissionReport /"
              " execId /" execId
              " commission /" commission
              " currency /" currency
              " realizedPNL /" realizedPNL)

        ;; (async/put! order-updates val)

        (let [stock+order (account/process-commission-report val account/account)]

          ;; (info "commissionReport / stock+order / " stock+order)
          (common/process-order-filled-notifications client* stock+order valid-order-ids))))

    (execDetailsEnd [^Integer reqId]
      (info "execDetailsEnd / reqId / " reqId))))

(defn default-exception-handler
  [^Exception e]
  (println "Exception:" (.getMessage e)))

(defn default-chs-map []
  {:publisher (-> 1000 async/sliding-buffer async/chan)
   :account-updates (-> 1000 async/sliding-buffer async/chan)})

(defn ewrapper
  ([]
   (ewrapper "tws"))
  ([host]
   (ewrapper host 4002))
  ([host port]
   (ewrapper host port 1))
  ([host port client-id]
   (ewrapper host port client-id (default-chs-map)))
  ([host port client-id chs-map]
   (ewrapper host port client-id chs-map default-exception-handler))
  ([host port client-id chs-map ex-handler]
   (let [wrapper (ewrapper-impl chs-map)
         client (.getClient wrapper)
         signal (.getSignal wrapper)]

     (def client* client)

     (.eConnect client host port client-id)
     (let [ereader (EReader. client signal)]
       (.start ereader)
       (future
         (while (.isConnected client)
           (.waitForSignal signal)
           (try (.processMsgs ereader)
                (catch Exception e (ex-handler e))))))
     (merge {:client client
             :wrapper wrapper}
            chs-map))))
