(ns clojure.com.interrupt.ibgateway.component.execution-engine
  (:require [mount.core :refer [defstate] :as mount]))

(defn setup-execution-engine []


  ;; Extract signals from processcing pipeline
  {:signal-stochastic-oscillator {:last-trade-time 1534782057122, :last-trade-price 297.79, :highest-price 298.76, :lowest-price 297.78, :K 0.010204081632701595, :D 0.03316326530614967, :signals ({:signal :up, :why :stochastic-oversold})},
   :signal-on-balance-volume {:obv -112131, :total-volume 112307, :last-trade-price 297.79, :last-trade-time 1534782057122},
   :stochastic-oscillator {:last-trade-time 1534782057122, :last-trade-price 297.79, :highest-price 298.76, :lowest-price 297.78, :K 0.010204081632701595, :D 0.03316326530614967},
   :macd {:last-trade-price 297.79, :last-trade-time 1534782057122, :last-trade-macd -0.1584495767526164, :ema-signal -0.10971536943309429, :histogram -0.048734207319522105},
   :signal-moving-averages {:last-trade-price 297.79, :last-trade-time 1534782057122, :uuid 9977571e-cba4-4532-b78b-a5cab2292a80, :last-trade-price-average 298.298, :last-trade-price-exponential 298.24896148209336},
   :sma-list {:last-trade-price 297.79, :last-trade-time 1534782057122, :uuid 9977571e-cba4-4532-b78b-a5cab2292a80, :last-trade-price-average 298.298},
   :signal-bollinger-band {:last-trade-price 297.79, :last-trade-time 1534782057122, :uuid 9977571e-cba4-4532-b78b-a5cab2292a80, :upper-band 298.80240459950323, :lower-band 297.79359540049677},
   :ema-list {:last-trade-price 297.79, :last-trade-time 1534782057122, :uuid 9977571e-cba4-4532-b78b-a5cab2292a80, :last-trade-price-exponential 298.24896148209336},
   :on-balance-volume {:obv -112131, :total-volume 112307, :last-trade-price 297.79, :last-trade-time 1534782057122},
   :signal-macd {:last-trade-price 297.79, :last-trade-time 1534782057122, :last-trade-macd -0.1584495767526164, :ema-signal -0.10971536943309429, :histogram -0.048734207319522105, :signals [{:signal :up, :why :macd-divergence}]},
   :tick-list {:last-trade-price 297.79, :last-trade-size 2, :last-trade-time 1534782057122, :total-volume 112307, :vwap 297.90072935, :single-trade-flag false, :ticker-id 0, :type :tick-string, :uuid 9977571e-cba4-4532-b78b-a5cab2292a80},
   :relative-strength {:last-trade-time 1534782057122, :last-trade-price 297.79, :rs 0.9006878372532118, :rsi 47.3874678208519},
   :bollinger-band {:last-trade-price 297.79, :last-trade-time 1534782057122, :uuid 9977571e-cba4-4532-b78b-a5cab2292a80, :upper-band 298.80240459950323, :lower-band 297.79359540049677}}

  ;; If :up signal from lagging + leading (or more), then BUY

  ;; If any :down signal, then SELL

  ;;  Add :buy :sell annotations to stream


  )

(defn teardown-execution-engine [ee])

(defstate execution-engine
  :start (setup-execution-engine)
  :stop (teardown-execution-engine processing-pipeline))
