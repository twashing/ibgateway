(ns com.interrupt.ibgateway.component.processing-pipeline
  (:require [clojure.core.async :refer [chan to-chan sliding-buffer close! <!
                                        go-loop mult tap pipeline]]
            [mount.core :refer [defstate] :as mount]
            [net.cgrand.xforms :as x]
            [com.interrupt.edgar.core.edgar :as edg]
            [com.interrupt.ibgateway.component.ewrapper :as ew]
            [com.interrupt.edgar.ib.market :as mkt]
            [com.interrupt.edgar.ib.handler.live :refer [feed-handler] :as live]
            [com.interrupt.edgar.core.analysis.lagging :as alag]
            [com.interrupt.edgar.core.analysis.leading :as alead]
            [com.interrupt.edgar.core.analysis.confirming :as aconf]

            [manifold.stream :as stream]
            [prpr.stream.cross :as stream.cross]
            prpr.stream
            [prpr.promise :refer [ddo]]
            [xn.transducers :as xn]))


(defn bind-channels->mult [tick-list-ch & channels]
  (let [tick-list-sma-mult (mult tick-list-ch)]
    (doseq [c channels]
      (tap tick-list-sma-mult c))))

#_(let [c1 (to-chan [{:id 2 :a "a"} {:id 3} {:id 4}])
        c2 (to-chan [{:id 1} {:id 2 :b "b"} {:id 3}])
        c3 (to-chan [{:id 0} {:id 1} {:id 2 :c "c"}])
        cs1 (stream/->source c1)
        cs2 (stream/->source c2)
        cs3 (stream/->source c3)
        ss1 (stream.cross/event-source->sorted-stream :id cs1)
        ss2 (stream.cross/event-source->sorted-stream :id cs2)
        ss3 (stream.cross/event-source->sorted-stream :id cs3)]


    (let [oc (chan 1 (map vals))
          result (stream.cross/set-streams-union {:default-key-fn :id
                                                  :skey-streams {:ss1 ss1
                                                                 :ss2 ss2
                                                                 :ss3 ss3}})]
      (stream/connect @result oc)
      (go-loop [r (<! oc)]
        (println "record: " r)
        (println "record merged: " (apply merge r))
        (println "")
        (if-not r
          r
          (recur (<! oc))))))

(comment  ;; SUCCESS with promisespromises


      #_(let [c1 (to-chan [{:id 2 :a "a"} {:id 3} {:id 4}])
            c2 (to-chan [{:id 1} {:id 2 :b "b"} {:id 3}])
            c3 (to-chan [{:id 0} {:id 1} {:id 2 :c "c"}])
            cs1 (stream/->source c1)
            cs2 (stream/->source c2)
            cs3 (stream/->source c3)
            ss1 (stream.cross/event-source->sorted-stream :id cs1)
            ss2 (stream.cross/event-source->sorted-stream :id cs2)
            ss3 (stream.cross/event-source->sorted-stream :id cs3)]


        (let [oc (chan 1 (map vals))
              result (stream.cross/set-streams-union {:default-key-fn :id
                                                      :skey-streams {:ss1 ss1
                                                                     :ss2 ss2
                                                                     :ss3 ss3}})]
          (stream/connect @result oc)
          (go-loop [r (<! oc)]
            (println "record: " r)
            (println "record merged: " (apply merge r))
            (println "")
            (if-not r
              r
              (recur (<! oc)))))))

