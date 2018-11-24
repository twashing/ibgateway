(ns com.interrupt.ibgateway.component.execution-engine
  (:require [clojure.core.async
             :refer [chan >! >!! <! <!! alts! close! merge go go-loop pub sub unsub-all
                     sliding-buffer mult tap pipeline] :as async]
            [clojure.core.match :refer [match]]
            [clojure.tools.logging :refer [info]]
            [clojure.tools.trace :refer [trace]]
            [clojure.set :as s]
            [com.rpl.specter :refer :all]
            [mount.core :refer [defstate] :as mount]
            [com.interrupt.ibgateway.component.common :refer [bind-channels->mult]]
            [com.interrupt.ibgateway.component.ewrapper :as ewrapper]
            [com.interrupt.ibgateway.component.account :refer [account account-name consume-order-updates]]
            [com.interrupt.ibgateway.component.processing-pipeline :as pp]
            [com.interrupt.edgar.ib.market :as market]))


(def lagging-signals #{:moving-average-crossover
                       :bollinger-divergence-overbought
                       :bollinger-divergence-oversold
                       :bollinger-close-abouve
                       :bollinger-close-below})

(def leading-signals #{:macd-signal-crossover
                       :macd-divergence
                       :stochastic-overbought
                       :stochastic-oversold
                       :stochastic-crossover
                       :stochastic-divergence})

