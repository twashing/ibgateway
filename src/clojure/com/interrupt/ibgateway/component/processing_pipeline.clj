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
            [com.interrupt.ibgateway.component.common :refer [bind-channels->mult] :as cm]
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
                                 ema-list-ch bollinger-band-ch]

  (pipeline concurrency sma-list-ch (map (partial alag/simple-moving-average options)) tick-list->sma-ch)
  (pipeline concurrency ema-list-ch (map (partial alag/exponential-moving-average options cm/sliding-buffer-window)) sma-list-ch)
  (pipeline concurrency bollinger-band-ch (map (partial alag/bollinger-band cm/sliding-buffer-window)) ema-list-ch))

(defn pipeline-analysis-leading [concurrency options moving-average-window
                                 macd-ch sma-list->macd-ch]

  (pipeline concurrency macd-ch (map (partial alead/macd options moving-average-window)) sma-list->macd-ch))

(defn pipeline-analysis-confirming [concurrency on-balance-volume-ch tick-list->obv-ch
                                    relative-strength-ch tick-list->relative-strength-ch]

  (pipeline concurrency on-balance-volume-ch (map aconf/on-balance-volume) tick-list->obv-ch)
  (pipeline-relative-strength-index concurrency relative-strength-ch tick-list->relative-strength-ch))

