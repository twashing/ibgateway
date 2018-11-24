(ns com.interrupt.ibgateway.component.account-test
  (:require [mount.core :refer [defstate] :as mount]
            [com.interrupt.ibgateway.component.ewrapper :refer [default-chs-map ewrapper]]
            [com.interrupt.ibgateway.component.account :refer [account consume-order-updates]]
            [com.interrupt.ibgateway.component.switchboard.mock :refer :all]
            [clojure.test :refer :all]))


(defn account-setup-fixture [f]

  (mount/start #'default-chs-map #'ewrapper #'account)
  (def wrapper (:wrapper ewrapper))
  (def account-name "DU542121")
  (def valid-order-id (atom -1))
  (consume-order-updates default-chs-map valid-order-id)

  (f)
  (mount/stop #'default-chs-map #'ewrapper #'account))

(use-fixtures :each account-setup-fixture)


(def account-mkt-one
  {:stock
   [{:symbol "AAPL"
     :amount 10.0
     :avgFillPrice nil
     :orders
     [{:orderId 3
       :orderType "MKT"
       :action "BUY"
       :quantity 10.0
       :price nil
       :state
       {:states
        '(#automata.core.Star{:matcher :api-pending}
           #automata.core.Star{:matcher :pending-submit}
           #automata.core.Star{:matcher :pending-cancel}
           #automata.core.Star{:matcher :pre-submitted}
           #automata.core.Star{:matcher :submitted}
           #automata.core.Star{:matcher :api-cancelled}
           #automata.core.Star{:matcher :cancelled}
           #automata.core.Star{:matcher :filled}
           #automata.core.Star{:matcher :inactive})
        :run
        '(#automata.core.Star{:matcher :submitted}
           #automata.core.Star{:matcher :api-cancelled}
           #automata.core.Star{:matcher :cancelled}
           #automata.core.Star{:matcher :filled}
           #automata.core.Star{:matcher :inactive})
        :state #automata.core.Star{:matcher :pre-submitted}
        :history
        '(nil
           {:state #automata.core.Star{:matcher :api-pending}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pending-submit}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pending-cancel}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pre-submitted}
            :input :pre-submitted
            :transition :automata.core/match})}}]}]
   :cash 0.0})

(def account-mkt-two
  {:stock
   [{:symbol "AAPL"
     :amount 10.0
     :avgFillPrice nil
     :orders
     [{:orderId 3
       :orderType "MKT"
       :action "BUY"
       :quantity 10.0
       :price nil
       :state
       {:states
        '(#automata.core.Star{:matcher :api-pending}
           #automata.core.Star{:matcher :pending-submit}
           #automata.core.Star{:matcher :pending-cancel}
           #automata.core.Star{:matcher :pre-submitted}
           #automata.core.Star{:matcher :submitted}
           #automata.core.Star{:matcher :api-cancelled}
           #automata.core.Star{:matcher :cancelled}
           #automata.core.Star{:matcher :filled}
           #automata.core.Star{:matcher :inactive})
        :run
        '(#automata.core.Star{:matcher :submitted}
           #automata.core.Star{:matcher :api-cancelled}
           #automata.core.Star{:matcher :cancelled}
           #automata.core.Star{:matcher :filled}
           #automata.core.Star{:matcher :inactive})
        :state #automata.core.Star{:matcher :pre-submitted}
        :history
        '(nil
           {:state #automata.core.Star{:matcher :api-pending}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pending-submit}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pending-cancel}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pre-submitted}
            :input :pre-submitted
            :transition :automata.core/match})}}]}]
   :cash 0.0})

(def account-mkt-three
  {:stock
   [{:symbol "AAPL"
     :amount 10.0
     :avgFillPrice nil
     :orders
     [{:orderId 3
       :orderType "MKT"
       :action "BUY"
       :quantity 10.0
       :price nil
       :state
       {:states
        '(#automata.core.Star{:matcher :api-pending}
           #automata.core.Star{:matcher :pending-submit}
           #automata.core.Star{:matcher :pending-cancel}
           #automata.core.Star{:matcher :pre-submitted}
           #automata.core.Star{:matcher :submitted}
           #automata.core.Star{:matcher :api-cancelled}
           #automata.core.Star{:matcher :cancelled}
           #automata.core.Star{:matcher :filled}
           #automata.core.Star{:matcher :inactive})
        :run
        '(#automata.core.Star{:matcher :submitted}
           #automata.core.Star{:matcher :api-cancelled}
           #automata.core.Star{:matcher :cancelled}
           #automata.core.Star{:matcher :filled}
           #automata.core.Star{:matcher :inactive})
        :state #automata.core.Star{:matcher :pre-submitted}
        :history
        '(nil
           {:state #automata.core.Star{:matcher :api-pending}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pending-submit}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pending-cancel}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pre-submitted}
            :input :pre-submitted
            :transition :automata.core/match})}
       :exec-id "00018037.5beb36f0.01.01"}]}]
   :cash 0.0})

