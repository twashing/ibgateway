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

(defn process-order-filled-notifications [client {:keys [stock order] :as val} valid-order-id-ch]

  (info "3 - process-order-filled-notifications LOOP / " (exists? val))
  (let [symbol (:symbol stock)
        action "SELL"
        quantity (:quantity order)
        valid-order-id (->next-valid-order-id client valid-order-id-ch)
        _ (info "3 - process-order-filled-notifications / valid-order-id-ch channel-open? / " (channel-open? valid-order-id-ch)
                " / order-id / " valid-order-id)

        auxPrice (->> @latest-standard-deviation
                      (clojure.pprint/cl-format nil "~,2f")
                      read-string
                      (Double.)
                      (* 1.75)
                      (clojure.pprint/cl-format nil "~,2f")
                      read-string)

        trailStopPrice (- (:price order) auxPrice)]

    (info "3 - (balancing) sell-stock / client, " [quantity valid-order-id auxPrice trailStopPrice])
    (.placeOrder client
                 valid-order-id
                 (contract/create symbol)
                 (doto (Order.)
                   (.action action)
                   (.orderType "TRAIL")
                   (.auxPrice auxPrice)
                   (.trailStopPrice trailStopPrice)
                   (.totalQuantity quantity)))))