(def confirming-signals #{:obv-divergence})

(defn lagging-signals? [a] (s/subset? a lagging-signals))
(defn leading-signals? [a] (s/subset? a leading-signals))
(defn confirming-signals? [a] (s/subset? a confirming-signals))

(defn identity-or-empty [l]
  (if-not (empty? l) l false))

(defn set->has-signal-fn [s]
  (fn [a]
    (->>
      (:why a)
      (conj [])
      (some s))))

(defn has-lagging-signal? [a]
  (let [f (set->has-signal-fn lagging-signals)]
    (filter f a)))

(defn has-leading-signal? [a]
  (let [f (set->has-signal-fn leading-signals)]
    (filter f a)))

(defn has-confirming-signal? [a]
  (let [f (set->has-signal-fn confirming-signals)]
    (filter f a)))

(defn which-signals? [joined-ticked]
  (->> joined-ticked
       vals
       (map :signals)
       (keep identity)
       flatten
       ((juxt has-lagging-signal? has-leading-signal? has-confirming-signal?))
       (map identity-or-empty)))


(def one
  {:signal-stochastic-oscillator {:last-trade-time 1534782057122 :last-trade-price 297.79 :highest-price 298.76 :lowest-price 297.78 :K 0.010204081632701595 :D 0.03316326530614967 :signals [{:signal :up :why :stochastic-oversold}]}
   :signal-on-balance-volume {:obv -112131 :total-volume 112307 :last-trade-price 297.79 :last-trade-time 1534782057122}
   :stochastic-oscillator {:last-trade-time 1534782057122 :last-trade-price 297.79 :highest-price 298.76 :lowest-price 297.78 :K 0.010204081632701595 :D 0.03316326530614967}
   :macd {:last-trade-price 297.79 :last-trade-time 1534782057122 :last-trade-macd -0.1584495767526164 :ema-signal -0.10971536943309429 :histogram -0.048734207319522105}
   :signal-moving-averages {:last-trade-price 297.79 :last-trade-time 1534782057122 :uuid "9977571e-cba4-4532-b78b-a5cab2292a80" :last-trade-price-average 298.298 :last-trade-price-exponential 298.24896148209336}
   :sma-list {:last-trade-price 297.79 :last-trade-time 1534782057122 :uuid "9977571e-cba4-4532-b78b-a5cab2292a80" :last-trade-price-average 298.298}
   :signal-bollinger-band {:last-trade-price 297.79 :last-trade-time 1534782057122 :uuid "9977571e-cba4-4532-b78b-a5cab2292a80" :upper-band 298.80240459950323 :lower-band 297.79359540049677}
   :ema-list {:last-trade-price 297.79 :last-trade-time 1534782057122 :uuid "9977571e-cba4-4532-b78b-a5cab2292a80" :last-trade-price-exponential 298.24896148209336}
   :on-balance-volume {:obv -112131 :total-volume 112307 :last-trade-price 297.79 :last-trade-time 1534782057122}
   :signal-macd {:last-trade-price 297.79 :last-trade-time 1534782057122 :last-trade-macd -0.1584495767526164 :ema-signal -0.10971536943309429 :histogram -0.048734207319522105 :signals [{:signal :up :why :macd-divergence}]}
   :tick-list {:last-trade-price 297.79 :last-trade-size 2 :last-trade-time 1534782057122 :total-volume 112307 :vwap 297.90072935 :single-trade-flag false :ticker-id 0 :type :tick-string :uuid "9977571e-cba4-4532-b78b-a5cab2292a80"}
   :relative-strength {:last-trade-time 1534782057122 :last-trade-price 297.79 :rs 0.9006878372532118 :rsi 47.3874678208519}
   :bollinger-band {:last-trade-price 297.79 :last-trade-time 1534782057122 :uuid "9977571e-cba4-4532-b78b-a5cab2292a80" :upper-band 298.80240459950323 :lower-band 297.79359540049677}})

(def two
  {:a {:signals [{:signal :up, :why :stochastic-oversold}]}
   :b {:signals [{:signal :up, :why :macd-divergence}]}})

(def three
  {:a {:signals [{:signal :up, :why :moving-average-crossover}]}
   :b {:signals [{:signal :up, :why :stochastic-oversold}]}
   :c {:signals [{:signal :up, :why :macd-divergence}]}})

(def four
  {:a {:signals [{:signal :up, :why :stochastic-oversold}
                 {:signal :down :why :moving-average-crossover}]}
   :b {:signals [{:signal :up, :why :macd-divergence}]}})

(defn which-ups? [which-signals]
  (let [[lags leads confs] (transform
                             [ALL #(and ((comp not false?) %)
                                        ((comp not empty?) %))]
                             #(map :signal %)
                             which-signals)
        all-ups? (fn [a]
                   (if-not (false? a)
                     (every? #(= :up %) a)
                     a))]

    (map all-ups? [lags leads confs])))

(defn next-valid-order-id [client valid-order-id-ch]
  (.reqIds client -1)
  (<!! valid-order-id-ch))

(defn ->account-cash-level [client account-updates-ch]
  (.reqAccountSummary client 9001 "All" "TotalCashValue")
  (<!! account-updates-ch))

(defn derive-order-quantity [cash-level price]
  (-> (cond
        (< cash-level 500) (* 0.5 cash-level)
        (<= cash-level 2000) 500
        (> cash-level 2000) (* 0.25 cash-level)
        (> cash-level 10000) (* 0.1 cash-level)
        (> cash-level 100000) (* 0.05 cash-level))
      (/ price)
      (.longValue)))

(defn extract-signals+decide-order [client joined-tick account-name {account-updates-ch :account-updates} valid-order-id-ch]

  (let [order-type "MKT"
        instrm "TSLA" ;; TODO pull from joined-tick

        price (-> joined-tick :sma-list :last-trade-price)
        {cash-level :value} (->account-cash-level client account-updates-ch) ;; TODO make a mock version of this
        qty (derive-order-quantity cash-level price)
        order-id (next-valid-order-id client valid-order-id-ch)

        [laggingS leadingS confirmingS] (-> joined-tick which-signals? which-ups?)]

    (match [laggingS leadingS confirmingS]
           [true true true] :A-buy-stock ;; (market/buy-stock "MKT" client order-id order-type account-name instrm qty price)
           [true true _] :B-buy-stock ;; (market/buy-stock "MKT" client order-id order-type account-name instrm qty price)
           [_ true true] :C-buy-stock ;; (market/buy-stock "MKT" client order-id order-type account-name instrm qty price)
           :else :noop)))

(comment

  (which-signals? one)
  (which-signals? three)
  (which-signals? four)

  (let [[laggingS leadingS confirmingS] (-> one which-signals? which-ups?)]
    (match [laggingS leadingS confirmingS]
           [true true _] :a
           :else :b))

  (do
    (mount/stop #'ewrapper/default-chs-map #'ewrapper/ewrapper #'account)
    (mount/start #'ewrapper/default-chs-map #'ewrapper/ewrapper #'account)

    (def client (:client ewrapper/ewrapper))
    (def wrapper (:wrapper ewrapper/ewrapper))
    (def valid-order-id-ch (chan))

    (consume-order-updates ewrapper/default-chs-map valid-order-id-ch))

  (next-valid-order-id client valid-order-id-ch))

(defn consume-joined-channel [joined-channel-tapped account+order-updates-map valid-order-id-ch client account-name]

  (go-loop [c 0 joined-tick (<! joined-channel-tapped)]
    (if-not joined-tick
      joined-tick
      (let [sr (update-in joined-tick [:sma-list] dissoc :population)]

        (info "count: " c " / sr: " sr)
        (extract-signals+decide-order client joined-tick account-name account+order-updates-map valid-order-id-ch)

        (recur (inc c) (<! joined-channel-tapped))))))

(defn setup-execution-engine [processing-pipeline wrapper default-chs account-name]

  (let [client (:client wrapper)
        account+order-updates-map default-chs
        valid-order-id-ch (chan)

        {joined-channel :joined-channel} processing-pipeline
        joined-channel-tapped (chan (sliding-buffer 100))]

    (bind-channels->mult joined-channel joined-channel-tapped)
    (consume-order-updates account+order-updates-map valid-order-id-ch)
    (consume-joined-channel joined-channel-tapped account+order-updates-map valid-order-id-ch client account-name)

    joined-channel-tapped)

  ;; TODO

  ;; Place order
  ;; Updates account cash level
  ;; Once a BUY order is "Filled", place a "TRAIL" sell order
  ;;   listen to / #'ew/default-chs-map / order-updates / open-order (:filled)


  ;; TODO

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


  ;; TODO

  ;;  Add :buy :sell annotations to stream
  )

(defn teardown-execution-engine [ee]
  (when-not (nil? ee)
    (close! ee)))


(defstate execution-engine
  :start (setup-execution-engine pp/processing-pipeline ewrapper/ewrapper ewrapper/default-chs-map account-name)
  :stop (teardown-execution-engine execution-engine))
