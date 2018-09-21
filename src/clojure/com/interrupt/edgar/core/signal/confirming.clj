(ns com.interrupt.edgar.core.signal.confirming
  (:require [com.interrupt.edgar.core.analysis.confirming :as aconfirming]
            [com.interrupt.edgar.core.signal.leading :as sleading]
            [com.interrupt.edgar.core.signal.common :as common]))

(defn on-balance-volume
  "signal for the on-balance-volume analysis chart. This function uses.

   A. OBV Divergence from price.

   ** This function assumes the latest tick is on the right**"

  [view-window obv-list]

  ;; (println "obv-list" obv-list)
  ;; (println "obv-list count" (count obv-list))

  (let [lst (last obv-list)

        price-peaks-valleys (common/find-peaks-valleys nil obv-list)
        obv-peaks-valleys (common/find-peaks-valleys {:input :obv} obv-list)

        dUP? (common/divergence-up?
               {:input-top :last-trade-price :input-bottom :obv} obv-list price-peaks-valleys obv-peaks-valleys)

        dDOWN? (common/divergence-down?
                 {:input-top :last-trade-price :input-bottom :obv} obv-list price-peaks-valleys obv-peaks-valleys)]

    (if (or dUP? dDOWN?)

      (if dUP?

        (assoc lst :signals [{:signal :up
                          :why :obv-divergence
                          :arguments [obv-list price-peaks-valleys obv-peaks-valleys]}])

        (assoc lst :signals [{:signal :down
                              :why :obv-divergence
                              :arguments [obv-list price-peaks-valleys obv-peaks-valleys]}]))
      (last obv-list))))
