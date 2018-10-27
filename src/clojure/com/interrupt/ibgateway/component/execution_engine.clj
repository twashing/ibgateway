(ns com.interrupt.ibgateway.component.execution-engine
  (:require [clojure.core.async
             :refer [chan >! >!! <! <!! alts! close! merge go go-loop pub sub unsub-all
                     sliding-buffer mult tap pipeline] :as async]
            [clojure.tools.logging :refer [info]]
            [clojure.tools.trace :refer [trace]]
            [clojure.set :as s]
            [mount.core :refer [defstate] :as mount]
            [com.interrupt.ibgateway.component.processing-pipeline :as pp]
            ;; [clojure.spec.alpha :as s]
            ))


(def lagging-signals #{:moving-average-crossover
                       :bollinger-divergence-overbought
                       :bollinger-divergence-oversold
                       :bollinger-close-abouve
                       :bollinger-close-below})

(defn lagging-signals? [a]
  (s/subset? a lagging-signals))

(def leading-signals #{:macd-signal-crossover
                       :macd-divergence
                       :stochastic-overbought
                       :stochastic-oversold
                       :stochastic-crossover
                       :stochastic-divergence})

(defn leading-signals? [a]
  (s/subset? a leading-signals))

(def confirming-signals #{:obv-divergence})

(defn confirming-signals? [a]
  (s/subset? a confirming-signals))


(def one {:signal-stochastic-oscillator {:last-trade-time 1534782057122, :last-trade-price 297.79, :highest-price 298.76, :lowest-price 297.78, :K 0.010204081632701595, :D 0.03316326530614967, :signals [{:signal :up, :why :stochastic-oversold}]},
          :signal-on-balance-volume {:obv -112131, :total-volume 112307, :last-trade-price 297.79, :last-trade-time 1534782057122},
          :stochastic-oscillator {:last-trade-time 1534782057122, :last-trade-price 297.79, :highest-price 298.76, :lowest-price 297.78, :K 0.010204081632701595, :D 0.03316326530614967},
          :macd {:last-trade-price 297.79, :last-trade-time 1534782057122, :last-trade-macd -0.1584495767526164, :ema-signal -0.10971536943309429, :histogram -0.048734207319522105},
          :signal-moving-averages {:last-trade-price 297.79, :last-trade-time 1534782057122, :uuid "9977571e-cba4-4532-b78b-a5cab2292a80", :last-trade-price-average 298.298, :last-trade-price-exponential 298.24896148209336},
          :sma-list {:last-trade-price 297.79, :last-trade-time 1534782057122, :uuid "9977571e-cba4-4532-b78b-a5cab2292a80", :last-trade-price-average 298.298},
          :signal-bollinger-band {:last-trade-price 297.79, :last-trade-time 1534782057122, :uuid "9977571e-cba4-4532-b78b-a5cab2292a80", :upper-band 298.80240459950323, :lower-band 297.79359540049677},
          :ema-list {:last-trade-price 297.79, :last-trade-time 1534782057122, :uuid "9977571e-cba4-4532-b78b-a5cab2292a80", :last-trade-price-exponential 298.24896148209336},
          :on-balance-volume {:obv -112131, :total-volume 112307, :last-trade-price 297.79, :last-trade-time 1534782057122},
          :signal-macd {:last-trade-price 297.79, :last-trade-time 1534782057122, :last-trade-macd -0.1584495767526164, :ema-signal -0.10971536943309429, :histogram -0.048734207319522105, :signals [{:signal :up, :why :macd-divergence}]},
          :tick-list {:last-trade-price 297.79, :last-trade-size 2, :last-trade-time 1534782057122, :total-volume 112307, :vwap 297.90072935, :single-trade-flag false, :ticker-id 0, :type :tick-string, :uuid "9977571e-cba4-4532-b78b-a5cab2292a80"},
          :relative-strength {:last-trade-time 1534782057122, :last-trade-price 297.79, :rs 0.9006878372532118, :rsi 47.3874678208519},
          :bollinger-band {:last-trade-price 297.79, :last-trade-time 1534782057122, :uuid "9977571e-cba4-4532-b78b-a5cab2292a80", :upper-band 298.80240459950323, :lower-band 297.79359540049677}})


(->> one vals (map :signals) (keep identity))

(group-by #(some lagging-signals (map :why %)) one)


(->> one
     vals
     (map :signals)
     (keep identity)
     flatten
     ((fn [a]
        (trace a)
        (trace (some leading-signals (map :why a))))))


(def two '([{:signal :up, :why :stochastic-oversold}]
           [{:signal :up, :why :macd-divergence}]))


(->> two
     (map #(group-by :why %)))

(map (juxt lagging-signals?
           leading-signals?
           confirming-signals?)

     (->> '({:stochastic-oversold [{:signal :up, :why :stochastic-oversold}]}
            {:macd-divergence [{:signal :up, :why :macd-divergence}]})
          (map (comp set keys))))

(group-by leading-signals?
          (->> '({:stochastic-oversold [{:signal :up, :why :stochastic-oversold}]}
                 {:macd-divergence [{:signal :up, :why :macd-divergence}]})
               (map (comp set keys))))


(def three '([{:signal :up, :why :moving-average-crossover}]
             [{:signal :up, :why :stochastic-oversold}]
             [{:signal :up, :why :macd-divergence}]))

(def four '([{:signal :up, :why :stochastic-oversold}
             {:signal :down :why :moving-average-crossover}]
            [{:signal :up, :why :macd-divergence}]))


(defn setup-execution-engine []

  ;; Extract signals from processcing pipeline


  ;; ** ALWAYS keep a running count of cash balance


  ;; BUY if
  ;;   :up signal from lagging + leading (or more)
  ;;   within last 3 ticks
  ;;   we have enough money
  ;;   * buy up to $1000 or 50% of cash (whichever is less)


  ;; SELL if
  ;;   any :down signal
  ;;   start losing
  ;;     more than 5 percent of gain (abouve a 5 percent price rise)
  ;;     more than 5 percent of original purchase price
  ;;   ? speed of change (howto measure)
  ;;   ? 5 percent outside of volatility range


  ;;  Add :buy :sell annotations to stream


  #_(let [{joined-channel :joined-channel} pp/processing-pipeline
        joined-channel-tapped (chan (sliding-buffer 100))]

    (pp/bind-channels->mult joined-channel joined-channel-tapped)

    (go-loop [c 0 r (<! joined-channel-tapped)]
      (if-not r
        r
        (let [sr (update-in r [:sma-list] dissoc :population)]
          (info "count: " c " / sr: " r)
          (recur (inc c) (<! joined-channel-tapped)))))

    joined-channel-tapped))


(defn teardown-execution-engine [ee]
  (when-not (nil? ee)
    (close! ee)))

(defstate execution-engine
  :start (setup-execution-engine)
  :stop (teardown-execution-engine execution-engine))
