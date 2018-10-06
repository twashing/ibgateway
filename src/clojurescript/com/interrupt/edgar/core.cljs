(ns com.interrupt.edgar.core
  (:require [cognitect.transit :as t]))


(defn read-transit [x]
  (let [r (t/reader :json)]
    (t/read r x)))

(def yaxis-config [{:title {:text "Technical Analysis"}
                    :height 200
                    ;; :shadow false
                    ;; :turboThreshold 50
                    ;; :marker {:enabled false}
                    }
                   {:title {:text "MACD / Signal"}
                    :height 100
                    :top 300
                    ;; :offset 0
                    ;; :lineWidth 2

                    ;; :turboThreshold 50
                    ;; :shadow false
                    ;; :marker {:enabled false}
                    ;; :plotOptions{:series {:enableMouseTracking false}}
                    }
                   {:title {:text "MACD Histog"}
                    :height 100
                    :top 400
                    ;; :offset 0
                    ;; :lineWidth 2

                    ;; :turboThreshold 50
                    ;; :shadow false
                    ;; :marker {:enabled false}
                    ;; :plotOptions{:series {:enableMouseTracking false}}
                    }
                   {:title {:text "Stochastic Osc"}
                    :height 100
                    :top 500
                    :offset 0
                    :lineWidth 2
                    :max 1
                    :min 0
                    ;; :turboThreshold 50
                    ;; :shadow false
                    ;; :marker {:enabled false}
                    ;; :plotOptions{:series {:enableMouseTracking false}}
                    :plotLines [{:value 0.75
                                 :color "red"
                                 :width 1
                                 :dashStyle "longdash"
                                 :label {:text "Overbought"}}
                                {:value 0.25
                                 :color "green"
                                 :width 1
                                 :dashStyle "longdash"
                                 :label {:text "Oversold"}}]}
                   {:title {:text "OBV"}
                    :height 100
                    :top 600
                    :offset 0
                    :lineWidth 2
                    ;; :turboThreshold 50
                    ;; :shadow false
                    ;; :marker {:enabled false}
                    ;; :plotOptions{:series {:enableMouseTracking false}}
                    }])

(defn ->series-config
  [chart-color]

  [{:name "Closing Price"
    :id "tick-list"
    :yAxis 0
    :data []
    ;; :marker {:enabled true :radius 3}
    :tooltip {"valueDecimals" 2}}

   {:name "Simple Moving Average"
    :id "sma-list"
    :yAxis 0
    :data []
    ;; :marker {:enabled true :radius 3}
    :tooltip {"valueDecimals" 2}}

   {:name "Exponential Moving Average"
    :id "ema-list"
    :yAxis 0
    :data []
    ;; :marker {:enabled true :radius 3}
    :tooltip {"valueDecimals" 2}}

   {:name "Bollinger Band"
    :id "bollinger-list"
    :type "arearange"
    :lineWidth 0
    :fillOpacity 0.3
    :zIndex 0
    :yAxis 0
    :data []
    ;; :marker {:enabled true :radius 3}
    :tooltip {"valueDecimals" 2}
    :color chart-color}

   ;; MACD Data
   {:name "MACD Price"
    :id "macd-price-list"
    :yAxis 1
    :data []
    ;; :marker {:enabled true :radius 3}
    ;; :shadow true
    :tooltip {:valueDecimals 2}}

   {:name "MACD Signal"
    :id "macd-signal-list"
    :yAxis 1
    :data []
    ;; :marker {:enabled true :radius 3}
    ;; :shadow true
    :tooltip {:valueDecimals 2}}

   {:name "MACD Histogram"
    :id "macd-histogram-list"
    :yAxis 2
    :data []
    :type "column"
    ;; :marker {:enabled true :radius 3}
    ;; :shadow true
    :tooltip {:valueDecimals 2}}

   ;; Stochastic Data
   {:name "Stochastic K"
    :id "k-list"
    :yAxis 3
    :data []
    ;; :marker {:enabled true :radius 3}
    ;; :shadow true
    :tooltip {:valueDecimals 2}}
   {:name "Stochastic D"
    :id "d-list"
    :yAxis 3
    :data []
    ;; :marker {:enabled true :radius 3}
    ;; :shadow true
    :tooltip {:valueDecimals 2}}

   {:name "On Balance Volume"
    :id "obv-list"
    :yAxis 4
    :data []
    :type "column"
    ;; :marker {:enabled true :radius 3}
    ;; :shadow true
    :tooltip {:valueDecimals 2}}

   ])

(defn loadData []

  (.log js/console "loadData CALLED")

  (let [chart-color (aget (.-colors (.getOptions js/Highcharts)) 0)
        chart-options (clj->js {:title {:text "TSLA Stock Price"}
                                :yAxis yaxis-config
                                :series (->series-config chart-color)})]

    (.log js/console "chart-options: " chart-options)
    (.stockChart js/Highcharts "container" chart-options)))

