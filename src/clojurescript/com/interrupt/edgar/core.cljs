(ns com.interrupt.edgar.core
  (:require [cognitect.transit :as t]))


(defn read-transit [x]
  (let [r (t/reader :json)]
    (t/read r x)))

(defn loadData []

  (.log js/console "loadData CALLED")

  (let [chart-color (aget (.-colors (.getOptions js/Highcharts)) 0)
        chart-options (clj->js {:rangeSelector {"selected" 1}
                                :title {:text "TSLA Stock Price"}
                                :series [{:name "Tick List"
                                          :data []
                                          :tooltip {"valueDecimals" 2}}
                                         {:name "SMA"
                                          :data []
                                          :tooltip {"valueDecimals" 2}}
                                         {:name "EMA"
                                          :data []
                                          :tooltip {"valueDecimals" 2}}
                                         {:name "Bollinger Band"
                                          :type "arearange"
                                          :lineWidth 0
                                          :fillOpacity 0.3
                                          :zIndex 0
                                          :data []
                                          :tooltip {"valueDecimals" 2}
                                          :color chart-color}]})]

    (.log js/console "chart-options: " chart-options)
    (.stockChart js/Highcharts "container" chart-options)))

(defn onmessage-handler [e]

  #_(.log js/console (.-data e))
  #_(.log js/console (js/eval (clj->js (read-transit (.-data e)))))

  (let [charts (aget (.-charts js/Highcharts) 0)
        tick-series (aget (.-series charts) 0)
        sma-series (aget (.-series charts) 1)
        ema-series (aget (.-series charts) 2)
        upper-series (aget (.-series charts) 3)
        lower-series (aget (.-series charts) 4)

        {{:keys [last-trade-time last-trade-price]} :tick-list
         {:keys [last-trade-price-average]} :sma-list
         {:keys [last-trade-price-exponential]} :ema-list
         {:keys [upper-band lower-band]} :bollinger-band :as message}

        (read-transit (.-data e))

        tick [last-trade-time last-trade-price]
        sma [last-trade-time last-trade-price-average]
        ema [last-trade-time last-trade-price-exponential]
        bband [last-trade-time lower-band upper-band]]

    ;; (.log js/console d)
    (.log js/console (js/eval (clj->js message)))
    (.addPoint tick-series (js/eval (clj->js tick)) true false)
    (.addPoint sma-series (js/eval (clj->js sma)) true false)
    (.addPoint ema-series (js/eval (clj->js ema)) true false)
    (.addPoint upper-series (js/eval (clj->js bband)) true false)))

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
