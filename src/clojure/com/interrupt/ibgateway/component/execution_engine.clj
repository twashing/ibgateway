(ns com.interrupt.ibgateway.component.execution-engine
  (:require [clojure.core.async
             :refer [chan >! >!! <! <!! alts! close! merge go go-loop pub sub unsub-all
                     sliding-buffer mult tap pipeline] :as async]
            [clojure.core.match :refer [match]]
            [clojure.tools.logging :refer [debug info]]
            [clojure.tools.trace :refer [trace]]
            [clojure.set :as s]
            [com.rpl.specter :refer :all]
            [mount.core :refer [defstate] :as mount]
            [com.interrupt.ibgateway.component.common :refer [bind-channels->mult]]
            [com.interrupt.ibgateway.component.ewrapper :as ewrapper]
            [com.interrupt.ibgateway.component.account :refer [account account-name consume-order-updates]]
            [com.interrupt.ibgateway.component.account.contract :as contract]
            [com.interrupt.ibgateway.component.processing-pipeline :as pp]
            [com.interrupt.edgar.ib.market :as market])
  (:import [com.ib.client Order]))


(def latest-standard-deviation (atom -1))

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

(defn ->next-valid-order-id
  ([client valid-order-id-ch]
   (->next-valid-order-id
     client valid-order-id-ch (fn [] (.reqIds client -1))))
  ([_ valid-order-id-ch f]
   (f)
   (<!! valid-order-id-ch)))

(defn ->account-cash-level

  ([client account-updates-ch]
   (->account-cash-level
     client account-updates-ch
     (fn []
       (.reqAccountSummary client 9001 "All" "TotalCashValue"))))

  ([_ account-updates-ch f]
   (f)
   (<!! account-updates-ch)))

;; TODO pick a better way to cap-order-quantity
(defn cap-order-quantity [quantity]
  1
  #_(if (< quantity 500) quantity 500))

(defn derive-order-quantity [cash-level price]
  (info "derive-order-quantity / " [cash-level price])
  1
  #_(-> (cond
        (< cash-level 500) (* 0.5 cash-level)
        (<= cash-level 2000) 500
        (> cash-level 2000) (* 0.25 cash-level)
        (> cash-level 10000) (* 0.1 cash-level)
        (> cash-level 100000) (* 0.05 cash-level)
        :else (* 0.05 cash-level))
      (/ price)
      (.longValue)
      cap-order-quantity))

(defn buy-stock [client joined-tick account-updates-ch valid-order-id-ch account-name instrm]
  (let [order-type "MKT"
        price (-> joined-tick :sma-list :last-trade-price)
        qty (derive-order-quantity
              (-> client (->account-cash-level account-updates-ch) :value)
              price)

        ;; TODO make a mock version of this
        order-id (->next-valid-order-id client valid-order-id-ch)]

    (info "buy-stock / client, " [order-id order-type account-name instrm qty price])
    (market/buy-stock client order-id order-type account-name instrm qty price)))