(def account-mkt-filled
  {:stock
   [{:symbol "AAPL"
     :amount 10.0
     :avgFillPrice nil
     :orders
     [{:orderId 3
       :orderType "MKT"
       :action "BUY"
       :quantity 10.0
       :price 218.96
       :state
       {:states
        '(#automata.core.Star{:matcher :api-pending}
           #automata.core.Star{:matcher :pending-submit}
           #automata.core.Star{:matcher :pending-cancel}
           #automata.core.Star{:matcher :pre-submitted}
           #automata.core.Star{:matcher :submitted}
           #automata.core.Star{:matcher :api-cancelled}
           #automata.core.Star{:matcher :cancelled}
           #automata.core.Star{:matcher :filled}
           #automata.core.Star{:matcher :inactive})
        :run '(#automata.core.Star{:matcher :inactive})
        :state #automata.core.Star{:matcher :filled}
        :history
        '(nil
           {:state #automata.core.Star{:matcher :api-pending}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pending-submit}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pending-cancel}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pre-submitted}
            :input :pre-submitted
            :transition :automata.core/match}
           {:state #automata.core.Star{:matcher :submitted}
            :input :filled
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :api-cancelled}
            :input :filled
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :cancelled}
            :input :filled
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :filled}
            :input :filled
            :transition :automata.core/match})}
       :exec-id "00018037.5beb36f0.01.01"
       :avgFillPrice 218.96}]}]
   :cash 0.0})

(def account-mkt-filled-spurious
  {:stock
   [{:symbol "AAPL"
     :amount 10.0
     :avgFillPrice nil
     :orders
     [{:avgFillPrice 218.97
       :exec-id "00018037.5beb36f0.01.01"
       :orderType "MKT"
       :state
       {:states
        '(#automata.core.Star{:matcher :api-pending}
           #automata.core.Star{:matcher :pending-submit}
           #automata.core.Star{:matcher :pending-cancel}
           #automata.core.Star{:matcher :pre-submitted}
           #automata.core.Star{:matcher :submitted}
           #automata.core.Star{:matcher :api-cancelled}
           #automata.core.Star{:matcher :cancelled}
           #automata.core.Star{:matcher :filled}
           #automata.core.Star{:matcher :inactive})
        :run '(#automata.core.Star{:matcher :inactive})
        :state #automata.core.Star{:matcher :filled}
        :history
        '(nil
           {:state #automata.core.Star{:matcher :api-pending}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pending-submit}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pending-cancel}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pre-submitted}
            :input :pre-submitted
            :transition :automata.core/match}
           {:state #automata.core.Star{:matcher :submitted}
            :input :filled
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :api-cancelled}
            :input :filled
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :cancelled}
            :input :filled
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :filled}
            :input :filled
            :transition :automata.core/match}
           {:state #automata.core.Star{:matcher :filled}
            :input :filled
            :transition :automata.core/match})}
       :commission 0.382257
       :action "BUY"
       :quantity 10.0
       :price 218.98
       :realizedPNL 1.7976931348623157E308
       :orderId 3}]}]
   :cash 0.0})

(deftest test-mkt-buy


  (testing "PreSubmitted callbacks"

    (let [symbol "AAPL"
          orderId 3
          orderType "MKT"
          action "BUY"
          quantity 10.0
          status "PreSubmitted"]
      (->openOrder wrapper symbol account-name orderId orderType action quantity status)

      ;; TODO account state updates after some callbacks; make this more testable
      (Thread/sleep 250)
      (is (= @account account-mkt-one)))


    (let [orderId 3
          status "PreSubmitted"
          filled 0.0
          remaining 10.0
          avgFillPrice 0.0
          lastFillPrice 0.0]
      (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice)

      (Thread/sleep 250)
      (is (= @account account-mkt-two)))


    (let [symbol "AAPL"
          orderId 3
          shares 10.0
          price 0.0
          avgPrice 0.0
          reqId 1]
      (->execDetails wrapper symbol orderId shares price avgPrice reqId)

      (Thread/sleep 250)
      (is (= @account account-mkt-three))))


  (testing "Status Filled callbacks"

    (let [symbol "AAPL"
          orderId 3
          orderType "MKT"
          action "BUY"
          quantity 10.0
          status "Filled"]
      (->openOrder wrapper symbol account-name orderId orderType action quantity status))

    (let [orderId 3
          status "Filled"
          filled 10.0
          remaining 0.0
          avgFillPrice 218.96
          lastFillPrice 218.96]
      (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice)

      (Thread/sleep 250)
      (is (= @account account-mkt-filled))))


  (testing "Status spurious Filled callbacks"

    (let [symbol "AAPL"
            orderId 3
            orderType "MKT"
            action "BUY"
            quantity 10.0
            status "Filled"]
      (->openOrder wrapper symbol account-name orderId orderType action quantity status))

    (let [orderId 3
            status "Filled"
            filled 10.0
            remaining 0.0
            avgFillPrice 218.97
            lastFillPrice 218.98]
      (->orderStatus wrapper orderId status filled remaining avgFillPrice lastFillPrice))

    (let [commission 0.382257
            currency "USD"
            realizedPNL 1.7976931348623157E308]
        (->commissionReport wrapper commission currency realizedPNL)

        (Thread/sleep 250)
        (is (= @account account-mkt-filled-spurious)))))