(defn pipeline-signals-moving-average [concurrency connector-ch signal-moving-averages-ch]

  (let [moving-average-signal-window 2
        string-check (fn [k i]
                       (if (string? (k i))
                         (assoc i k (read-string (k i)))
                         i))

        strings->numbers (fn [item]
                           (->> item
                                (string-check :last-trade-price-exponential)
                                (string-check :last-trade-price-average)))

        partition-xf (x/partition moving-average-signal-window cm/moving-average-increment (x/into []))
        join-xf (map #(map strings->numbers %))

        ;; remove-population-ch (chan (sliding-buffer sliding-buffer-window) remove-population-xf)
        ;; partitioned-ch (chan (sliding-buffer sliding-buffer-window) partition-xf)
        partitioned-joined-ch (chan (sliding-buffer cm/sliding-buffer-window) join-xf)]

    ;; (pipeline concurrency partitioned-ch (map identity) connector-ch)
    (pipeline concurrency partitioned-joined-ch (map identity) connector-ch)
    (pipeline concurrency signal-moving-averages-ch (map slag/moving-averages) partitioned-joined-ch)))


;; #_(def partitioned-bollinger-band
;;   [{:last-trade-price 311.23 :last-trade-time 1534858597321 :uuid "c754a059-e9f2-4103-b06d-55d5c9be18fd" :variance 0.0903759999999971 :standard-deviation 0.30062601351180024 :upper-band 312.12325202702357 :lower-band 310.9207479729764 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :bollinger-band-squeeze})} {:last-trade-price 311.18 :last-trade-time 1534858598827 :uuid "ba5679c3-8460-48ff-8609-3b557b930270" :variance 0.09007899999999755 :standard-deviation 0.30013163778581814 :upper-band 312.0892632755716 :lower-band 310.8887367244283 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :bollinger-band-squeeze})} {:last-trade-price 311.22 :last-trade-time 1534858601302 :uuid "17eed297-e349-47a3-a908-2e3bbcb67e02" :variance 0.07911999999999683 :standard-deviation 0.281282775867981 :upper-band 312.012565551736 :lower-band 310.887434448264 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :bollinger-band-squeeze})} {:last-trade-price 311.21 :last-trade-time 1534858601805 :uuid "6df9a7ae-867d-4cc4-b7d1-ab263681e115" :variance 0.06471999999999778 :standard-deviation 0.2544012578585212 :upper-band 311.91880251571706 :lower-band 310.901197484283 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :bollinger-band-squeeze})} {:last-trade-price 311.09 :last-trade-time 1534858604733 :uuid "e1d6998f-0ceb-4667-8cfe-99b27ac44b9b" :variance 0.05282274999999962 :standard-deviation 0.22983200386369088 :upper-band 311.82616400772736 :lower-band 310.9068359922726 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :bollinger-band-squeeze})} {:last-trade-price 310.82 :last-trade-time 1534858612303 :uuid "00c18820-10ea-443f-8ced-c01fe71bbda9" :variance 0.05287474999999915 :standard-deviation 0.22994510214396643 :upper-band 311.77439020428795 :lower-band 310.85460979571207 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :bollinger-band-squeeze})} {:last-trade-price 309.45 :last-trade-time 1534858624980 :uuid "74d56511-e8a8-40bf-ba2a-fc01ac0d78a5" :variance 0.20896475000000173 :standard-deviation 0.45712662359569667 :upper-band 312.11875324719136 :lower-band 310.2902467528086 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :volume-spike} {:signal :either :why :bollinger-band-squeeze})} {:last-trade-price 309.42 :last-trade-time 1534858625984 :uuid "d06f4dda-b09c-4a72-8501-be9e18ac753b" :variance 0.3458310000000001 :standard-deviation 0.5880739749385278 :upper-band 312.2691479498771 :lower-band 309.91685205012294 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.43 :last-trade-time 1534858630267 :uuid "fa58f52b-ef50-4dce-ac0f-000c9d3e6ae0" :variance 0.46353600000000067 :standard-deviation 0.6808347817202061 :upper-band 312.3496695634404 :lower-band 309.6263304365596 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.69 :last-trade-time 1534858634784 :uuid "281fe40f-68da-4e9f-9f76-c47ec03ec60e" :variance 0.5147190000000001 :standard-deviation 0.7174391960298797 :upper-band 312.3238783920598 :lower-band 309.45412160794024 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.76 :last-trade-time 1534858635286 :uuid "a651c6cc-a42d-437e-90b3-a4cbbd200d8e" :variance 0.5580727499999998 :standard-deviation 0.7470426694640674 :upper-band 312.30058533892816 :lower-band 309.3124146610719 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.91 :last-trade-time 1534858636541 :uuid "fc29778c-9675-487a-87a8-e83ec45fc220" :variance 0.5737389999999966 :standard-deviation 0.7574556092603688 :upper-band 312.24591121852075 :lower-band 309.21608878147924 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 310.05 :last-trade-time 1534858641692 :uuid "7c2b1022-364a-484c-8482-32080b2156f1" :variance 0.5612559999999968 :standard-deviation 0.7491702076297461 :upper-band 312.15634041525954 :lower-band 309.1596595847405 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 310.02 :last-trade-time 1534858642947 :uuid "f76665d3-2ffd-4340-b9d9-816a7774a30b" :variance 0.545132749999999 :standard-deviation 0.7383310571823448 :upper-band 312.0631621143647 :lower-band 309.10983788563533 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.22 :last-trade-time 1534858659581 :uuid "9822515a-0732-4b76-84d2-3361c77b294a" :variance 0.5916627499999942 :standard-deviation 0.7691961713373217 :upper-band 312.0148923426746 :lower-band 308.93810765732536 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :volume-spike})} {:last-trade-price 309.48 :last-trade-time 1534858662090 :uuid "9858dda1-d994-440a-ad0e-807da1badf9b" :variance 0.5831487499999951 :standard-deviation 0.763641768108578 :upper-band 311.90478353621717 :lower-band 308.85021646378283 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.38 :last-trade-time 1534858663852 :uuid "8d4b38b2-c911-4303-9d47-64b6fdc145f8" :variance 0.5985889999999948 :standard-deviation 0.7736853365548522 :upper-band 311.8383706731097 :lower-band 308.7436293268903 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.16 :last-trade-time 1534858668113 :uuid "014ba3ff-1a6d-4d3d-bab4-7458bb900955" :variance 0.6281427499999935 :standard-deviation 0.7925545722535411 :upper-band 311.7836091445071 :lower-band 308.6133908554929 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.33 :last-trade-time 1534858685212 :uuid "2d9f6f58-c0c8-4c8f-b1c8-4cb52c47e9c0" :variance 0.619348999999997 :standard-deviation 0.7869872934171155 :upper-band 311.68497458683424 :lower-band 308.53702541316574 :signals '({:signal :down :why :percent-b-below-50} {:signal :either :why :volume-spike})} {:last-trade-price 309.57 :last-trade-time 1534858690698 :uuid "eac2a6e5-abf5-4914-aacf-d17488283f7d" :variance 0.5715089999999963 :standard-deviation 0.7559821426462375 :upper-band 311.5429642852925 :lower-band 308.5190357147075 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.75 :last-trade-time 1534858692957 :uuid "f6d907a2-67f1-4e14-b3d4-20c4e9cc933d" :variance 0.49810099999999446 :standard-deviation 0.7057627079975213 :upper-band 311.368525415995 :lower-band 308.545474584005 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.83 :last-trade-time 1534858693207 :uuid "9c5e62b0-8e70-4c53-9e07-160f8f5e123f" :variance 0.41956474999999405 :standard-deviation 0.6477381801314432 :upper-band 311.1849763602629 :lower-band 308.5940236397371 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.8 :last-trade-time 1534858693464 :uuid "765fca35-bbb2-432b-b336-896214cf5aa5" :variance 0.32641274999999015 :standard-deviation 0.5713254326563716 :upper-band 310.96115086531273 :lower-band 308.6758491346872 :signals '({:signal :down :why :percent-b-below-50})} {:last-trade-price 309.76 :last-trade-time 1534858693960 :uuid "3a7e3535-f0f9-4193-b420-f855d13906a2" :variance 0.22451399999999327 :standard-deviation 0.47382908310908195 :upper-band 310.69365816621814 :lower-band 308.7983418337818 :signals '({:signal :up :why :percent-b-abouve-50})}])
;;
;; (comment
;;
;;
;;   (->> (select [ALL :signals] partitioned-bollinger-band)
;;        (map (fn [a] (map (fn [b] (select-keys b [:why])) a))))
;;
;;   (->> (select [ALL :signals] partitioned-bollinger-band)
;;        (map (fn [a] (map :why a))))
;;
;;   (->> (select [ALL :signals] partitioned-bollinger-band)
;;        (map (fn [a] (map :why a)))
;;        (apply combo/cartesian-product))
;;
;;
;;
;;   (def a (automata [(a/+ :fibonacci)
;;                     (a/+ :volume-spike)]))
;;
;;   (def b (automata [(a/+ :fibonacci)
;;                     (a/+ :something-else)]))
;;
;;   (def c (automata [(a/+ :bollinger-band-squeeze)
;;                     (a/+ :volume-spike)
;;                     (a/+ :percent-b-abouve-50)]))
;;
;;   (def d (automata [(a/+ :bollinger-band-squeeze)
;;                     (a/+ :percent-b-abouve-50)
;;                     (a/+ :volume-spike)]))
;;
;;
;;
;;   (defn play-state-machine [state-machine transitions]
;;     (reduce (fn [acc a]
;;               (advance acc a))
;;             state-machine
;;             transitions))
;;
;;   (->> (select [ALL :signals] partitioned-bollinger-band)
;;        (map (fn [a] (map :why a)))
;;        (apply combo/cartesian-product)
;;        (map #(play-state-machine c %)))
;;
;;   ;; A. See if any of the transition combinations (from the data) match our automata expression
;;   (->> (select [ALL :signals] partitioned-bollinger-band)
;;        (map (fn [a] (map :why a)))
;;        (apply combo/cartesian-product)
;;        (map #(play-state-machine c %))
;;        (remove #(:error %)))
;;
;;   (->> (select [ALL :signals] partitioned-bollinger-band)
;;        (map (fn [a] (map :why a)))
;;        (apply combo/cartesian-product)
;;        (map #(play-state-machine d %))
;;        (remove #(:error %)))
;;
;;   ;; B. We can do this for multiple automata
;;   (defn partitioned-bollinger-band->matching-automata [partitioned-bollinger-band state-machines]
;;
;;     (let [signals-catesian-product (->> (select [ALL :signals] partitioned-bollinger-band)
;;                                         (map (fn [a] (map :why a)))
;;                                         (apply combo/cartesian-product))
;;
;;           signals->valid-state-machines (fn [signals state-machine]
;;                                           (->> (map #(play-state-machine state-machine %) signals)
;;                                                (remove #(:error %))
;;                                                ((fn [results]
;;                                                   {:automata-match? (not-empty? results)
;;                                                    :count (count results)}))))]
;;
;;       (map (partial signals->valid-state-machines signals-catesian-product) state-machines)))
;;
;;   (defn matching-automata? [partitioned-bollinger-band automatas]
;;
;;     (->> (partitioned-bollinger-band->matching-automata partitioned-bollinger-band automatas)
;;          (map :automata-match?)
;;          (some #{true})
;;          true?))
;;
;;   (matching-automata? partitioned-bollinger-band [a b])
;;   (matching-automata? partitioned-bollinger-band [a c d]))


(def strategy-bollinger-band-squeeze-automata-a
  (automata [(a/+ :moving-average-crossover)]))

(def strategy-bollinger-band-squeeze-automata-b
  (automata [(a/+ :bollinger-band-squeeze)
             (a/+ :moving-average-crossover)
             (a/+ :volume-spike)]))

(defn play-state-machine [state-machine transitions]
  (reduce (fn [acc a]
            (advance acc a))
          state-machine
          transitions))

;; (->> (select [ALL :signals] (take 15 one))
;;      (map #(map :why %))
;;      (apply concat))
;;
;;
;; (->> (select [ALL :signals] (take 15 one))
;;      (map #(map :why))
;;      (apply concat)
;;      (play-state-machine (automata [(a/+ :bollinger-band-squeeze)
;;                                     (a/+ :moving-average-crossover)
;;                                     (a/+ :volume-spike)])))

;; (play-state-machine (automata [(a/+ :bollinger-band-squeeze)
;;                                (a/+ :moving-average-crossover)
;;                                (a/+ :volume-spike)])
;;                     '(:not-down-market :bollinger-band-squeeze :moving-average-crossover :not-down-market :moving-average-crossover :not-down-market :moving-average-crossover :not-down-market :moving-average-crossover :not-down-market :moving-average-crossover :not-down-market :moving-average-crossover :not-down-market :moving-average-crossover :not-down-market :moving-average-crossover :not-down-market :moving-average-crossover :not-down-market :percent-b-below-50 :not-down-market :percent-b-below-50 :not-down-market :percent-b-below-50 :volume-spike))
;;
;; (play-state-machine (automata [(a/+ :bollinger-band-squeeze)
;;                                (a/+ :moving-average-crossover)
;;                                (a/+ :volume-spike)])
;;                     '(:not-down-market :bollinger-band-squeeze :moving-average-crossover :volume-spike))


;; (->> (select [ALL :signals] (take 15 one))
;;      (map #(map :why %))
;;      (apply combo/cartesian-product))



(defn partitioned-bollinger-band->matching-automata [partitioned-bollinger-band state-machines]

  (let [signals-catesian-product (->> (select [ALL :signals] partitioned-bollinger-band)
                                      (map #(map :why %))
                                      (apply combo/cartesian-product))

        signals->valid-state-machines (fn [signals state-machine]
                                        (->> (map #(play-state-machine state-machine %) signals)
                                             (remove #(:error %))
                                             ((fn [results]
                                                {:automata-match? (not-empty? results)
                                                 :count (count results)}))))]

    (map (partial signals->valid-state-machines signals-catesian-product) state-machines)))

(def one
  [{:last-trade-price 300.75, :last-trade-time 1534782908898, :uuid "6a2f6d32-c3d5-4243-a19e-a9ad0966e2a2", :variance 0.09823400000000224, :standard-deviation 0.3134230368048945, :upper-band 300.7628460736098, :lower-band 299.50915392639024, :signals '({:signal :up, :why :bollinger-band-squeeze})}
{:last-trade-price 300.69, :last-trade-time 1534782914666, :uuid "993a170a-88b5-4b24-a0db-815c21ddd11e", :variance 0.10976475000000244, :standard-deviation 0.33130763649515, :upper-band 300.83711527299033, :lower-band 299.5118847270097, :signals '({:signal :up, :why :not-down-market} {:signal :up, :why :moving-average-crossover})}
{:last-trade-price 300.54, :last-trade-time 1534782917391, :uuid "f4c9b1da-fa28-45b9-9cb0-31bfc1d8e07e", :variance 0.11134100000000262, :standard-deviation 0.3336779884859093, :upper-band 300.8743559769718, :lower-band 299.5396440230282, :signals '({:signal :up, :why :not-down-market} {:signal :up, :why :moving-average-crossover})}
{:last-trade-price 300.5, :last-trade-time 1534782941488, :uuid "43019160-c6d3-41c9-80cd-b1a44e77e607", :variance 0.11002100000000177, :standard-deviation 0.33169413621588456, :upper-band 300.90038827243177, :lower-band 299.5736117275683, :signals '({:signal :up, :why :not-down-market} {:signal :up, :why :moving-average-crossover})}
{:last-trade-price 300.44, :last-trade-time 1534782946463, :uuid "122f80ac-26e5-45ca-b8ff-ce4912d77362", :variance 0.07782100000000117, :standard-deviation 0.27896415540352343, :upper-band 300.844928310807, :lower-band 299.72907168919295, :signals '({:signal :up, :why :not-down-market} {:signal :up, :why :moving-average-crossover})}
{:last-trade-price 300.87, :last-trade-time 1534782956654, :uuid "97f4abb5-5c3e-49ef-983f-db9995dcf834", :variance 0.07726100000000104, :standard-deviation 0.27795863001533344, :upper-band 300.89891726003066, :lower-band 299.7870827399694, :signals '({:signal :up, :why :not-down-market} {:signal :up, :why :moving-average-crossover})}
{:last-trade-price 300.94, :last-trade-time 1534782957157, :uuid "0b15268c-e862-41ba-9f73-4d60b5b1ad4c", :variance 0.08534599999999969, :standard-deviation 0.29214037721615904, :upper-band 300.9762807544323, :lower-band 299.8077192455677, :signals '({:signal :up, :why :not-down-market} {:signal :up, :why :moving-average-crossover})}
{:last-trade-price 301.0, :last-trade-time 1534782959164, :uuid "c3e9480a-f71f-47b3-b44e-681cea2e4f04", :variance 0.08977400000000005, :standard-deviation 0.2996230965730113, :upper-band 301.04524619314606, :lower-band 299.846753806854, :signals '({:signal :up, :why :not-down-market} {:signal :up, :why :moving-average-crossover})}
{:last-trade-price 300.97, :last-trade-time 1534782959917, :uuid "3e93bb50-17b9-4fd4-9909-f73e4270d7f2", :variance 0.07850500000000235, :standard-deviation 0.2801874372629907, :upper-band 301.06537487452596, :lower-band 299.94462512547403, :signals '({:signal :up, :why :not-down-market} {:signal :up, :why :moving-average-crossover})}
{:last-trade-price 300.74, :last-trade-time 1534782968197, :uuid "a482d4ce-9010-4fa2-aa9b-98500f2c0bf9", :variance 0.07683475000000288, :standard-deviation 0.27719081875127627, :upper-band 301.0848816375026, :lower-band 299.97611836249746, :signals '({:signal :up, :why :not-down-market} {:signal :up, :why :moving-average-crossover})}
   {:last-trade-price 300.48, :last-trade-time 1534782981562, :uuid "fed34401-0c17-48ff-8121-2d3bc0928adf", :variance 0.06945100000000173, :standard-deviation 0.2635355763459684, :upper-band 301.07407115269194, :lower-band 300.0199288473081, :signals '()}
{:last-trade-price 300.22, :last-trade-time 1534782991195, :uuid "566bb32b-e8dc-45f8-b646-1c63e713f079", :variance 0.06911875000000015, :standard-deviation 0.26290445032368726, :upper-band 301.07330890064736, :lower-band 300.02169109935267, :signals '({:signal :up, :why :not-down-market} {:signal :down, :why :percent-b-below-50})}
{:last-trade-price 300.48, :last-trade-time 1534782998385, :uuid "79531fdb-0211-47d4-a969-aae88647536c", :variance 0.053782749999999616, :standard-deviation 0.2319110820982896, :upper-band 301.0353221641966, :lower-band 300.10767783580343, :signals '({:signal :up, :why :not-down-market} {:signal :down, :why :percent-b-below-50})}
{:last-trade-price 300.45, :last-trade-time 1534783004390, :uuid "fd1e7a4c-29cd-4097-9dfc-055fc5085927", :variance 0.047463999999999104, :standard-deviation 0.21786234185833747, :upper-band 301.0197246837167, :lower-band 300.14827531628333, :signals '({:signal :up, :why :not-down-market} {:signal :down, :why :percent-b-below-50})}
   {:last-trade-price 300.29, :last-trade-time 1534783007174, :uuid "d43c3989-d6cc-4f4c-8132-09186eb37e48", :variance 0.04987899999999833, :standard-deviation 0.22333606963497482, :upper-band 301.02567213926994, :lower-band 300.1323278607301, :signals '({:signal :up :why :volume-spike})}
{:last-trade-price 300.26, :last-trade-time 1534783020227, :uuid "9622156e-a195-4659-acc2-4bdbe8f64b50", :variance 0.05391999999999878, :standard-deviation 0.23220680437919725, :upper-band 301.03441360875837, :lower-band 300.1055863912416, :signals '({:signal :up, :why :not-down-market} {:signal :down, :why :percent-b-below-50})}
{:last-trade-price 300.26, :last-trade-time 1534783020729, :uuid "4776fd55-3dc7-4e14-812b-e97656604f2a", :variance 0.05649499999999943, :standard-deviation 0.23768676866834515, :upper-band 301.0403735373367, :lower-band 300.0896264626633, :signals '({:signal :up, :why :not-down-market} {:signal :down, :why :percent-b-below-50})}
{:last-trade-price 300.36, :last-trade-time 1534783040036, :uuid "a8167c06-0cb0-4dc8-b434-6b6ffbe49386", :variance 0.05738874999999958, :standard-deviation 0.23955949156733403, :upper-band 301.04161898313464, :lower-band 300.08338101686536, :signals '({:signal :up, :why :not-down-market} {:signal :down, :why :percent-b-below-50})}
{:last-trade-price 300.13, :last-trade-time 1534783050210, :uuid "1bcc44c6-6664-4683-b9c9-d131d36c0c6e", :variance 0.06602475000000028, :standard-deviation 0.2569528166804176, :upper-band 301.0594056333608, :lower-band 300.0315943666392, :signals '({:signal :up, :why :not-down-market} {:signal :down, :why :percent-b-below-50})}
{:last-trade-price 300.16, :last-trade-time 1534783059244, :uuid "8fb3bfcd-929e-4ae2-aebb-ff93aad312d6", :variance 0.0730927499999996, :standard-deviation 0.2703567088126344, :upper-band 301.0672134176253, :lower-band 299.9857865823747, :signals '({:signal :up, :why :not-down-market} {:signal :down, :why :percent-b-below-50})}
{:last-trade-price 300.19, :last-trade-time 1534783059996, :uuid "9194105e-00c0-452d-931d-f4c818489f19", :variance 0.07547275000000006, :standard-deviation 0.27472304235356754, :upper-band 301.0479460847072, :lower-band 299.9490539152929, :signals '({:signal :up, :why :not-down-market} {:signal :down, :why :percent-b-below-50})}
{:last-trade-price 300.25, :last-trade-time 1534783064603, :uuid "c007bd8b-f05f-436f-8a3a-b71b7ddf2c95", :variance 0.07624275000000043, :standard-deviation 0.2761208974344398, :upper-band 301.0287417948689, :lower-band 299.9242582051311, :signals '({:signal :up, :why :not-down-market} {:signal :down, :why :percent-b-below-50})}
{:last-trade-price 300.69, :last-trade-time 1534783088132, :uuid "7e11254e-3cfd-41b3-9319-113008491a52", :variance 0.07826400000000014, :standard-deviation 0.27975703744499464, :upper-band 301.04351407488997, :lower-band 299.92448592511, :signals '({:signal :up, :why :not-down-market} {:signal :up, :why :moving-average-crossover})}
{:last-trade-price 300.64, :last-trade-time 1534783088633, :uuid "977cf765-d49c-44a7-8d1a-1e48728cd96c", :variance 0.07941899999999988, :standard-deviation 0.28181376829388566, :upper-band 301.05462753658776, :lower-band 299.9273724634122, :signals '({:signal :up, :why :volume-spike})}])

(defn matching-automata? [partitioned-bollinger-band automatas]

  #_(->> (partitioned-bollinger-band->matching-automata partitioned-bollinger-band automatas)
         (map :automata-match?)
         (some #{true})
         true?)

  ;; (info "SANITY /" (partitioned-bollinger-band->matching-automata (take 15 partitioned-bollinger-band) automatas))
  (->> (partitioned-bollinger-band->matching-automata (take 15 partitioned-bollinger-band) automatas)
       (map :automata-match?)
       (some #{true})
       true?))

(comment

  (partitioned-bollinger-band->matching-automata one [strategy-bollinger-band-squeeze-automata-a strategy-bollinger-band-squeeze-automata-b])

  (->> (partitioned-bollinger-band->matching-automata (take 15 one) [strategy-bollinger-band-squeeze-automata-a strategy-bollinger-band-squeeze-automata-b])
       (map :automata-match?))

  (->> (partitioned-bollinger-band->matching-automata one [strategy-bollinger-band-squeeze-automata-a strategy-bollinger-band-squeeze-automata-b])
       (map :automata-match?)
       (filter true?)))

(defn extract-signals-for-strategy-bollinger-bands-squeeze [partitioned-bollinger-band]

  (-> partitioned-bollinger-band
      (matching-automata? [strategy-bollinger-band-squeeze-automata-a])
      ((fn [a]
         (if a
           (transform [LAST :signals]
                      #(conj % {:signal :up :why :strategy-bollinger-bands-squeeze})
                      partitioned-bollinger-band)
           partitioned-bollinger-band)))))

(defn pipeline-signals-bollinger-band [concurrency input-ch signal-bollinger-band-ch]

  (let [bollinger-band-signal-window 24
        bollinger-band-increment 1

        ;; Transducers
        partition-xf (x/partition bollinger-band-signal-window bollinger-band-increment (x/into []))
        matches-window-size? #(= bollinger-band-signal-window
                                 (count %))
        bollinger-band-exists-xf (filter #(->> (filter :upper-band %)
                                               matches-window-size?))

        ;; Channels
        partitioned-ch (chan (sliding-buffer cm/sliding-buffer-window) partition-xf)
        bollinger-band-exists-ch (chan (sliding-buffer cm/sliding-buffer-window) bollinger-band-exists-xf)
        ach (chan (sliding-buffer cm/sliding-buffer-window) partition-xf)
        ;; bch (chan (sliding-buffer cm/sliding-buffer-window) (map extracdt-signals-for-strategy-bollinger-bands-squeeze))
        bch (chan (sliding-buffer cm/sliding-buffer-window))
        ]

    (pipeline concurrency partitioned-ch (map identity) input-ch)
    (pipeline concurrency bollinger-band-exists-ch (map identity) partitioned-ch)
    (pipeline concurrency bch (map (partial slag/bollinger-band cm/sliding-buffer-window)) bollinger-band-exists-ch)
    (pipeline concurrency signal-bollinger-band-ch (map identity) bch)


    ;; TODO partition
    ;; (pipeline concurrency bch (x/partition bollinger-band-signal-window bollinger-band-increment (x/into [])) ach)
    ;; (pipeline concurrency bch (map identity) ach)


    ;; #{:strategy-bollinger-bands-squeeze :percent-b-abouve-50 :bollinger-band-squeeze}

    ;; TODO A) extract-signals-for-strategy-bollinger-bands-squeeze
    ;; this is where the partitioned bollinger band is still reified
    ;; true (extract-signals-for-strategy-bollinger-bands-squeeze)
    ;; output is: signal-bollinger-band-ch -> put a partition on a binding channel
    ;; (pipeline concurrency cch (map identity) bch)

    ;; TODO unpartition (take last item of each partitioned list)
    ;; (pipeline concurrency signal-bollinger-band-ch (map last) bch)
    ))

(defn pipeline-signals-leading [concurrency moving-average-window
                                signal-macd-ch macd->macd-signal
                                ;; signal-stochastic-oscillator-ch stochastic-oscillator->stochastic-oscillator-signal
                                ]

  (let [partition-xf (x/partition moving-average-window 1 (x/into []))
        partitioned-ch (chan (sliding-buffer cm/sliding-buffer-window) partition-xf)]

    (pipeline concurrency partitioned-ch (map identity) macd->macd-signal)
    (pipeline concurrency signal-macd-ch (map slead/macd) partitioned-ch))

  ;; (pipeline concurrency signal-stochastic-oscillator-ch (map slead/stochastic-oscillator)
  ;;           stochastic-oscillator->stochastic-oscillator-signal)
  )

(def partition-xform (x/partition cm/moving-average-window cm/moving-average-increment (x/into [])))

(defn channel-analytics []
  {:source-list-ch (chan (sliding-buffer cm/sliding-buffer-window))
   :parsed-list-ch (chan (sliding-buffer cm/sliding-buffer-window))

   :tick-list-ch (chan (sliding-buffer cm/sliding-buffer-window) (x/partition cm/moving-average-window cm/moving-average-increment (x/into [])))
   :sma-list-ch (chan (sliding-buffer cm/sliding-buffer-window) (x/partition cm/moving-average-window cm/moving-average-increment (x/into [])))
   :ema-list-ch (chan (sliding-buffer cm/sliding-buffer-window))
   :bollinger-band-ch (chan (sliding-buffer cm/sliding-buffer-window))
   :macd-ch (chan (sliding-buffer cm/sliding-buffer-window))

   :tick-list->sma-ch (chan (sliding-buffer cm/sliding-buffer-window))
   ;; :sma-list->ema-ch (chan (sliding-buffer cm/sliding-buffer-window))
   ;; :sma-list->bollinger-band-ch (chan (sliding-buffer cm/sliding-buffer-window))
   :lagging-signals-moving-averages-ch (chan (sliding-buffer cm/sliding-buffer-window))
   :join-analytics->bollinger-band (chan (sliding-buffer cm/sliding-buffer-window))})

(defn channel-join-mults []
  {:tick-list->JOIN (chan (sliding-buffer cm/sliding-buffer-window) (map last))
   :sma-list->JOIN (chan (sliding-buffer cm/sliding-buffer-window) (map last))
   :ema-list->JOIN (chan (sliding-buffer cm/sliding-buffer-window) (map last))
   :bollinger-band->JOIN (chan (sliding-buffer cm/sliding-buffer-window) (map last))
   :sma-list->JOIN->bollinger (chan (sliding-buffer cm/sliding-buffer-window) (map last))

   ;; :macd->JOIN (chan (sliding-buffer cm/sliding-buffer-window) (map last))
   ;; :stochastic-oscillator->JOIN (chan (sliding-buffer cm/sliding-buffer-window) (map last))
   ;; :on-balance-volume->JOIN (chan (sliding-buffer cm/sliding-buffer-window) (map last))
   ;; :relative-strength->JOIN (chan (sliding-buffer cm/sliding-buffer-window) (map last))
   })

(defn channel-signal-moving-averages []
  {:tick-list->moving-averages-signal (chan (sliding-buffer cm/sliding-buffer-window))
   :sma-list->moving-averages-signal (chan (sliding-buffer cm/sliding-buffer-window))
   :ema-list->moving-averages-signal (chan (sliding-buffer cm/sliding-buffer-window))
   :merged-averages (chan (sliding-buffer cm/sliding-buffer-window) (x/partition cm/sliding-buffer-window cm/moving-average-increment (x/into [])))
   :signal-merged-averages (chan (sliding-buffer cm/sliding-buffer-window))
   :signal-moving-averages-ch (chan (sliding-buffer cm/sliding-buffer-window))})

(defn channel-signal-bollinger-band []
  {:tick-list->bollinger-band-signal (chan (sliding-buffer cm/sliding-buffer-window))
   :sma-list->bollinger-band-signal (chan (sliding-buffer cm/sliding-buffer-window))
   :signal-bollinger-band (chan (sliding-buffer cm/sliding-buffer-window) (filter :joined))
   :signal-bollinger-band-ch (chan (sliding-buffer cm/sliding-buffer-window))})

(defn channel->stream [& channels]
  (map #(->> %
             stream/->source
             (stream.cross/event-source->sorted-stream :last-trade-time))
       channels))

(def parse-xform (comp (map #(assoc % :timestamp (-> (t/now) c/to-long)))
                    (map parse-tick)))
(def filter-xform (comp (remove empty-last-trade-price?)
                     (filter rtvolume-time-and-sales?)))

(defn foobar [tick-list-ch bollinger-band-ch macd-ch
              signal-moving-averages-ch signal-bollinger-band-ch
              signal-macd-ch]

  #_(go-loop [c 0 r (<! tick-list-ch)]
      (info "count: " c " / tick-list-ch INPUT " r)
      (when r
        (recur (inc c) (<! tick-list-ch))))

  #_(go-loop [c 0 r (<! bollinger-band-ch)]
      (info "count: " c " / bollinger-band-ch INPUT " r)
      (when r
        (recur (inc c) (<! bollinger-band-ch))))

  #_(go-loop [c 0 r (<! macd-ch)]
    (info "count: " c " / macd-ch INPUT " r)
    (when r
      (recur (inc c) (<! macd-ch))))

  #_(go-loop [c 0 r (<! signal-moving-averages-ch)]
      (info "count: " c " / MA signals: " r)
      (when r
        (recur (inc c) (<! signal-moving-averages-ch))))

  #_(go-loop [c 0 r (<! signal-bollinger-band-ch)]
    (info "count: " c " / BB signals: " r)
    (when r
      (recur (inc c) (<! signal-bollinger-band-ch))))

  (go-loop [c 0 r (<! signal-macd-ch)]
    (info "count: " c " / MACD signals: " r)
    (when r
      (recur (inc c) (<! signal-macd-ch)))))

(defn setup-publisher-channel [source-ch output-ch stock-name concurrency ticker-id-filter]

  (let [options {:stock-match {:symbol stock-name :ticker-id-filter ticker-id-filter}}


        ;; Channels Analytics
        {:keys [source-list-ch parsed-list-ch
                tick-list-ch sma-list-ch ema-list-ch bollinger-band-ch
                macd-ch
                tick-list->sma-ch

                ;; sma-list->ema-ch
                ;; sma-list->bollinger-band-ch
                ;; lagging-signals-moving-averages-ch
                lagging-signals-bollinger-band-connector-ch]}
        (channel-analytics)


        ;; Channels Signal: Moving Averages
        {:keys [tick-list->moving-averages-signal sma-list->moving-averages-signal ema-list->moving-averages-signal
                merged-averages signal-merged-averages signal-moving-averages-ch]}
        (channel-signal-moving-averages)


        ;; Signal Bollinger Band
        {:keys [tick-list->bollinger-band-signal sma-list->bollinger-band-signal
                ;; signal-bollinger-band
                signal-bollinger-band-ch]}
        (channel-signal-bollinger-band)

        signal-macd-ch (chan (sliding-buffer cm/sliding-buffer-window))]


    (doseq [source+mults [[source-list-ch parsed-list-ch]
                          [tick-list-ch tick-list->sma-ch]]]
      (apply bind-channels->mult source+mults))


    ;; TICK LIST
    (pipeline concurrency source-list-ch parse-xform source-ch)
    (pipeline concurrency tick-list-ch filter-xform source-list-ch)


     ;; ANALYSIS
    (pipeline-analysis-lagging concurrency options
                               sma-list-ch tick-list->sma-ch
                               ema-list-ch bollinger-band-ch)

    (pipeline-analysis-leading concurrency options cm/sliding-buffer-window
                               macd-ch bollinger-band-ch)

    ;; SIGNALS
    ;; (pipeline-signals-moving-average concurrency bollinger-band-ch signal-moving-averages-ch)
    (pipeline-signals-moving-average concurrency macd-ch signal-moving-averages-ch)


    ;; TODO implement Trendlines (a Simple Moving Average?)
    (pipeline-signals-bollinger-band concurrency signal-moving-averages-ch signal-bollinger-band-ch)

    #_(pipeline-signals-leading concurrency cm/sliding-buffer-window
                              signal-macd-ch signal-bollinger-band-ch)

    #_(foobar tick-list-ch bollinger-band-ch macd-ch signal-moving-averages-ch signal-bollinger-band-ch signal-macd-ch)


    {:joined-channel signal-bollinger-band-ch
     :input-channel parsed-list-ch}))

(defn teardown-publisher-channel [joined-channel-map]
  (doseq [v (vals joined-channel-map)]
    (close! v)))
