(ns com.interrupt.ibgateway.component.processing-pipeline
  (:require [clojure.core.async :refer [chan to-chan sliding-buffer close! <! >!
                                        go-loop mult tap mix pipeline onto-chan] :as async]
            [clojure.tools.logging :refer [debug info warn error] :as log]
            [clojure.tools.trace :refer [trace]]
            [clojure.set :refer [subset?]]
            [clojure.string :as cs]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [mount.core :refer [defstate] :as mount]
            [net.cgrand.xforms :as x]
            [cljs-uuid.core :as uuid]
            [com.rpl.specter :refer :all]
            [clojure.math.combinatorics :as combo]
            [automata.refactor :refer [automata advance] :as a]

            [com.interrupt.ibgateway.component.ewrapper :as ew]
            [com.interrupt.ibgateway.component.common :refer [bind-channels->mult]]
            [com.interrupt.edgar.ib.market :as mkt]
            [com.interrupt.edgar.core.analysis.lagging :as alag]
            [com.interrupt.edgar.core.analysis.leading :as alead]
            [com.interrupt.edgar.core.analysis.confirming :as aconf]
            [com.interrupt.edgar.core.signal.lagging :as slag]
            [com.interrupt.edgar.core.signal.leading :as slead]
            [com.interrupt.edgar.core.signal.confirming :as sconf]
            [com.interrupt.edgar.core.utils :refer [not-nil? not-empty?]]
            [prpr.stream.cross :as stream.cross]
            [manifold.stream :as stream]))


(def moving-average-window 20)
(def moving-average-increment 1)
(def rt-volume-time-and-sales-type 48)
(def tick-string-type :tick-string)
(def tick-price-type :tick-price)
(def tick-size-type :tick-size)


(defn rtvolume-time-and-sales? [{:keys [type tick-type]}]
  (and (= tick-string-type type)
       (= rt-volume-time-and-sales-type tick-type)))

(defn tick-string? [{topic :topic}]
  (= tick-string-type topic))

(defn tick-price? [{topic :topic}]
  (= tick-price-type topic))

(defn tick-size? [{topic :topic}]
  (= tick-size-type topic))


(defmulti parse-tick-string (fn [{tick-type :tick-type}] tick-type))

;; Bid Exchange 32	For stock and options, identifies the exchange(s) posting the bid price.
;; {:topic :tick-string :ticker-id 0 :tick-type 32 :value W}
(defmethod parse-tick-string 32 [event]
  (clojure.set/rename-keys event {:topic :type}))

;; Ask Exchange 33	For stock and options, identifies the exchange(s) posting the ask price.
;; {:topic :tick-string :ticker-id 0 :tick-type 33 :value E}
(defmethod parse-tick-string 33 [event]
  (clojure.set/rename-keys event {:topic :type}))

;; Last Timestamp 45	Time of the last trade (in UNIX time).
;; {:topic :tick-string :ticker-id 0 :tick-type 45 :value 1534781337}
(defmethod parse-tick-string 45 [event]
  (clojure.set/rename-keys event {:topic :type}))

