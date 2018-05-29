(ns com.interrupt.edgar.ib.handler.live
  (:use [clojure.repl]
          [clojure.core.strint]
          [clojure.tools.namespace.repl]
          [datomic.api :only [q db] :as d])
  (:require [net.cgrand.xforms :as x]
            [clojure.tools.logging :as log]
            [clojure.string :as cs]
            [cljs-uuid.core :as uuid]
            #_[clojure.walk :as walk]
            #_[clojure.pprint :as pprint]
            #_[com.interrupt.edgar.tee.datomic :as tdatomic]
            #_[com.interrupt.edgar.core.analysis.lagging :as lagging]))


(defn load-filtered-results
    "Find entity.symbol (and entire entity) where price-difference is greatest"
    [limit conn]

    (let [historical-entities (q '[:find ?p ?s ?c :where
                                   [?h :historical/price-difference ?p]
                                   [?h :historical/symbol ?s]
                                   [?h :historical/company ?c]]
                                 (db conn))
          sorted-entities (reverse (sort-by first historical-entities))]

      (if limit
        (take limit sorted-entities)
        sorted-entities)))

#_(defn handle-tick-price
    " Format will look like:

    {type tickPrice, tickerId 0, timeStamp #<DateTime 2013-05-01T13:29:38.129-04:00>, price 412.14, canAutoExecute 0, field 4}"
    [options evt]

    #_(log/info "handle-tick-price > options[" (dissoc options :tick-list) "] evt[" evt "]")
    (dosync (alter (:tick-list options)
                   (fn [inp]

                     #_(conj inp (walk/keywordize-keys (merge evt {:uuid (str (uuid/make-random))})))
                     (as-> evt e
                       (merge e {:uuid (str (uuid/make-random))})
                       (walk/keywordize-keys e)
                       (list e)
                       (concat inp e))))))

#_(defn handle-tick-string
    "Format will look like:

   {type tickString, tickerId 0, tickType 48, value 412.14;1;1367429375742;1196;410.39618025;true}"
    [options evt]


    ;; :value       ;0;1522337866199;67085;253.23364232;true
    ;; :value 255.59;1;1522337865948;67077;253.23335428;true

    ;; ;0;1522334506905;47098;252.27282467;true

    ;; 0; ticker-id
    ;; 1522334506905; timestamp
    ;; 47098; volume
    ;; 252.27282467; price
    ;; true

    :last-trade-price
    :last-trade-size
    :total-volume
    :last-trade-time
    :vwap

    (log/info "handle-tick-string > options[" (dissoc options :tick-list) "] evt[" evt "]")
    (let [tvalues (remove empty?
                          (cs/split (:value evt) #";"))
          tkeys [:last-trade-price :last-trade-size :last-trade-time :total-volume :vwap :single-trade-flag]

          #_(if (= 5 (count tvalues))
              [:ticker-id :last-trade-time :total-volume :last-trade-price :single-trade-flag]
              [:last-trade-price :last-trade-size :last-trade-time :total-volume :vwap :single-trade-flag])

          ;; parsing RTVolume data
          result-map (zipmap tkeys tvalues)]

      (dosync (alter (:tick-list options)
                     (fn [inp] (as-> result-map rm
                                (merge rm {:ticker-id (:ticker-id evt)
                                           :type (:topic evt)
                                           :uuid (str (uuid/make-random))})
                                (list rm)
                                (concat inp rm)))))))

#_(defn handle-event [options evt]

  (let [tick-list (:tick-list options)
        tee-list (if (:tee-list options)
                   (conj (:tee-list options) tdatomic/tee-market)
                   [tdatomic/tee-market])
        tick-window (if (:tick-window options)
                      (:tick-window options)
                      40)]

    #_(log/info "com.interrupt.edgar.core.edgar/handle-event [" evt
                "] FILTER[" (-> options :stock-match :ticker-id-filter)
                "] > tick-list size[" (count @tick-list) "]")

    (when (= :tick-price (:topic evt))
      (handle-tick-price options evt))

    (when (and (= :tick-string (:topic evt))
               (= 48 (:tick-type evt)))
      (handle-tick-string options evt))


    ;; At the end of our tick window
    ;;  - only for RTVolume last ticks
    ;;  - wrt a given tickerId
    (let [trimmed-list (->> @tick-list
                            (filter #(= :tick-string (:type %)))
                            (filter #(if (and (not (nil? (:ticker-id evt)))
                                              (not (nil? (:ticker-id %))))
                                       (= (int (:ticker-id evt))
                                          (int (:ticker-id %))))))
          tail-evt (first trimmed-list)]

      (log/info "com.interrupt.edgar.core.edgar/handle-event vs > trimmed[" (count trimmed-list)
                "] format[" (first @tick-list)
                "] tick-list count[" (count @tick-list)
                "] > CHECK[" (>= (count trimmed-list) tick-window)
                "] > Event[" evt "]")


      ;; i. spit the data out to DB and
      ;; ii. and trim the list list back to the tick-window size
      (if (>= (count trimmed-list) tick-window)

        (do

          (reduce (fn [rslt efn]

                    (efn {:symbol (-> options :stock-match :symbol)
                          :ticker-id (-> options :stock-match :ticker-id-filter)
                          :event-list trimmed-list}))
                  nil
                  tee-list)


          ;; trim down tick-list if it's greater than the tick-window (defaults to 40)
          (if (>= (count trimmed-list) tick-window)
            (dosync
             (alter tick-list
                    (fn [inp]
                      (into []
                            (remove #(= (:uuid tail-evt) (% :uuid))
                                    inp)))))))))))


