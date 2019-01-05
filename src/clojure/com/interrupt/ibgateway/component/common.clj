(ns com.interrupt.ibgateway.component.common
  (:require [clojure.core.async :refer [<!! mult tap] :as async]
            [clojure.tools.logging :refer [info]]
            [clojure.core.async.impl.protocols :refer [closed?]]
            [com.interrupt.ibgateway.component.account.contract :as contract])
  (:import [com.ib.client Order]))

(defn bind-channels->mult [source-list-ch & channels]
  (let [source-list->sink-mult (mult source-list-ch)]
    (doseq [c channels]
      (tap source-list->sink-mult c))))

(def channel-open? (comp not closed?))
(def exists? (comp not empty?))
(def latest-standard-deviation (atom -1))
(def latest-bid (atom -1))
(def valid-order-id (atom 100))

(defn ->next-valid-order-id

  ([client valid-order-id-ch]
   (->next-valid-order-id
     client valid-order-id-ch (fn [] (.reqIds client -1))))

  #_([_ valid-order-id-ch f]
   (f)
   (<!! valid-order-id-ch))

  ([_ valid-order-id-ch f]

   ;; KLUDGE
   (swap! valid-order-id inc)))

(defn sell-market [client stock order valid-order-id-ch account-name]

  (let [action "SELL"
        valid-order-id (->next-valid-order-id client valid-order-id-ch)
        symbol (:symbol stock)
        quantity (:quantity order)]

    (info "3 - (balancing) sell-stock / sell-market / " [quantity valid-order-id])
    (.placeOrder client
                 valid-order-id
                 (contract/create symbol)
                 (doto (Order.)
                   (.action action)
                   (.orderType "MKT")
                   (.totalQuantity quantity)
                   (.account account-name)))))

(defn sell-limit [client stock order valid-order-id-ch]

  (let [action "SELL"
        symbol (:symbol stock)
        quantity (:quantity order)
        valid-order-id (->next-valid-order-id client valid-order-id-ch)
        threshold (->> @latest-standard-deviation
                       (clojure.pprint/cl-format nil "~,2f")
                       read-string
                       (Double.)
                       (* 2.5))
        limitPrice (+ (:price order) threshold)]

    (info "3 - (balancing) sell-stock / sell-limit / " [quantity valid-order-id limitPrice])
    (.placeOrder client
                 valid-order-id
                 (contract/create symbol)
                 (doto (Order.)
                   (.action action)
                   (.orderType "LMT")
                   (.totalQuantity quantity)
                   (.lmtPrice limitPrice)))))

(defn sell-trailing [client stock order valid-order-id-ch]

  (let [action "SELL"
        symbol (:symbol stock)
        quantity (:quantity order)
        valid-order-id (->next-valid-order-id client valid-order-id-ch)
        auxPrice (->> @latest-standard-deviation
                      (clojure.pprint/cl-format nil "~,2f")
                      read-string
                      (Double.)
                      (* 10)
                      (clojure.pprint/cl-format nil "~,2f")
                      read-string)
        trailStopPrice (- (:price order) auxPrice)]

    (info "3 - (balancing) sell-stock / sell-trailing / " [quantity valid-order-id auxPrice trailStopPrice])
    (.placeOrder client
                 valid-order-id
                 (contract/create symbol)
                 (doto (Order.)
                   (.action action)
                   (.orderType "TRAIL")
                   (.auxPrice auxPrice)
                   (.trailStopPrice trailStopPrice)
                   (.totalQuantity quantity)))))

(defn sell-trailing-limit [client stock order valid-order-id-ch]

  (let [action "SELL"
        symbol (:symbol stock)
        quantity (:quantity order)
        valid-order-id (->next-valid-order-id client valid-order-id-ch)
        lmtPriceOffset 0.05
        trailingAmount (->> @latest-standard-deviation
                            (clojure.pprint/cl-format nil "~,2f")
                            read-string)
        trailStopPrice (- (:price order) trailingAmount)]

    (info "3 - (balancing) sell-stock / sell-trailing-limit / " [quantity valid-order-id trailingAmount trailStopPrice])
    (.placeOrder client
                 valid-order-id
                 (contract/create symbol)
                 (doto (Order.)
                   (.action action)
                   (.orderType "TRAIL LIMIT")
                   (.lmtPriceOffset lmtPriceOffset)
                   (.auxPrice trailingAmount)
                   (.trailStopPrice trailStopPrice)
                   (.totalQuantity quantity)))))

(defn process-order-filled-notifications [client {:keys [stock order] :as val} valid-order-id-ch]

  (info "3 - process-order-filled-notifications LOOP / " (exists? val))
  ;; (sell-limit client stock order valid-order-id-ch)
  ;; (sell-trailing client stock order valid-order-id-ch)
  )