(defn setup-publisher-channel [stock-name concurrency ticker-id-filter]

  (let [output-fn (fn [event-name result]
                    (println (str "stream-live > event-name[" event-name "] response keys[" (keys result) "]"))
                    (println (str "stream-live > event-name[" event-name "] signals[" (-> result :signals keys) "]"))
                    (println (str "stream-live > event-name[" event-name "] strategies[" (:strategies result) "]")))

        options {;; :tee-list [(partial tlive/tee-fn output-fn stock-name)]
                 :stock-match {:symbol stock-name :ticker-id-filter ticker-id-filter}}]


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
          ema-list-ch (chan (sliding-buffer 100))
          bollinger-band-ch (chan (sliding-buffer 100))
          macd-ch (chan (sliding-buffer 100))

          tick-list->sma-ch (chan (sliding-buffer 100))
          tick-list->macd-ch (chan (sliding-buffer 100))
          sma-list->ema-ch (chan (sliding-buffer 100))
          sma-list->bollinger-band-ch (chan (sliding-buffer 100))
          sma-list->macd-ch (chan (sliding-buffer 100))

          ;; TODO join tick-list, sma-list
          ;; tick-list->macd-ch (bind-channels->mult tick-list-ch)

          tick-list->stochastic-osc-ch (chan (sliding-buffer 100))
          tick-list->obv-ch (chan (sliding-buffer 100))
          tick-list->relative-strength-ch (chan (sliding-buffer 100))]

      (bind-channels->mult tick-list-ch
                           tick-list->sma-ch
                           tick-list->macd-ch
                           tick-list->stochastic-osc-ch
                           tick-list->obv-ch
                           tick-list->relative-strength-ch)

      (bind-channels->mult sma-list-ch
                           sma-list->ema-ch
                           sma-list->bollinger-band-ch
                           sma-list->macd-ch)

      (pipeline n tick-list-ch live/handler-xform (ew/ewrapper :publisher))
      (pipeline n sma-list-ch (map (partial alag/simple-moving-average options)) tick-list->sma-ch)
      (pipeline n ema-list-ch (map (partial alag/exponential-moving-average options live/moving-average-window)) sma-list->ema-ch)
      (pipeline n bollinger-band-ch (map (partial alag/bollinger-band live/moving-average-window)) sma-list->bollinger-band-ch)


      #_[options tick-window tick-list sma-list]

      #_{input-key :input
         output-key :output
         etal-keys :etal
         :or {input-key :last-trade-price
              output-key :last-trade-price-average
              etal-keys [:last-trade-price :last-trade-time :uuid]}}

      ;; {:last-trade-price 295.26,
      ;;  :last-trade-time 1524063528019,
      ;;  :uuid #uuid 3a889b10-4131-42ae-aefb-133acc63acfa,
      ;;  :last-trade-price-average 294.844,
      ;;  :population []}

      (pipeline n macd-ch (map (partial alead/macd {} live/moving-average-window [])) sma-list->macd-ch)
      (go-loop [r (<! macd-ch)]
        (println r)
        (when r
          (recur (<! macd-ch))))

      #_(let [tick-list->MACD (->> tick-list->macd-ch
                                 stream/->source
                                 (stream.cross/event-source->sorted-stream :id))
            sma-list->MACD (->> sma-list->macd-ch
                                stream/->source
                                (stream.cross/event-source->sorted-stream :id))

            result (stream.cross/set-streams-union {:default-key-fn :id
                                                    :skey-streams {:tick-list tick-list->MACD
                                                                   :sma-list sma-list->MACD}})

            connector-ch (chan (sliding-buffer 100))]

        ;; OK
        #_(go-loop [r (<! tick-list->macd-ch)]
          (println r)
          (when r
            (recur (<! tick-list->macd-ch))))

        ;; OK
        #_(stream/map (fn [r]
                      (prn ">> " r)
                      r)
                    sma-list->MACD)

        ;; FAIL
        (stream/connect @result connector-ch)
        (go-loop [r #_{:keys [tick-list sma-list] :as r} (<! connector-ch)]
            (println "record: " r)
            (if-not r
              r
              (recur (<! connector-ch))))

        #_(go-loop [r (<! bollinger-band-ch)]
          (println r)
          (if-not r
            r
            (recur (<! bollinger-band-ch))))


        ;; Final GOAL
        #_(pipeline n macd-ch (map (partial alead/macd options live/moving-average-window)) tick-list->MACD sma-list->MACD))


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
