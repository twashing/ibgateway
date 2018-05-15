(ns com.interrupt.ibgateway.component.processing-pipeline
  (:require [clojure.core.async :refer [chan sliding-buffer close!
                                        mult tap pipeline]]
            [mount.core :refer [defstate] :as mount]
            [net.cgrand.xforms :as x]
            [com.interrupt.edgar.core.edgar :as edg]
            [com.interrupt.ibgateway.component.ewrapper :as ew]
            [com.interrupt.edgar.ib.market :as mkt]
            [com.interrupt.edgar.ib.handler.live :refer [feed-handler] :as live]
            [com.interrupt.edgar.core.analysis.lagging :as alag]
            [com.interrupt.edgar.core.analysis.leading :as alead]
            [com.interrupt.edgar.core.analysis.confirming :as aconf]))


(defn bind-channel->mult [tick-list-ch]

  (let [tick-list-sma-mult (mult tick-list-ch)
        tick-list-sma-ch (chan (sliding-buffer 100))]

    (tap tick-list-sma-mult tick-list-sma-ch)
    tick-list-sma-ch))


(defn setup-publisher-channel [stock-name concurrency ticker-id-filter]

  (let [output-fn (fn [event-name result]
                    (println (str "stream-live > event-name[" event-name "] response keys[" (keys result) "]"))
                    (println (str "stream-live > event-name[" event-name "] signals[" (-> result :signals keys) "]"))
                    (println (str "stream-live > event-name[" event-name "] strategies[" (:strategies result) "]")))

        options {;; :tee-list [(partial tlive/tee-fn output-fn stock-name)]
                 :stock-match {:symbol stock-name :ticker-id-filter ticker-id-filter}}]

    ;; TODO
    ;; join together pipeline channels
    ;; Mount component for pipelines
    ;; We want to pipeline:
    #_[alagging/simple-moving-average
       alagging/exponential-moving-average

       slagging/moving-averages
       slagging/bollinger-band
       sleading/macd
       sleading/stochastic-oscillator
       sconfirming/on-balance-volume

       strategy/strategy-A
       strategy/strategy-C

       {:stock-name stock-name
        :stock-symbol (:symbol result-map)
        :stock-list final-list
        :source-list tick-list-N
        :sma-list smaF
        :ema-list emaF
        :signals {:moving-average signals-ma
                  :bollinger-band signals-bollinger
                  :macd signals-macd
                  :stochastic-oscillator signals-stochastic
                  :obv signals-obv}
        :strategies {:strategy-A sA
                     :strategy-C sC}}]

    (let [n concurrency
          tick-list-ch (chan (sliding-buffer 100) (x/partition live/moving-average-window live/moving-average-increment (x/into [])))

          ;; TODO Remove :population
          sma-list-ch (chan (sliding-buffer 100) (x/partition live/moving-average-window live/moving-average-increment (x/into []) #_(map #(dissoc % :population))))
          ema-list-ch (chan (sliding-buffer 100) #_(map last))
          bollinger-band-ch (chan (sliding-buffer 100) #_(map last))
          macd-ch (chan (sliding-buffer 100))

          tick-list-sma-ch (bind-channel->mult tick-list-ch)
          sma-list-ema-ch (bind-channel->mult sma-list-ch)
          sma-list-bollinger-band-ch (bind-channel->mult sma-list-ch)


          ;; TODO join tick-list, sma-list
          ;; tick-list-macd-ch (bind-channel->mult tick-list-ch)


          tick-list-stochastic-osc-ch (bind-channel->mult tick-list-ch)
          tick-list-obv-ch (bind-channel->mult tick-list-ch)
          tick-list-relative-strength-ch (bind-channel->mult tick-list-ch)]


      (pipeline n tick-list-ch live/handler-xform (ew/ewrapper :publisher))
      (pipeline n sma-list-ch (map (partial alag/simple-moving-average options)) tick-list-sma-ch)
      (pipeline n ema-list-ch (map (partial alag/exponential-moving-average options live/moving-average-window)) sma-list-ema-ch)
      (pipeline n bollinger-band-ch (map (partial alag/bollinger-band live/moving-average-window)) sma-list-bollinger-band-ch)

      #_(pipeline n macd-ch (map (partial alead/macd options live/moving-average-window)) tick-list-macd-ch #_sma-list-macd-ch)


      ;; TODO refactor
      ;; (alead/stochastic-oscillator tick-window trigger-window trigger-line tick-list)

      ;; (aconf/on-balance-volume latest-tick tick-list)
      ;; (aconf/relative-strength-index tick-window tick-list)

      ;; (slead/macd options tick-window tick-list sma-list macd-list)
      ;; (slead/stochastic-oscillator tick-window trigger-window trigger-line tick-list stochastic-list)

      ;; (sconf/on-balance-volume view-window tick-list obv-list)


      {:tick-list-ch tick-list-ch
       :sma-list-ch sma-list-ch
       :ema-list-ch ema-list-ch
       :bollinger-band-ch bollinger-band-ch
       ;; :macd-ch macd-ch
       })))

(defn teardown-publisher-channel [processing-pipeline]
  (doseq [vl (vals processing-pipeline)]
    (close! vl)))

(defstate processing-pipeline
  :start (setup-publisher-channel "TSLA" 1 0)
  :stop (teardown-publisher-channel processing-pipeline))