(def rt-volume-time-and-sales-type 48)
(def tick-string-type :tick-string)
(def moving-average-window 20)
(def moving-average-increment 1)

(defn rtvolume-time-and-sales? [event]
  (and (= rt-volume-time-and-sales-type (:tick-type event))
       (= tick-string-type (:topic event))))

(defn parse-tick-string
  "Input format:

   {:type tickString, :tickerId 0, :tickType 48, :value 412.14;1;1367429375742;1196;410.39618025;true}

   Value format:

   :value       ;0;1522337866199;67085;253.23364232;true
   :value 255.59;1;1522337865948;67077;253.23335428;true"
  [event]

  (let [tvalues (cs/split (:value event) #";")
        tkeys [:last-trade-price :last-trade-size :last-trade-time :total-volume :vwap :single-trade-flag]]

    (as-> (zipmap tkeys tvalues) rm
      (merge rm {:ticker-id (:ticker-id event)
                 :type (:topic event)
                 :uuid (str (uuid/make-random))})
      (assoc rm
             :last-trade-price (if (not (empty? (:last-trade-price rm)))
                                 (read-string (:last-trade-price rm)) 0)
             :last-trade-size (read-string (:last-trade-size rm))
             :total-volume (read-string (:total-volume rm))
             :vwap (read-string (:vwap rm))))))

(defn empty-last-trade-price? [event]
  (-> event :last-trade-price (<= 0)))

#_(defn simple-moving-average-format [sym event]
  (assoc event :symbol sym))

#_(defn handle-event [options evt]

  (let [tick-list (:tick-list options)
        tee-list (if (:tee-list options)
                   (conj (:tee-list options) tdatomic/tee-market)
                   [tdatomic/tee-market])
        tick-window (if (:tick-window options)
                      (:tick-window options)
                      40)]

    tee-list))

(def handler-xform
  (comp (filter rtvolume-time-and-sales?)
     (map parse-tick-string)
     (remove empty-last-trade-price?)  ;; TODO For now, ignore empty lots
     ))

(defn feed-handler
  "Event structures will look like 'tickPrice' or 'tickString'

   Options are:
     :tick-list - the list into which result tick events will be put
     :tee-list - list of pipes to which result events will be pushed
     :stock-match :symbol , :ticker-id-filter - a list of tickerIds about which this feed-handler cares"
  [options evt]

  (let [stock-match (:stock-match options)]

    ;; TODO - core.async channel transformations
    #_(if (and (not (nil? (-> options :stock-match :ticker-id-filter)))
             (= (int (:ticker-id evt))
                (int (-> options :stock-match :ticker-id-filter))))
      )))


(comment  ;; Parsing tick-string / xform input -> tick-list

  (def input-source (read-string (slurp "live.4.edn")))
  (def options {:stock-match {:symbol "TSLA"
                              :ticker-id-filter 0}})


  ;; 1
  (->> input-source
       (filter rtvolume-time-and-sales?)

       (take 100)
       pprint)


  ;; 2
  (def e1 {:topic :tick-string
           :ticker-id 0
           :tick-type 48
           :value ";0;1524063373067;33853;295.43541145;true"})

  (def e2 {:topic :tick-string
           :ticker-id 0
           :tick-type 48
           :value "295.31;8;1524063373569;33861;295.43538182;true"})

  (parse-tick-string e1)
  (parse-tick-string e2)


  ;; 3
  (->> input-source
       (filter rtvolume-time-and-sales?)
       (map parse-tick-string)

       (take 10)
       pprint)

  ;; 3.1
  (-> (filter rtvolume-time-and-sales?)
      (sequence (take 50 input-source))
      pprint)


  (def xf (comp (filter rtvolume-time-and-sales?)
             (map parse-tick-string)
             (x/partition moving-average-window moving-average-increment (x/into []))
             (map (partial simple-moving-average-format options))))


  (def result (-> xf
                  (sequence (take 500 input-source)))))

(comment  ;; Abstracting out xform -> handler-xform

  (def input-source (read-string (slurp "live.4.edn")))
  (def options {:stock-match {:symbol "TSLA"
                              :ticker-id-filter 0}})

  (def result (-> handler-xform
                  (sequence (take 1000 input-source)))))