;; RT Volume (Time & Sales) 48	Last trade details (Including both "Last" and "Unreportable Last" trades).
;;
;; "Input format:
;;    {:type tickString, :tickerId 0, :tickType 48, :value 412.14;1;1367429375742;1196;410.39618025;true}
;;
;;    Value format:
;;    :value       ;0;1522337866199;67085;253.23364232;true
;;    :value 255.59;1;1522337865948;67077;253.23335428;true"
(defmethod parse-tick-string 48 [event]

  (let [tvalues (cs/split (:value event) #";")
        tkeys [:last-trade-price :last-trade-size :last-trade-time :total-volume :vwap :single-trade-flag]]

    (as-> (zipmap tkeys tvalues) rm
      (clojure.set/rename-keys rm {:topic :type})
      (assoc rm
             :ticker-id (:ticker-id event)
             :uuid (str (uuid/make-random))
             :type :tick-string
             :tick-type 48
             :last-trade-price (if (not (empty? (:last-trade-price rm)))
                                 (read-string (:last-trade-price rm)) 0)
             :last-trade-time (Long/parseLong (:last-trade-time rm))
             :last-trade-size (read-string (:last-trade-size rm))
             :total-volume (read-string (:total-volume rm))
             :vwap (read-string (:vwap rm))))))

(defn parse-tick-price
  "Input format:
  {:topic :tick-price :ticker-id 0 :field 1 :price 316.19 :can-auto-execute 1} - Bid Price
  {:topic :tick-price :ticker-id 0 :field 2 :price 314.7 :can-auto-execute 1} - Ask PRice
  {:topic :tick-price :ticker-id 0 :field 4 :price 314.42 :can-auto-execute 0} - Last Price
  {:topic :tick-price :ticker-id 0 :field 6 :price 311.4 :can-auto-execute 0} - High price for the day
  {:topic :tick-price :ticker-id 0 :field 7 :price 306.56 :can-auto-execute 0} - Low price for the day
  {:topic :tick-price :ticker-id 0 :field 9 :price 311.86 :can-auto-execute 0} - Close Price	(last available) for the previous day"
  [event]

  (letfn [(assoc-price [{:keys [field price] :as event}]
            (case field
              1 (assoc event :last-bid-price (if (string? price) (read-string price) price))
              2 (assoc event :last-ask-price (if (string? price) (read-string price) price))
              4 (assoc event :last-trade-price (if (string? price) (read-string price) price))

              6 (assoc event :last-high-price (if (string? price) (read-string price) price))
              7 (assoc event :last-low-price (if (string? price) (read-string price) price))
              9 (assoc event :last-close-price (if (string? price) (read-string price) price))
              (assoc event :unknown-type :noop)))]

    (-> (clojure.set/rename-keys event {:topic :type})
        (assoc :uuid (str (uuid/make-random)))
        assoc-price
        (dissoc :field :price))))

(defn parse-tick-size
  "Input format:
  {:topic :tick-size :ticker-id 0 :field 0 :size 1} - Bid Size
  {:topic :tick-size :ticker-id 0 :field 3 :size 1} - Ask Size
  {:topic :tick-size :ticker-id 0 :field 5 :size 1} - Last Size
  {:topic :tick-size :ticker-id 0 :field 8 :size 29924} - Trading volume for the day for the selected contract (US Stocks: multiplier 100)"
  [event]

  (letfn [(assoc-size [{:keys [field size] :as event}]
            (case field
              0 (assoc event :last-bid-size size)
              3 (assoc event :last-ask-size size)
              5 (assoc event :last-size size)
              8 (assoc event :last-volume size)
              (assoc event :unknown-type :noop)))]

    (-> (clojure.set/rename-keys event {:topic :type})
        (assoc :uuid (str (uuid/make-random)))
        assoc-size
        (dissoc :field :size))))

(defn empty-last-trade-price? [event]
  (or (-> event :last-trade-price nil?)
      (-> event :last-trade-price (<= 0))))


;; track bid / ask with stream (https://interactivebrokers.github.io/tws-api/tick_types.html)
;;   These are the only tickString types I see coming in
;;   48 45 33 32

;; Bid Size	0	IBApi.EWrapper.tickSize
;; Bid Price	1	IBApi.EWrapper.tickPrice
;; Ask Price	2	IBApi.EWrapper.tickPrice
;; Ask Size	3	IBApi.EWrapper.tickSize
;; Last Price	4	IBApi.EWrapper.tickPrice
;; Last Size	5	IBApi.EWrapper.tickSize
;; High	6 IBApi.EWrapper.tickPrice
;; Low	7	IBApi.EWrapper.tickPrice
;; Volume	8	IBApi.EWrapper.tickSize
;; Close Price	9, IBApi.EWrapper.tickPrice

(defn parse-tick [event]
  (cond
    (tick-string? event) (parse-tick-string event)
    (tick-price? event) (parse-tick-price event)
    (tick-size? event) (parse-tick-size event)))

(defn pipeline-stochastic-oscillator [n stochastic-oscillator-ch tick-list->stochastic-osc-ch]
  (let [stochastic-tick-window 14
        trigger-window 3
        trigger-line 3]
    (pipeline n stochastic-oscillator-ch (map (partial alead/stochastic-oscillator stochastic-tick-window trigger-window trigger-line)) tick-list->stochastic-osc-ch)))

(defn pipeline-relative-strength-index [n relative-strength-ch tick-list->relative-strength-ch]
  (let [relative-strength 14]
    (pipeline n relative-strength-ch (map (partial aconf/relative-strength-index relative-strength)) tick-list->relative-strength-ch)))

(defn has-all-lists?
  ([averages-map] (has-all-lists? averages-map #{:tick-list :sma-list :ema-list}))
  ([averages-map completion-set]
   (subset? completion-set (->> averages-map keys (into #{})))))

(defn update-state-and-complete [state uuid c]
  (swap! state dissoc uuid)
  (assoc c :joined true))

(defn pipeline-analysis-lagging [concurrency options
                                 sma-list-ch tick-list->sma-ch
                                 ema-list-ch sma-list->ema-ch
                                 bollinger-band-ch sma-list->bollinger-band-ch]

  (pipeline concurrency sma-list-ch (map (partial alag/simple-moving-average options)) tick-list->sma-ch)
  (pipeline concurrency ema-list-ch (map (partial alag/exponential-moving-average options moving-average-window)) sma-list->ema-ch)
  (pipeline concurrency bollinger-band-ch (map (partial alag/bollinger-band moving-average-window)) sma-list->bollinger-band-ch))

(defn pipeline-analysis-leading [concurrency options moving-average-window
                                 macd-ch sma-list->macd-ch
                                 stochastic-oscillator-ch tick-list->stochastic-osc-ch]

  (pipeline concurrency macd-ch (map (partial alead/macd options moving-average-window)) sma-list->macd-ch)
  (pipeline-stochastic-oscillator concurrency stochastic-oscillator-ch tick-list->stochastic-osc-ch))

(defn pipeline-analysis-confirming [concurrency on-balance-volume-ch tick-list->obv-ch
                                    relative-strength-ch tick-list->relative-strength-ch]

  (pipeline concurrency on-balance-volume-ch (map aconf/on-balance-volume) tick-list->obv-ch)
  (pipeline-relative-strength-index concurrency relative-strength-ch tick-list->relative-strength-ch))

(defn pipeline-signals-moving-average [concurrency connector-ch signal-moving-averages-ch]

  (let [moving-average-signal-window 2
        m (mult connector-ch)

        string-check (fn [k i]
                       (if (string? (k i))
                         (assoc i k (read-string (k i)))
                         i))

        strings->numbers (fn [item]
                           (->> item
                                (string-check :last-trade-price-exponential)
                                (string-check :last-trade-price-average)))

        remove-population-xf (map #(update-in % [:sma-list] dissoc :population))
        partition-xf (x/partition moving-average-signal-window moving-average-increment (x/into []))
        join-xf (map (fn [e]
                       (let [ks [:sma-list :ema-list]]
                         (->> e
                              (map #(merge ((first ks) %) ((second ks) %)))
                              (map strings->numbers)))))

        remove-population-ch (chan (sliding-buffer 40) remove-population-xf)
        partitioned-ch (chan (sliding-buffer 40) partition-xf)
        partitioned-joined-ch (chan (sliding-buffer 40) join-xf)

        mult-moving-averages (mult partitioned-joined-ch)
        tap->moving-averages (chan (sliding-buffer 40))]

    (tap m remove-population-ch)
    (tap mult-moving-averages tap->moving-averages)

    (pipeline concurrency partitioned-ch (map identity) remove-population-ch)
    (pipeline concurrency partitioned-joined-ch (map identity) partitioned-ch)
    (pipeline concurrency signal-moving-averages-ch (map slag/moving-averages) tap->moving-averages)))

(def partitioned-bollinger-band
  [{:last-trade-price 311.23 :last-trade-time 1534858597321 :uuid "c754a059-e9f2-4103-b06d-55d5c9be18fd" :variance 0.0903759999999971 :standard-deviation 0.30062601351180024 :upper-band 312.12325202702357 :lower-band 310.9207479729764 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :bollinger-band-squeeze})} {:last-trade-price 311.18 :last-trade-time 1534858598827 :uuid "ba5679c3-8460-48ff-8609-3b557b930270" :variance 0.09007899999999755 :standard-deviation 0.30013163778581814 :upper-band 312.0892632755716 :lower-band 310.8887367244283 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :bollinger-band-squeeze})} {:last-trade-price 311.22 :last-trade-time 1534858601302 :uuid "17eed297-e349-47a3-a908-2e3bbcb67e02" :variance 0.07911999999999683 :standard-deviation 0.281282775867981 :upper-band 312.012565551736 :lower-band 310.887434448264 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :bollinger-band-squeeze})} {:last-trade-price 311.21 :last-trade-time 1534858601805 :uuid "6df9a7ae-867d-4cc4-b7d1-ab263681e115" :variance 0.06471999999999778 :standard-deviation 0.2544012578585212 :upper-band 311.91880251571706 :lower-band 310.901197484283 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :bollinger-band-squeeze})} {:last-trade-price 311.09 :last-trade-time 1534858604733 :uuid "e1d6998f-0ceb-4667-8cfe-99b27ac44b9b" :variance 0.05282274999999962 :standard-deviation 0.22983200386369088 :upper-band 311.82616400772736 :lower-band 310.9068359922726 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :bollinger-band-squeeze})} {:last-trade-price 310.82 :last-trade-time 1534858612303 :uuid "00c18820-10ea-443f-8ced-c01fe71bbda9" :variance 0.05287474999999915 :standard-deviation 0.22994510214396643 :upper-band 311.77439020428795 :lower-band 310.85460979571207 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :bollinger-band-squeeze})} {:last-trade-price 309.45 :last-trade-time 1534858624980 :uuid "74d56511-e8a8-40bf-ba2a-fc01ac0d78a5" :variance 0.20896475000000173 :standard-deviation 0.45712662359569667 :upper-band 312.11875324719136 :lower-band 310.2902467528086 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :volume-spike} {:signal :either :why :bollinger-band-squeeze})} {:last-trade-price 309.42 :last-trade-time 1534858625984 :uuid "d06f4dda-b09c-4a72-8501-be9e18ac753b" :variance 0.3458310000000001 :standard-deviation 0.5880739749385278 :upper-band 312.2691479498771 :lower-band 309.91685205012294 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.43 :last-trade-time 1534858630267 :uuid "fa58f52b-ef50-4dce-ac0f-000c9d3e6ae0" :variance 0.46353600000000067 :standard-deviation 0.6808347817202061 :upper-band 312.3496695634404 :lower-band 309.6263304365596 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.69 :last-trade-time 1534858634784 :uuid "281fe40f-68da-4e9f-9f76-c47ec03ec60e" :variance 0.5147190000000001 :standard-deviation 0.7174391960298797 :upper-band 312.3238783920598 :lower-band 309.45412160794024 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.76 :last-trade-time 1534858635286 :uuid "a651c6cc-a42d-437e-90b3-a4cbbd200d8e" :variance 0.5580727499999998 :standard-deviation 0.7470426694640674 :upper-band 312.30058533892816 :lower-band 309.3124146610719 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.91 :last-trade-time 1534858636541 :uuid "fc29778c-9675-487a-87a8-e83ec45fc220" :variance 0.5737389999999966 :standard-deviation 0.7574556092603688 :upper-band 312.24591121852075 :lower-band 309.21608878147924 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 310.05 :last-trade-time 1534858641692 :uuid "7c2b1022-364a-484c-8482-32080b2156f1" :variance 0.5612559999999968 :standard-deviation 0.7491702076297461 :upper-band 312.15634041525954 :lower-band 309.1596595847405 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 310.02 :last-trade-time 1534858642947 :uuid "f76665d3-2ffd-4340-b9d9-816a7774a30b" :variance 0.545132749999999 :standard-deviation 0.7383310571823448 :upper-band 312.0631621143647 :lower-band 309.10983788563533 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.22 :last-trade-time 1534858659581 :uuid "9822515a-0732-4b76-84d2-3361c77b294a" :variance 0.5916627499999942 :standard-deviation 0.7691961713373217 :upper-band 312.0148923426746 :lower-band 308.93810765732536 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :volume-spike})} {:last-trade-price 309.48 :last-trade-time 1534858662090 :uuid "9858dda1-d994-440a-ad0e-807da1badf9b" :variance 0.5831487499999951 :standard-deviation 0.763641768108578 :upper-band 311.90478353621717 :lower-band 308.85021646378283 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.38 :last-trade-time 1534858663852 :uuid "8d4b38b2-c911-4303-9d47-64b6fdc145f8" :variance 0.5985889999999948 :standard-deviation 0.7736853365548522 :upper-band 311.8383706731097 :lower-band 308.7436293268903 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.16 :last-trade-time 1534858668113 :uuid "014ba3ff-1a6d-4d3d-bab4-7458bb900955" :variance 0.6281427499999935 :standard-deviation 0.7925545722535411 :upper-band 311.7836091445071 :lower-band 308.6133908554929 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.33 :last-trade-time 1534858685212 :uuid "2d9f6f58-c0c8-4c8f-b1c8-4cb52c47e9c0" :variance 0.619348999999997 :standard-deviation 0.7869872934171155 :upper-band 311.68497458683424 :lower-band 308.53702541316574 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :volume-spike})} {:last-trade-price 309.57 :last-trade-time 1534858690698 :uuid "eac2a6e5-abf5-4914-aacf-d17488283f7d" :variance 0.5715089999999963 :standard-deviation 0.7559821426462375 :upper-band 311.5429642852925 :lower-band 308.5190357147075 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.75 :last-trade-time 1534858692957 :uuid "f6d907a2-67f1-4e14-b3d4-20c4e9cc933d" :variance 0.49810099999999446 :standard-deviation 0.7057627079975213 :upper-band 311.368525415995 :lower-band 308.545474584005 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.83 :last-trade-time 1534858693207 :uuid "9c5e62b0-8e70-4c53-9e07-160f8f5e123f" :variance 0.41956474999999405 :standard-deviation 0.6477381801314432 :upper-band 311.1849763602629 :lower-band 308.5940236397371 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.8 :last-trade-time 1534858693464 :uuid "765fca35-bbb2-432b-b336-896214cf5aa5" :variance 0.32641274999999015 :standard-deviation 0.5713254326563716 :upper-band 310.96115086531273 :lower-band 308.6758491346872 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.76 :last-trade-time 1534858693960 :uuid "3a7e3535-f0f9-4193-b420-f855d13906a2" :variance 0.22451399999999327 :standard-deviation 0.47382908310908195 :upper-band 310.69365816621814 :lower-band 308.7983418337818 :signals '({:signal :up :why :percent-b-abouve-50})}])

(comment


  (->> (select [ALL :signals] partitioned-bollinger-band)
       (map (fn [a] (map (fn [b] (select-keys b [:why])) a))))

  (->> (select [ALL :signals] partitioned-bollinger-band)
       (map (fn [a] (map :why a))))

  (->> (select [ALL :signals] partitioned-bollinger-band)
       (map (fn [a] (map :why a)))
       (apply combo/cartesian-product))



  (def a (automata [(a/+ :fibonacci)
                    (a/+ :volume-spike)]))

  (def b (automata [(a/+ :fibonacci)
                    (a/+ :something-else)]))

  (def c (automata [(a/+ :bollinger-band-squeeze)
                    (a/+ :volume-spike)
                    (a/+ :percent-b-abouve-50)]))

  (def d (automata [(a/+ :bollinger-band-squeeze)
                    (a/+ :percent-b-abouve-50)
                    (a/+ :volume-spike)]))



  (defn play-state-machine [state-machine transitions]
    (reduce (fn [acc a]
              (advance acc a))
            state-machine
            transitions))

  (->> (select [ALL :signals] partitioned-bollinger-band)
       (map (fn [a] (map :why a)))
       (apply combo/cartesian-product)
       (map #(play-state-machine c %)))

  ;; A. See if any of the transition combinations (from the data) match our automata expression
  (->> (select [ALL :signals] partitioned-bollinger-band)
       (map (fn [a] (map :why a)))
       (apply combo/cartesian-product)
       (map #(play-state-machine c %))
       (remove #(:error %)))

  (->> (select [ALL :signals] partitioned-bollinger-band)
       (map (fn [a] (map :why a)))
       (apply combo/cartesian-product)
       (map #(play-state-machine d %))
       (remove #(:error %)))

  ;; B. We can do this for multiple automata
  (defn partitioned-bollinger-band->matching-automata [partitioned-bollinger-band state-machines]

    (let [signals-catesian-product (->> (select [ALL :signals] partitioned-bollinger-band)
                                        (map (fn [a] (map :why a)))
                                        (apply combo/cartesian-product))

          signals->valid-state-machines (fn [signals state-machine]
                                          (->> (map #(play-state-machine state-machine %) signals)
                                               (remove #(:error %))
                                               ((fn [results]
                                                  {:automata-match? (not-empty? results)
                                                   :count (count results)}))))]

      (map (partial signals->valid-state-machines signals-catesian-product) state-machines)))

  (defn matching-automata? [partitioned-bollinger-band automatas]

    (->> (partitioned-bollinger-band->matching-automata partitioned-bollinger-band automatas)
         (map :automata-match?)
         (some #{true})
         true?))

  (matching-automata? partitioned-bollinger-band [a b])
  (matching-automata? partitioned-bollinger-band [a c d]))


(def strategy-bollinger-band-squeeze-automata-a
  (automata [(a/+ :bollinger-band-squeeze)
             (a/+ :volume-spike)
             (a/+ :percent-b-abouve-50)]))

(def strategy-bollinger-band-squeeze-automata-b
  (automata [(a/+ :bollinger-band-squeeze)
             (a/+ :percent-b-abouve-50)
             (a/+ :volume-spike)]))

(defn play-state-machine [state-machine transitions]
  (reduce (fn [acc a]
            (advance acc a))
          state-machine
          transitions))

(defn partitioned-bollinger-band->matching-automata [partitioned-bollinger-band state-machines]

  (let [signals-catesian-product (->> (select [ALL :signals] partitioned-bollinger-band)
                                      (map (fn [a] (map :why a)))
                                      (apply combo/cartesian-product))

        signals->valid-state-machines (fn [signals state-machine]
                                        (->> (map #(play-state-machine state-machine %) signals)
                                             (remove #(:error %))
                                             ((fn [results]
                                                {:automata-match? (not-empty? results)
                                                 :count (count results)}))))]

    (map (partial signals->valid-state-machines signals-catesian-product) state-machines)))

(defn matching-automata? [partitioned-bollinger-band automatas]

  (->> (partitioned-bollinger-band->matching-automata partitioned-bollinger-band automatas)
       (map :automata-match?)
       (some #{true})
       true?))

(defn extract-signals-for-strategy-bollinger-bands-squeeze [partitioned-bollinger-band]

  ;; (info partitioned-bollinger-band)
  (-> partitioned-bollinger-band
      (matching-automata? [strategy-bollinger-band-squeeze-automata-a strategy-bollinger-band-squeeze-automata-b])
      ((fn [a]
         (if a
           (transform [LAST :signals]
                      #(conj % {:signal :up :why :strategy-bollinger-bands-squeeze})
                      partitioned-bollinger-band)
           partitioned-bollinger-band)))))

(defn pipeline-signals-bollinger-band [concurrency connector-ch signal-bollinger-band-ch]

  (let [bollinger-band-signal-window 24
        bollinger-band-increment 1

        partition-xf (x/partition bollinger-band-signal-window bollinger-band-increment (x/into []))
        matches-window-size? #(= bollinger-band-signal-window
                                 (count %))
        bollinger-band-exists-xf (filter #(->> (filter :bollinger-band %)
                                               matches-window-size?))
        join-xf (map (fn [e]
                       (let [ks [:tick-list :sma-list :bollinger-band]]
                         (->> e
                              ((juxt #(map (first ks) %)
                                     #(map (second ks) %)
                                     #(map (nth ks 2) %)))
                              (zipmap ks)))))

        partitioned-ch (chan (sliding-buffer 40) partition-xf)
        bollinger-band-exists-ch (chan (sliding-buffer 40) bollinger-band-exists-xf)
        partitioned-joined-ch (chan (sliding-buffer 40) join-xf)

        ach (chan (sliding-buffer 40) partition-xf)
        bch (chan (sliding-buffer 40))
        cch (chan (sliding-buffer 40))]

    (pipeline concurrency partitioned-ch (map identity) connector-ch)
    (pipeline concurrency bollinger-band-exists-ch (map identity) partitioned-ch)
    (pipeline concurrency partitioned-joined-ch (map identity) bollinger-band-exists-ch)
    (pipeline concurrency ach (map (partial slag/bollinger-band moving-average-window)) partitioned-joined-ch)

    ;; TODO partition
    ;; (pipeline concurrency bch (x/partition bollinger-band-signal-window bollinger-band-increment (x/into [])) ach)
    (pipeline concurrency bch (map identity) ach)

    ;; TODO A) extract-signals-for-strategy-bollinger-bands-squeeze
    ;; this is where the partitioned bollinger band is still reified
    ;; true (extract-signals-for-strategy-bollinger-bands-squeeze)
    ;; output is: signal-bollinger-band-ch -> put a partition on a binding channel
    (pipeline concurrency cch (map extract-signals-for-strategy-bollinger-bands-squeeze) bch)
    ;; (pipeline concurrency cch (map identity) bch)

    ;; TODO unpartition (take last item of each partitioned list)
    (pipeline concurrency signal-bollinger-band-ch (map last) cch)
    ;; (pipeline concurrency signal-bollinger-band-ch (map identity) cch)
    ))

(defn pipeline-signals-leading [concurrency moving-average-window
                                signal-macd-ch macd->macd-signal
                                signal-stochastic-oscillator-ch stochastic-oscillator->stochastic-oscillator-signal]

  (pipeline concurrency signal-macd-ch (map slead/macd) macd->macd-signal)

  (pipeline concurrency signal-stochastic-oscillator-ch (map slead/stochastic-oscillator)
            stochastic-oscillator->stochastic-oscillator-signal))

(def partition-xform (x/partition moving-average-window moving-average-increment (x/into [])))

(defn channel-analytics []
  {:source-list-ch (chan (sliding-buffer 40))
   :parsed-list-ch (chan (sliding-buffer 40))

   :tick-list-ch (chan (sliding-buffer 40) (x/partition moving-average-window moving-average-increment (x/into [])))
   :sma-list-ch (chan (sliding-buffer 40) (x/partition moving-average-window moving-average-increment (x/into [])))

   :ema-list-ch (chan (sliding-buffer 40))
   :bollinger-band-ch (chan (sliding-buffer 40))
   :macd-ch (chan (sliding-buffer 40))
   :stochastic-oscillator-ch (chan (sliding-buffer 40))
   :on-balance-volume-ch (chan (sliding-buffer 40))
   :relative-strength-ch (chan (sliding-buffer 40))})

(defn channel-analytics-mults []
  {:tick-list->sma-ch (chan (sliding-buffer 40))
   :tick-list->macd-ch (chan (sliding-buffer 40))

   :sma-list->ema-ch (chan (sliding-buffer 40))
   :sma-list->bollinger-band-ch (chan (sliding-buffer 40))
   :sma-list->macd-ch (chan (sliding-buffer 40))

   :tick-list->stochastic-osc-ch (chan (sliding-buffer 40))
   :tick-list->obv-ch (chan (sliding-buffer 40))
   :tick-list->relative-strength-ch (chan (sliding-buffer 40))})

(defn channel-join-mults []
  {:tick-list->JOIN (chan (sliding-buffer 40) (map last))
   :sma-list->JOIN (chan (sliding-buffer 40) (map last))
   :ema-list->JOIN (chan (sliding-buffer 40) (map last))
   :bollinger-band->JOIN (chan (sliding-buffer 40) (map last))
   :sma-list->JOIN->bollinger (chan (sliding-buffer 40) (map last))

   :macd->JOIN (chan (sliding-buffer 40) (map last))
   :stochastic-oscillator->JOIN (chan (sliding-buffer 40) (map last))
   :on-balance-volume->JOIN (chan (sliding-buffer 40) (map last))
   :relative-strength->JOIN (chan (sliding-buffer 40) (map last))})

(defn channel-signal-moving-averages []
  {:tick-list->moving-averages-signal (chan (sliding-buffer 40))
   :sma-list->moving-averages-signal (chan (sliding-buffer 40))
   :ema-list->moving-averages-signal (chan (sliding-buffer 40))
   :merged-averages (chan (sliding-buffer 40) (x/partition moving-average-window moving-average-increment (x/into [])))
   :signal-merged-averages (chan (sliding-buffer 40))
   :signal-moving-averages-ch (chan (sliding-buffer 40))})

(defn channel-signal-bollinger-band []

  (let [tick-list->bollinger-band-signal (chan (sliding-buffer 40))
        sma-list->bollinger-band-signal (chan (sliding-buffer 40))]
    {:tick-list->bollinger-band-signal tick-list->bollinger-band-signal
     :sma-list->bollinger-band-signal sma-list->bollinger-band-signal
     :signal-bollinger-band (chan (sliding-buffer 40) (filter :joined))
     :signal-bollinger-band-ch (chan (sliding-buffer 40))}))

(defn signal-join-mults []
  {:tick-list->SIGNAL (chan (sliding-buffer 40) (map last))
   :sma-list->SIGNAL (chan (sliding-buffer 40) (map last))
   :ema-list->SIGNAL (chan (sliding-buffer 40) (map last))
   :bollinger-band->SIGNAL (chan (sliding-buffer 40) (map last))
   :macd->SIGNAL (chan (sliding-buffer 40) (map last))
   :stochastic-oscillator->SIGNAL (chan (sliding-buffer 40) (map last))
   :on-balance-volume->SIGNAL (chan (sliding-buffer 40) (map last))
   :relative-strength->SIGNAL (chan (sliding-buffer 40) (map last))

   :signal-moving-averages->SIGNAL (chan (sliding-buffer 40))
   :signal-bollinger-band->SIGNAL (chan (sliding-buffer 40))
   :signal-macd->SIGNAL (chan (sliding-buffer 40))
   :signal-stochastic-oscillator->SIGNAL (chan (sliding-buffer 40))
   :signal-on-balance-volume->SIGNAL (chan (sliding-buffer 40))})

(defn channel->stream [& channels]
  (map #(->> %
             stream/->source
             (stream.cross/event-source->sorted-stream :last-trade-time))
       channels))

(defn join-analytics->moving-averages [sma-list->JOIN ema-list->JOIN]

  (let [[sma-list->CROSS ema-list->CROSS] (channel->stream sma-list->JOIN ema-list->JOIN)
        moving-averages-connector-ch (chan (sliding-buffer 40))
        result (stream.cross/set-streams-union {:default-key-fn :last-trade-time
                                                :skey-streams {:sma-list sma-list->CROSS
                                                               :ema-list ema-list->CROSS}})]

    (stream/connect @result moving-averages-connector-ch)
    moving-averages-connector-ch))

(defn join-analytics->bollinger-band [tick-list->JOIN bollinger-band->JOIN sma-list->JOIN->bollinger]

  (let [[tick-list->CROSS bollinger-band->CROSS sma-list->CROSS]
        (channel->stream tick-list->JOIN bollinger-band->JOIN sma-list->JOIN->bollinger)

        bollinger-band-connector-ch (chan (sliding-buffer 40))
        result (stream.cross/set-streams-union {:default-key-fn :last-trade-time
                                                :skey-streams {:tick-list tick-list->CROSS
                                                               :sma-list sma-list->CROSS
                                                               :bollinger-band bollinger-band->CROSS}})]

    (stream/connect @result bollinger-band-connector-ch)
    bollinger-band-connector-ch))

(defn join-analytics [output-ch tick-list->SIGNAL sma-list->SIGNAL ema-list->SIGNAL bollinger-band->SIGNAL
                      macd->SIGNAL stochastic-oscillator->SIGNAL
                      on-balance-volume->SIGNAL relative-strength->SIGNAL

                      signal-moving-averages->SIGNAL
                      signal-bollinger-band->SIGNAL
                      signal-macd->SIGNAL
                      signal-stochastic-oscillator->SIGNAL
                      signal-on-balance-volume->SIGNAL]

  (let [[tick-list->CROSS sma-list->CROSS ema-list->CROSS bollinger-band->CROSS
         macd->CROSS stochastic-oscillator->CROSS
         on-balance-volume->CROSS relative-strength->CROSS

         signal-moving-averages->CROSS
         signal-bollinger-band->CROSS
         signal-macd->CROSS
         signal-stochastic-oscillator->CROSS
         signal-on-balance-volume->CROSS]

        (channel->stream tick-list->SIGNAL sma-list->SIGNAL ema-list->SIGNAL bollinger-band->SIGNAL
                         macd->SIGNAL stochastic-oscillator->SIGNAL
                         on-balance-volume->SIGNAL relative-strength->SIGNAL

                         signal-moving-averages->SIGNAL
                         signal-bollinger-band->SIGNAL
                         signal-macd->SIGNAL
                         signal-stochastic-oscillator->SIGNAL
                         signal-on-balance-volume->SIGNAL)

        result (stream.cross/set-streams-union {:default-key-fn :last-trade-time
                                                :skey-streams {:tick-list tick-list->CROSS

                                                               ;; lagging
                                                               :sma-list sma-list->CROSS
                                                               :ema-list ema-list->CROSS
                                                               :bollinger-band bollinger-band->CROSS

                                                               ;; leading
                                                               :macd macd->CROSS
                                                               :stochastic-oscillator stochastic-oscillator->CROSS

                                                               ;; confirming
                                                               :on-balance-volume on-balance-volume->CROSS
                                                               :relative-strength relative-strength->CROSS

                                                               ;; signals
                                                               :signal-moving-averages signal-moving-averages->CROSS
                                                               :signal-bollinger-band signal-bollinger-band->CROSS
                                                               :signal-macd signal-macd->CROSS
                                                               :signal-stochastic-oscillator signal-stochastic-oscillator->CROSS
                                                               :signal-on-balance-volume signal-on-balance-volume->CROSS}})]

    (stream/connect @result output-ch)
    output-ch))

(def parse-xform (comp (map #(assoc % :timestamp (-> (t/now) c/to-long)))
                    (map parse-tick)))
(def filter-xform (comp (remove empty-last-trade-price?)
                     (filter rtvolume-time-and-sales?)))

(defn setup-publisher-channel [source-ch output-ch stock-name concurrency ticker-id-filter]

  (let [options {:stock-match {:symbol stock-name :ticker-id-filter ticker-id-filter}}


        ;; Channels Analytics
        {:keys [source-list-ch parsed-list-ch tick-list-ch sma-list-ch ema-list-ch
                bollinger-band-ch macd-ch stochastic-oscillator-ch
                on-balance-volume-ch relative-strength-ch]}
        (channel-analytics)


        ;; Channels Analytics Mults
        {:keys [tick-list->sma-ch tick-list->macd-ch
                sma-list->ema-ch sma-list->bollinger-band-ch sma-list->macd-ch
                tick-list->stochastic-osc-ch tick-list->obv-ch tick-list->relative-strength-ch]}
        (channel-analytics-mults)


        ;; Channels Signal: Moving Averages
        {:keys [tick-list->moving-averages-signal sma-list->moving-averages-signal ema-list->moving-averages-signal
                merged-averages signal-merged-averages signal-moving-averages-ch]}
        (channel-signal-moving-averages)


        ;; Channel JOIN Mults
        {:keys [sma-list->JOIN ema-list->JOIN
                tick-list->JOIN bollinger-band->JOIN sma-list->JOIN->bollinger
                ;; macd->JOIN stochastic-oscillator->JOIN
                ;; on-balance-volume->JOIN relative-strength->JOIN
                ]}
        (channel-join-mults)


        ;; Signal Bollinger Band
        {:keys [tick-list->bollinger-band-signal sma-list->bollinger-band-signal
                signal-bollinger-band signal-bollinger-band-ch]}
        (channel-signal-bollinger-band)


        macd->macd-signal (chan (sliding-buffer 40))
        stochastic-oscillator->stochastic-oscillator-signal (chan (sliding-buffer 40))
        on-balance-volume->on-balance-volume-ch (chan (sliding-buffer 40))
        signal-macd-ch (chan (sliding-buffer 40))
        signal-stochastic-oscillator-ch (chan (sliding-buffer 40))
        signal-on-balance-volume-ch (chan (sliding-buffer 40))

        lagging-signals-moving-averages-ch (join-analytics->moving-averages sma-list->JOIN ema-list->JOIN)
        lagging-signals-bollinger-band-connector-ch (join-analytics->bollinger-band
                                                      tick-list->JOIN
                                                      bollinger-band->JOIN
                                                      sma-list->JOIN->bollinger)


        ;; Signal JOIN Mults
        {:keys [sma-list->SIGNAL ema-list->SIGNAL
                tick-list->SIGNAL bollinger-band->SIGNAL
                macd->SIGNAL stochastic-oscillator->SIGNAL
                on-balance-volume->SIGNAL relative-strength->SIGNAL

                signal-moving-averages->SIGNAL
                signal-bollinger-band->SIGNAL
                signal-macd->SIGNAL
                signal-stochastic-oscillator->SIGNAL
                signal-on-balance-volume->SIGNAL]}
        (signal-join-mults)]

    (doseq [source+mults [[source-list-ch parsed-list-ch]
                          [tick-list-ch tick-list->sma-ch tick-list->macd-ch
                           tick-list->stochastic-osc-ch tick-list->obv-ch
                           tick-list->relative-strength-ch tick-list->JOIN tick-list->SIGNAL]
                          [sma-list-ch sma-list->ema-ch sma-list->bollinger-band-ch
                           sma-list->macd-ch sma-list->JOIN sma-list->SIGNAL sma-list->JOIN->bollinger]
                          [ema-list-ch ema-list->moving-averages-signal
                           ema-list->JOIN ema-list->SIGNAL]
                          [bollinger-band-ch bollinger-band->JOIN bollinger-band->SIGNAL]
                          [macd-ch macd->macd-signal macd->SIGNAL]
                          [stochastic-oscillator-ch stochastic-oscillator->stochastic-oscillator-signal
                           stochastic-oscillator->SIGNAL]
                          [on-balance-volume-ch on-balance-volume->on-balance-volume-ch
                           on-balance-volume->SIGNAL]
                          [relative-strength-ch relative-strength->SIGNAL]

                          [signal-moving-averages-ch signal-moving-averages->SIGNAL]
                          [signal-bollinger-band-ch signal-bollinger-band->SIGNAL]
                          [signal-macd-ch signal-macd->SIGNAL]
                          [signal-stochastic-oscillator-ch signal-stochastic-oscillator->SIGNAL]
                          [signal-on-balance-volume-ch signal-on-balance-volume->SIGNAL]]]

      (apply bind-channels->mult source+mults))

    (join-analytics output-ch tick-list->SIGNAL sma-list->SIGNAL ema-list->SIGNAL bollinger-band->SIGNAL
                    macd->SIGNAL stochastic-oscillator->SIGNAL
                    on-balance-volume->SIGNAL relative-strength->SIGNAL

                    signal-moving-averages->SIGNAL
                    signal-bollinger-band->SIGNAL
                    signal-macd->SIGNAL
                    signal-stochastic-oscillator->SIGNAL
                    signal-on-balance-volume->SIGNAL)


    ;; TICK LIST
    (pipeline concurrency source-list-ch parse-xform source-ch)
    (pipeline concurrency tick-list-ch filter-xform source-list-ch)


    ;; ANALYSIS
    (pipeline-analysis-lagging concurrency options
                               sma-list-ch tick-list->sma-ch
                               ema-list-ch sma-list->ema-ch
                               bollinger-band-ch sma-list->bollinger-band-ch)

    (pipeline-analysis-leading concurrency options moving-average-window macd-ch sma-list->macd-ch
                               stochastic-oscillator-ch tick-list->stochastic-osc-ch)

    (pipeline-analysis-confirming concurrency on-balance-volume-ch tick-list->obv-ch
                                  relative-strength-ch tick-list->relative-strength-ch)


    ;; SIGNALS
    (pipeline-signals-moving-average concurrency lagging-signals-moving-averages-ch
                                     signal-moving-averages-ch)

    ;; TODO implement Trendlines (a Simple Moving Average?)

    (pipeline-signals-bollinger-band concurrency lagging-signals-bollinger-band-connector-ch
                                     signal-bollinger-band-ch)

    (pipeline-signals-leading concurrency moving-average-window
                              signal-macd-ch macd->macd-signal
                              signal-stochastic-oscillator-ch stochastic-oscillator->stochastic-oscillator-signal)

    ;; TODO on balance volume signals are all down (not up)
    (pipeline concurrency signal-on-balance-volume-ch (map sconf/on-balance-volume)
              on-balance-volume->on-balance-volume-ch)

    ;; TODO put aggregate signals here
    '[:exponential-ma-has-crossed-below
      :macd-troughs
      (or :rsi :bollinger-band-squeeze)]

    '[:exponential-ma-has-crossed-abouve
      :macd-peaks
      (or :rsi :bollinger-band-squeeze)]


    #_(go-loop [c 0 r (<! tick-list-ch)]
        (info "count: " c " / tick-list-ch INPUT " r)
        (when r
          (recur (inc c) (<! tick-list-ch))))

    #_(go-loop [c 0 r (<! lagging-signals-moving-averages-ch)]
        (info "count: " c " / lagging-signals-moving-averages INPUT " r)
        (when r
          (recur (inc c) (<! lagging-signals-moving-averages-ch))))

    #_(go-loop [c 0 r (<! signal-moving-averages-ch)]
        (info "count: " c " / MA signals: " r)
        (when r
          (recur (inc c) (<! signal-moving-averages-ch))))

    #_(go-loop [c 0 r (<! signal-bollinger-band-ch)]
        (info "count: " c " / BB signals: " r)
        (when r
          (recur (inc c) (<! signal-bollinger-band-ch))))

    #_(go-loop [c 0 r (<! signal-macd-ch)]
        (info "count: " c " / MACD signals: " r)
        (when r
          (recur (inc c) (<! signal-macd-ch))))

    #_(go-loop [c 0 r (<! signal-stochastic-oscillator-ch)]
        (info "count: " c " / SO signals: " r)
        (when r
          (recur (inc c) (<! signal-stochastic-oscillator-ch))))

    #_(go-loop [c 0 r (<! signal-on-balance-volume-ch)]
        (info "count: " c " / OBV signal: " r)
        (when r
          (recur (inc c) (<! signal-on-balance-volume-ch))))

    #_(go-loop [c 0 r (<! output-ch)]
        (info "count: " c " / r: " r)
        (when r
          (recur (inc c) (<! output-ch))))

    {:joined-channel output-ch
     :input-channel parsed-list-ch}))

(defn teardown-publisher-channel [joined-channel-map]
  (doseq [v (vals joined-channel-map)]
    (close! v)))