(defn extract-signals+decide-order [client joined-tick instrm account-name
                                    {account-updates-ch :account-updates} valid-order-id-ch]

  (let [[laggingS leadingS confirmingS] (-> joined-tick which-signals? which-ups?)]

    (info "extract-signals+decide-order / " [laggingS leadingS confirmingS])
    (match [laggingS leadingS confirmingS]
           [true true true] (buy-stock client joined-tick account-updates-ch valid-order-id-ch account-name instrm)
           [true true _] (buy-stock client joined-tick account-updates-ch valid-order-id-ch account-name instrm)
           [_ true true] (buy-stock client joined-tick account-updates-ch valid-order-id-ch account-name instrm)
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
    (def order-filled-notification-ch (chan))
    (def account-updates-ch (:account-updates ewrapper/default-chs-map))

    (consume-order-updates ewrapper/default-chs-map valid-order-id-ch order-filled-notification-ch))

  (->next-valid-order-id client valid-order-id-ch)
  (->account-cash-level client account-updates-ch))

(defn consume-order-filled-notifications [client order-filled-notification-ch valid-order-id-ch]

  (go-loop [{:keys [stock order] :as val} (<! order-filled-notification-ch)]
    (info "consume-order-filled-notifications LOOPED / " val)
    (let [symbol (:symbol stock)
          action "SELL"
          quantity (:quantity order)
          valid-order-id (->next-valid-order-id client valid-order-id-ch)
          ;; trailingPercent 1
          ;; trail-price (if (< @latest-standard-deviation 0.5) 0.5 @latest-standard-deviation)

          ;; (clojure.pprint/cl-format nil "~,2f" 23.456)
          ;; (clojure.pprint/cl-format nil "~,2f" 0.0057683)
          ;; (clojure.pprint/cl-format nil "~,2f" 66.2)

          _ (println "1 / " @latest-standard-deviation)
          _ (println "2 / " (clojure.pprint/cl-format nil "~,2f" @latest-standard-deviation))
          _ (println "3 / " (type (clojure.pprint/cl-format nil "~,2f" @latest-standard-deviation)))
          auxPrice (->> @latest-standard-deviation
                        (clojure.pprint/cl-format nil "~,2f")
                        read-string)

          _ (println "4 / " auxPrice)
          _ (println "5 / " (:price order))
          _ (println "6 / " (type (:price order)))
          _ (println "7 / " (- (:price order) auxPrice))
          trailStopPrice (- (:price order) auxPrice)]

      (info "(balancing) sell-stock / client, " [quantity valid-order-id auxPrice #_trailingPercent trailStopPrice])
      (.placeOrder client
                   valid-order-id
                   (contract/create symbol)
                   (doto (Order.)
                     (.action action)
                     (.orderType "TRAIL")
                     ;; (.trailingPercent trailingPercent)
                     (.auxPrice auxPrice)
                     (.trailStopPrice trailStopPrice)
                     (.totalQuantity quantity))))

    (recur (<! order-filled-notification-ch))))

(defn consume-joined-channel [joined-channel-tapped account+order-updates-map valid-order-id-ch client instrm account-name]

  (go-loop [c 0 joined-tick (<! joined-channel-tapped)]
    (if-not joined-tick
      joined-tick
      (let [sr (update-in joined-tick [:sma-list] dissoc :population)
            account-updates-ch (:account-updates account+order-updates-map)]

        ;; (info "count: " c " / sr: " sr)
        (info "count: " c)


        ;; TODO design a better way to capture running standard-deviation
        (reset! latest-standard-deviation
                (-> joined-tick :bollinger-band :standard-deviation))

        (when (:sma-list joined-tick)
          (extract-signals+decide-order client joined-tick instrm account-name
                                        account+order-updates-map valid-order-id-ch))

        (recur (inc c) (<! joined-channel-tapped))))))

(defn setup-execution-engine [{joined-channel :joined-channel}
                              {account+order-updates-map :default-channels
                               {client :client} :ewrapper}
                              instrm account-name]

  (let [valid-order-id-ch (chan)
        order-filled-notification-ch (chan)
        joined-channel-tapped (chan (sliding-buffer 100))]


    (bind-channels->mult joined-channel joined-channel-tapped)


    ;; CONSUME ORDER UPDATES

    ;; TODO mock
    ;;   account+order-updates-map (->account-cash-level)
    ;;   valid-order-id-ch (->next-valid-order-id)
    ;;   order-filled-notification-ch
    (consume-order-updates account+order-updates-map valid-order-id-ch order-filled-notification-ch)


    ;; CONSUME ORDER FILLED NOTIFICATIONS

    ;; TODO mock
    ;;   order-filled-notification-ch
    ;;   valid-order-id-ch (->next-valid-order-id)
    (consume-order-filled-notifications client order-filled-notification-ch valid-order-id-ch)


    ;; CONSUME JOINED TICK STREAM
    (consume-joined-channel joined-channel-tapped account+order-updates-map valid-order-id-ch client instrm account-name)


    joined-channel-tapped)


  ;; TODO

  ;; [x] SELL if - always have trailing stop price
  ;;   any :down signal
  ;;   start losing
  ;;     more than 5 percent of gain (abouve a 5 percent price rise)
  ;;     more than 5 percent of original purchase price
  ;;   ? speed of change (howto measure)
  ;;   ? 5 percent outside of volatility range
  ;; [ok] derive sell price
  ;; [ok] why is conditionally-filled taking so long to execute
  ;;   responses seem to speed up, when I put print lines in the code path
  ;; [ok] In TRAIL stop orders, does the API let us see the trailingStopPrice
  ;;   add a column header with that value
  ;; [ok] re-check scanners to see that we're getting stocks with the most price movement
  ;; [ok] what is the minimum trailing stop price
  ;;   none - just need to round the value to 2 decimal places
  ;; [ok] round trailing stop price to 2 decimal places
  ;; [ok] test with a sine wave
  ;; [ok] dynamically change log levels
  ;; [ok] turn logging on/off per namespace
  ;; [ok] upstream scanner

  ;; track many (n) stocks
  ;;   track instrument symbol with stream
  ;;   track bid / ask with stream (https://interactivebrokers.github.io/tws-api/tick_types.html)


  ;; workbench
  ;;   -> joined-ticks (place orders)
  ;;   -> order-updates (notify order filled)
  ;;   -> order-filled (place opposite TRAIL sell)


  ;; mock(s) for ->account-cash-level + ->next-valid-order-id


  ;; only purchase more if
  ;;   we're gaining (over last 3 ticks)
  ;;   we have enough money


  ;; BUY if
  ;;   :up signal from lagging + leading (or more)
  ;;   standard deviation is at least 0.5
  ;;   within last 3 ticks
  ;;   we have enough money
  ;;   * buy up to $1000 or 50% of cash (whichever is less)


  ;; (in processing pipeline) bollinger-band signals should be fleshed out more
  ;;   also look at RSI divergence
  ;; fix tests
  ;; host on AWS
  ;; after some time - memory lag
  ;; ensure sell order completes
  ;; overlay (emit) orders on top of stream

  ;; Stream live + record, to troubleshoot errors
  ;;   Exception in thread "async-dispatch-1"
  ;;   java.lang.NullPointerException
  ;;    at clojure.lang.Numbers.ops(Numbers.java:1018)
  ;;    at clojure.lang.Numbers.lt(Numbers.java:226)
  ;;    at com.interrupt.edgar.core.signal.lagging$peak_inside_upper_PLUS_price_abouve_upper_QMARK_.invokeStatic(lagging.clj:161)
  ;;    at com.interrupt.edgar.core.signal.lagging$peak_inside_upper_PLUS_price_abouve_upper_QMARK_.invoke(lagging.clj:158)
  ;;    at com.interrupt.edgar.core.signal.lagging$analysis_overbought_oversold.invokeStatic(lagging.clj:181)
  ;;    at com.interrupt.edgar.core.signal.lagging$analysis_overbought_oversold.invoke(lagging.clj:168)
  ;;    at com.interrupt.edgar.core.signal.lagging$bollinger_band.invokeStatic(lagging.clj:295)
  ;;    at com.interrupt.edgar.core.signal.lagging$bollinger_band.invoke(lagging.clj:210)
  ;;    at clojure.core$partial$fn__5561.invoke(core.clj:2616)
  ;;    at clojure.core$map$fn__5583$fn__5584.invoke(core.clj:2734)
  ;;    at clojure.core.async.impl.channels$chan$fn__13854.invoke(channels.clj:300)
  ;;    at clojure.core.async.impl.channels.ManyToManyChannel.put_BANG_(channels.clj:143)
  ;;    at clojure.core.async$_GT__BANG__BANG_.invokeStatic(async.clj:142)
  ;;    at clojure.core.async$_GT__BANG__BANG_.invoke(async.clj:137)
  ;;    at clojure.core.async$pipeline_STAR_$process__19658.invoke(async.clj:491)
  ;;    at clojure.core.async$pipeline_STAR_$fn__19840$state_machine__19318__auto____19869$fn__19871.invoke(async.clj:508)
  ;;    at clojure.core.async$pipeline_STAR_$fn__19840$state_machine__19318__auto____19869.invoke(async.clj:508)
  ;;    at clojure.core.async.impl.ioc_macros$run_state_machine.invokeStatic(ioc_macros.clj:973)
  ;;    at clojure.core.async.impl.ioc_macros$run_state_machine.invoke(ioc_macros.clj:972)
  ;;    at clojure.core.async.impl.ioc_macros$run_state_machine_wrapped.invokeStatic(ioc_macros.clj:977)
  ;;    at clojure.core.async.impl.ioc_macros$run_state_machine_wrapped.invoke(ioc_macros.clj:975)
  ;;    at clojure.core.async.impl.ioc_macros$take_BANG_$fn__19336.invoke(ioc_macros.clj:986)
  ;;    at clojure.core.async.impl.channels.ManyToManyChannel$fn__13760$fn__13761.invoke(channels.clj:95)
  ;;    at clojure.lang.AFn.run(AFn.java:22)
  ;;    at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
  ;;    at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
  ;;    at java.lang.Thread.run(Thread.java:748)

  ;;   Exception in thread "async-dispatch-4"
  ;;   java.lang.NullPointerException
  ;;    at clojure.lang.Numbers.ops(Numbers.java:1018)
  ;;    at clojure.lang.Numbers.gt(Numbers.java:234)
  ;;    at com.interrupt.edgar.core.signal.lagging$valley_inside_lower_PLUS_price_below_lower_QMARK_.invokeStatic(lagging.clj:151)
  ;;    at com.interrupt.edgar.core.signal.lagging$valley_inside_lower_PLUS_price_below_lower_QMARK_.invoke(lagging.clj:145)
  ;;    at com.interrupt.edgar.core.signal.lagging$analysis_overbought_oversold.invokeStatic(lagging.clj:174)
  ;;    at com.interrupt.edgar.core.signal.lagging$analysis_overbought_oversold.invoke(lagging.clj:168)
  ;;    at com.interrupt.edgar.core.signal.lagging$bollinger_band.invokeStatic(lagging.clj:295)
  ;;    at com.interrupt.edgar.core.signal.lagging$bollinger_band.invoke(lagging.clj:210)
  ;;    at clojure.core$partial$fn__5561.invoke(core.clj:2616)
  ;;    at clojure.core$map$fn__5583$fn__5584.invoke(core.clj:2734)
  ;;    at clojure.core.async.impl.channels$chan$fn__13854.invoke(channels.clj:300)
  ;;    at clojure.core.async.impl.channels.ManyToManyChannel.put_BANG_(channels.clj:143)
  ;;    at clojure.core.async$_GT__BANG__BANG_.invokeStatic(async.clj:142)
  ;;    at clojure.core.async$_GT__BANG__BANG_.invoke(async.clj:137)
  ;;    at clojure.core.async$pipeline_STAR_$process__19658.invoke(async.clj:491)
  ;;    at clojure.core.async$pipeline_STAR_$fn__19840$state_machine__19318__auto____19869$fn__19871.invoke(async.clj:508)
  ;;    at clojure.core.async$pipeline_STAR_$fn__19840$state_machine__19318__auto____19869.invoke(async.clj:508)
  ;;    at clojure.core.async.impl.ioc_macros$run_state_machine.invokeStatic(ioc_macros.clj:973)
  ;;    at clojure.core.async.impl.ioc_macros$run_state_machine.invoke(ioc_macros.clj:972)
  ;;    at clojure.core.async.impl.ioc_macros$run_state_machine_wrapped.invokeStatic(ioc_macros.clj:977)
  ;;    at clojure.core.async.impl.ioc_macros$run_state_machine_wrapped.invoke(ioc_macros.clj:975)
  ;;    at clojure.core.async.impl.ioc_macros$take_BANG_$fn__19336.invoke(ioc_macros.clj:986)
  ;;    at clojure.core.async.impl.channels.ManyToManyChannel$fn__13760$fn__13761.invoke(channels.clj:95)
  ;;    at clojure.lang.AFn.run(AFn.java:22)
  ;;    at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
  ;;    at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
  ;;    at java.lang.Thread.run(Thread.java:748)


  ;; ? Error. Id: 21, Code: 105, Msg: Order being modified does not match original order
  ;; https://groups.io/g/twsapi/topic/fixed_how_to_modify_combo/5333246?p=,,,20,0,0,0::recentpostdate%2Fsticky,,,20,2,0,5333246
  ;; TWS thinks I'm using the same order ID
  )

(defn teardown-execution-engine [ee]
  (when-not (nil? ee)
    (close! ee)))