(comment  ;; Generating sma-list, ema-list, bollinger-band

  (require '[com.interrupt.edgar.core.analysis.lagging :as al])

  (def input-source (read-string (slurp "live.4.edn")))
  (def options {:stock-match {:symbol "TSLA"
                              :ticker-id-filter 0}})

  (def sma-list (-> (comp handler-xform
                       (x/partition moving-average-window moving-average-increment (x/into []))
                       (map (partial al/simple-moving-average {})))
                    (sequence (take 1000 input-source))))

  (def ema-list (al/exponential-moving-average {} moving-average-window sma-list))

  (def bollinger-band (al/bollinger-band moving-average-window sma-list)))

(comment  ;; Playing with promisespromises

  (require '[clojure.core.async :refer [to-chan <!!]]
           '[manifold.stream :as s]
           '[prpr.stream.cross :as prpr :refer [event-source->sorted-stream]])


  (def c1 (to-chan [{:id 2 :value "a"} {:id 3} {:id 4}]))
  (def c2 (to-chan [{:id 1} {:id 2 :value "b"} {:id 3}]))
  (def c3 (to-chan [{:id 0} {:id 1} {:id 2 :value "c"}]))

  (def cs1 (s/->source c1))
  (def cs2 (s/->source c2))
  (def cs3 (s/->source c3))

  (def kss {:cs1 cs1 :cs2 cs2 :cs3 cs3})
  (def os (prpr/full-outer-join-streams
            {:default-key-fn :id
             :skey-streams kss}))
  (def ovs @(s/reduce conj [] os))


  #_(def ss1 (prpr/event-source->sorted-stream :id cs1))
  #_(def ss2 (prpr/event-source->sorted-stream :id cs2))
  #_(def ss3 (prpr/event-source->sorted-stream :id cs3))

  #_(def result (prpr/set-streams-union {:default-key-fn :id
                                       :skey-streams {:ss1 ss1
                                                      :ss2 ss2
                                                      :ss3 ss3}}))

  #_(s/take! result)

  ;; ClassCastException manifold.deferred.SuccessDeferred cannot be cast to manifold.stream.core.IEventSource  com.interrupt.edgar.ib.handler.live/eval47535 (form-init767747358322570513.clj:266)

  ;;cross-streams
  ;;sort-merge-streams
  ;;set-streams-union (which uses cross-streams)


  ;;clojure.core.async [mult
  ;;                    merge mix
  ;;                    pub sub
  ;;                    map]
  ;;net.cgrand/xforms [group-by for]


  (let [s0 (s/->source [{:foo 1 :bar 10} {:foo 3 :bar 30} {:foo 4 :bar 40}])
        s1 (s/->source [{:foo 1 :baz 100} {:foo 2 :baz 200} {:foo 3 :baz 300}])
        kss {:0 s0 :1 s1}

        os @(prpr/full-outer-join-streams
             {:default-key-fn :foo
              :skey-streams kss})
        ovs @(s/reduce conj [] os)
        ]

    (println "Foo: " os)
    (println "Bar: " ovs)

    #_(is (= [{:0 {:foo 1 :bar 10} :1 {:foo 1 :baz 100}}
            {:1 {:foo 2 :baz 200}}
            {:0 {:foo 3 :bar 30} :1 {:foo 3 :baz 300}}
            {:0 {:foo 4 :bar 40}}]
           ovs))))

(comment  ;; SUCCESS with promisespromises

  (require '[clojure.core.async :refer [to-chan chan go-loop <!]]
           '[manifold.stream :as stream]
           '[prpr.stream.cross :as stream.cross]
           'prpr.stream
           '[prpr.promise :refer [ddo]]
           '[xn.transducers :as xn])

  (let [c1 (to-chan [{:id 2 :a "a"} {:id 3} {:id 4}])
        c2 (to-chan [{:id 1} {:id 2 :b "b"} {:id 3}])
        c3 (to-chan [{:id 0} {:id 1} {:id 2 :c "c"}])
        cs1 (stream/->source c1)
        cs2 (stream/->source c2)
        cs3 (stream/->source c3)
        ss1 (stream.cross/event-source->sorted-stream :id cs1)
        ss2 (stream.cross/event-source->sorted-stream :id cs2)
        ss3 (stream.cross/event-source->sorted-stream :id cs3)]


    #_(let [result (stream.cross/set-streams-union {:default-key-fn :id
                                                  :skey-streams {:ss1 ss1
                                                                 :ss2 ss2
                                                                 :ss3 ss3}})]
        @(stream/take! @result))

    (ddo [u-s (stream.cross/set-streams-union {:default-key-fn :id
                                                 :skey-streams {:ss1 ss1
                                                                :ss2 ss2
                                                                :ss3 ss3}})]
           (->> u-s
                (stream/map
                 (fn [r]
                   (prn r)
                   r))
                (prpr.stream/count-all-throw
                 "count results")))

    #_(stream.cross/set-streams-union {:default-key-fn :id
                                     :skey-streams {:ss1 ss1
                                                    :ss2 ss2
                                                    :ss3 ss3}})

    #_(let [oc (chan 1 (map vals))
          result (stream.cross/set-streams-union {:default-key-fn :id
                                                  :skey-streams {:ss1 ss1
                                                                 :ss2 ss2
                                                                 :ss3 ss3}})]
      (stream/connect @result oc)
      (go-loop [r (<! oc)]
        (println "record: " r)
        (println "record merged: " (apply merge r))
        (println "")
        (if-not r
          r
          (recur (<! oc)))))))
