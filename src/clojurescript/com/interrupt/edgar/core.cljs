(ns com.interrupt.edgar.core
  (:require [cognitect.transit :as t]))


(defn read-transit [x]
  (let [r (t/reader :json)]
    (t/read r x)))

(defn loadData []

  (.log js/console "loadData CALLED")

  (let [chart-options (clj->js {:rangeSelector {"selected" 1}
                                :title {:text "TSLA Stock Price"}
                                :series [{:name "TSLA"
                                          :data []
                                          :tooltip {"valueDecimals" 2}}
                                         {:name "TSLA 2"
                                          :data []
                                          :tooltip {"valueDecimals" 2}}]})]

    (.log js/console "chart-options: " chart-options)
    (.stockChart js/Highcharts "container" chart-options)))

(defn onmessage-handler [e]

  #_(.log js/console (.-data e))
  #_(.log js/console (js/eval (clj->js (read-transit (.-data e)))))

  (let [a (aget (.-charts js/Highcharts) 0)
        b (aget (.-series a) 0)

        {{:keys [last-trade-time last-trade-price]} :tick-list
         {:keys [last-trade-price-average]} :sma-list
         {:keys [last-trade-price-exponential]} :ema-list :as message}

        (read-transit (.-data e))

        tick [last-trade-time last-trade-price]]

    (.log js/console message)
    (.addPoint b (js/eval (clj->js tick)) true false)

    ))

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
