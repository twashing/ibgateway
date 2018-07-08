(ns com.interrupt.edgar.core)


(defn loadData []

  (.log js/console "loadData CALLED")

  (let [series-array (clj->js {:name "TSLA"
                               :data []
                               :tooltip {"valueDecimals" 2}})
        chart-options (clj->js {:rangeSelector {"selected" 1}
                                :title {:text "TSLA Stock Price"}
                                :series series-array})]

    (.log js/console "chart-options: " chart-options)
    (.stockChart js/Highcharts "container" chart-options)))


(defn onmessage-handler [e]

  (.log js/console (js/eval (clj->js (.-data e))))

  ;; Highcharts.charts[0].series[0].addPoint(eval(e.data), true, false)

  (let [a (aget (.-charts js/Highcharts) 0)
        b (aget (.-series a) 0)]

    ;; (.addPoint b (js->clj (.-data e)) true false)
    ;; (.addPoint b (.-data e) true false)
    (.addPoint b (js/eval (clj->js (.-data e))) true false)

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
