(ns com.interrupt.edgar.core)


(defn loadData []
  (.stockChart js/Highcharts
               "container"
               #js {:rangeSelector {"selected" 1}
                    :title {:text "TSLA Stock Price"}
                    :series [{:name "TSLA"
                              :data []
                              :tooltip {"valueDecimals" 2}}]})
  (.log js/console "Done!!"))


(defn doc-ready-handler []
  (let[ready-state (. js/document -readyState)]
    (when (= "complete" ready-state)

      (.log js/console "DOMContentLoaded callback")

      (loadData)

      (let [onmessage-handler (fn [e]

                                (.log js/console (.-data e))

                                ;; Highcharts.charts[0].series[0].addPoint(eval(e.data), true, false)

                                (let [a (aget (.-charts js/Highcharts) 0)
                                      b (aget (.-series a) 0)]

                                  (.addPoint b (js-obj (.-data e)) true false)))
            w (js/WebSocket. "ws://localhost:8080/ws")]

        (set! (.-onmessage w) onmessage-handler)
        (set! (.-onclose w)
              (fn [e]
                (.log js/console "The connection to the server has closed.")))))))


(aset  js/document "onreadystatechange" doc-ready-handler )