(def account-trail-sell
  {:stock
   [{:symbol "AAPL"
     :amount 10.0
     :avgFillPrice nil
     :orders
     [{:avgFillPrice 176.27
       :exec-id "00018037.5beb36f0.01.01"
       :orderType "TRAIL"
       :state
       {:states
        '(#automata.core.Star{:matcher :api-pending}
           #automata.core.Star{:matcher :pending-submit}
           #automata.core.Star{:matcher :pending-cancel}
           #automata.core.Star{:matcher :pre-submitted}
           #automata.core.Star{:matcher :submitted}
           #automata.core.Star{:matcher :api-cancelled}
           #automata.core.Star{:matcher :cancelled}
           #automata.core.Star{:matcher :filled}
           #automata.core.Star{:matcher :inactive})
        :run '(#automata.core.Star{:matcher :inactive})
        :state #automata.core.Star{:matcher :filled}
        :history
        '(nil
           {:state #automata.core.Star{:matcher :api-pending}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pending-submit}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pending-cancel}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pre-submitted}
            :input :pre-submitted
            :transition :automata.core/match}
           {:state #automata.core.Star{:matcher :pre-submitted}
            :input :pre-submitted
            :transition :automata.core/match}
           {:state #automata.core.Star{:matcher :submitted}
            :input :submitted
            :transition :automata.core/match}
           {:state #automata.core.Star{:matcher :api-cancelled}
            :input :filled
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :cancelled}
            :input :filled
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :filled}
            :input :filled
            :transition :automata.core/match}
           {:state #automata.core.Star{:matcher :filled}
            :input :filled
            :transition :automata.core/match})}
       :commission 0.355362
       :action "SELL"
       :quantity 10.0
       :price 176.27
       :realizedPNL -380.989362
       :orderId 13}]}]
   :cash 0.0})

(deftest test-trail-sell

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
    (->commissionReport wrapper commission currency realizedPNL))

  (Thread/sleep 250)
  (is (= @account account-trail-sell)))

(def account-trail-limit-sell
  {:stock
   [{:symbol "AAPL"
     :amount 10.0
     :avgFillPrice nil
     :orders
     [{:avgFillPrice 177.16
       :exec-id "00018037.5beb36f0.01.01"
       :orderType "TRAIL LIMIT"
       :state
       {:states
        '(#automata.core.Star{:matcher :api-pending}
           #automata.core.Star{:matcher :pending-submit}
           #automata.core.Star{:matcher :pending-cancel}
           #automata.core.Star{:matcher :pre-submitted}
           #automata.core.Star{:matcher :submitted}
           #automata.core.Star{:matcher :api-cancelled}
           #automata.core.Star{:matcher :cancelled}
           #automata.core.Star{:matcher :filled}
           #automata.core.Star{:matcher :inactive})
        :run '(#automata.core.Star{:matcher :inactive})
        :state #automata.core.Star{:matcher :filled}
        :history
        '(nil
           {:state #automata.core.Star{:matcher :api-pending}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pending-submit}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pending-cancel}
            :input :pre-submitted
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :pre-submitted}
            :input :pre-submitted
            :transition :automata.core/match}
           {:state #automata.core.Star{:matcher :pre-submitted}
            :input :pre-submitted
            :transition :automata.core/match}
           {:state #automata.core.Star{:matcher :submitted}
            :input :filled
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :api-cancelled}
            :input :filled
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :cancelled}
            :input :filled
            :transition :automata.core/noop}
           {:state #automata.core.Star{:matcher :filled}
            :input :filled
            :transition :automata.core/match}
           {:state #automata.core.Star{:matcher :filled}
            :input :filled
            :transition :automata.core/match})}
       :commission 0.352478
       :action "SELL"
       :quantity 10.0
       :price 177.16
       :realizedPNL -372.086478
       :orderId 13}]}]
   :cash 0.0})

(deftest test-trail-limit-sell

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
    (->commissionReport wrapper commission currency realizedPNL))

  (Thread/sleep 250)
  (is (= @account account-trail-limit-sell)))