(defn onmessage-handler [e]

  (let [charts (aget (.-charts js/Highcharts) 0)

        tick-series (aget (.-series charts) 0)
        sma-series (aget (.-series charts) 1)
        ema-series (aget (.-series charts) 2)
        bband-series (aget (.-series charts) 3)

        macd-price-series (aget (.-series charts) 4)
        macd-signal-series (aget (.-series charts) 5)
        macd-histogram-series (aget (.-series charts) 6)

        stochastic-kseries (aget (.-series charts) 7)
        stochastic-dseries (aget (.-series charts) 8)
        on-balance-volume-series (aget (.-series charts) 9)

        {{:keys [last-trade-time last-trade-price]} :tick-list
         {:keys [last-trade-price-average]} :sma-list
         {:keys [last-trade-price-exponential]} :ema-list
         {:keys [upper-band lower-band]} :bollinger-band
         {:keys [last-trade-price last-trade-time
                 last-trade-macd ema-signal histogram]} :macd
         {:keys [last-trade-time last-trade-price
                 highest-price lowest-price K D]} :stochastic-oscillator
         {:keys [obv total-volume last-trade-price last-trade-time]} :on-balance-volume
         {:keys [last-trade-time last-trade-price rs rsi]} :relative-strength
         :as message}
        (read-transit (.-data e))

        tick [last-trade-time last-trade-price]
        sma [last-trade-time last-trade-price-average]
        ema [last-trade-time last-trade-price-exponential]
        bband [last-trade-time lower-band upper-band]

        macd-price [last-trade-time last-trade-macd]
        macd-signal [last-trade-time ema-signal]
        macd-histogram [last-trade-time histogram]


        stochastic-oscillator-k [last-trade-time K]
        stochastic-oscillator-d [last-trade-time D]
        on-balance-volume [last-trade-time obv]
        ;; relative-strength [last-trade-time]
        ]

    ;; TODO
    ;; DataLabels
    ;;   https://api.highcharts.com/highstock/series.ad.dataLabels
    ;;   https://stackoverflow.com/questions/17053158/howto-combine-highstock-highcharts-addpoint-function-with-flags
    ;; Annotations (incl Plot bands)
    ;;   http://jsfiddle.net/gh/get/library/pure/highcharts/highcharts/tree/master/samples/highcharts/annotations/basic
    ;;   https://www.highcharts.com/blog/products/cloud/docs/annotating-your-charts


    ;; (.log js/console (js/eval (clj->js message)))
    ;; (.log js/console "----")
    ;; (.log js/console (str "macd-price-series / " (js/eval (clj->js macd-price ))))
    ;; (.log js/console (str "macd-signal-series / " (js/eval (clj->js macd-signal))))
    ;; (.log js/console (str "macd-histogram-series / " (js/eval (clj->js macd-histogram))))
    ;; (.log js/console (str "stochastic-kseries / " (js/eval (clj->js stochastic-oscillator-k))))
    ;; (.log js/console (str "stochastic-dseries / " (js/eval (clj->js stochastic-oscillator-d))))
    ;; (.log js/console (str "on-balance-volume-series / " (js/eval (clj->js on-balance-volume))))

    (when tick (.addPoint tick-series (js/eval (clj->js tick)) true false))
    (when sma (.addPoint sma-series (js/eval (clj->js sma)) true false))
    (when ema (.addPoint ema-series (js/eval (clj->js ema)) true false))
    (when bband (.addPoint bband-series (js/eval (clj->js bband)) true false))

    (when macd-price (.addPoint macd-price-series (js/eval (clj->js macd-price)) true false))
    (when macd-signal (.addPoint macd-signal-series (js/eval (clj->js macd-signal)) true false))
    (when macd-histogram (.addPoint macd-histogram-series (js/eval (clj->js macd-histogram)) true false))

    (when stochastic-oscillator-k (.addPoint stochastic-kseries (js/eval (clj->js stochastic-oscillator-k)) true false))
    (when stochastic-oscillator-d (.addPoint stochastic-dseries (js/eval (clj->js stochastic-oscillator-d)) true false))
    (when on-balance-volume (.addPoint on-balance-volume-series (js/eval (clj->js on-balance-volume)) true false))))

(defn doc-ready-handler []
  (let[ready-state (. js/document -readyState)]
    (when (= "complete" ready-state)

      (.log js/console "DOMContentLoaded callback")

      (loadData)

      (let [w (js/WebSocket. "ws://localhost:8080/ws")]

        (set! (.-onmessage w) onmessage-handler)
        (set! (.-onclose w)
              (fn [e]
                (.log js/console "The connection to the server has closed.")))))))


(aset  js/document "onreadystatechange" doc-ready-handler )
