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
             :last-trade-time (Long/parseLong (:last-trade-time rm))
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
