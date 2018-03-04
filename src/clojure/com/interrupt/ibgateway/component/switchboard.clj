(ns com.interrupt.ibgateway.component.switchboard
  (:require [clojure.core.async :refer [chan >! <! merge go go-loop pub sub unsub-all sliding-buffer]]
            [clojure.set :as cs]
            [clojure.math.combinatorics :as cmb]
            [franzy.clients.producer.client :as producer]
            [franzy.clients.consumer.client :as consumer]
            [franzy.clients.consumer.callbacks :refer [consumer-rebalance-listener]]
            [franzy.clients.producer.protocols :refer :all]
            [franzy.clients.consumer.protocols :refer :all]
            [franzy.common.metadata.protocols :refer :all]
            [franzy.serialization.serializers :as serializers]
            [franzy.serialization.deserializers :as deserializers]
            [franzy.admin.zookeeper.client :as client]
            [franzy.admin.topics :as topics]
            [franzy.clients.producer.defaults :as pd]
            [franzy.clients.consumer.defaults :as cd]
            [datomic.api :as d]
            [mount.core :refer [defstate] :as mount]
            [com.interrupt.ibgateway.component.ewrapper :as ew]
            [com.interrupt.ibgateway.component.switchboard.store :as store]
            [com.interrupt.ibgateway.component.switchboard.brokerage :as brok]))


#_(def topic-scanner-command "scanner-command")
#_(def topic-scanner-command-result "scanner-command-result")
#_(def topic-scanner "scanner")
#_(def topic-filtered-stocks "filtered-stocks")
#_(def topic-stock-command "stock-command")

#_(defn setup-topics [zookeeper-url]

    (def zk-utils (client/make-zk-utils {:servers [zookeeper-url]} false))
    (doseq [topic [topic-scanner-command
                   topic-scanner-command-result
                   topic-scanner
                   topic-filtered-stocks
                   topic-stock-command]
            :let [partition-count 1]]
      (topics/create-topic! zk-utils topic partition-count))

    (topics/all-topics zk-utils))

#_(defrecord Switchboard [zookeeper-url kafka-url]
    component/Lifecycle
    (start [component]

      (setup-topics zookeeper-url)
      (assoc component :status :up))
    (stop [{server :server :as component}]
      (assoc component :status :down)))

#_(defn new-switchboard [zookeeper-url kafka-url]
    (map->Switchboard {:zookeeper-url zookeeper-url
                       :kafka-url kafka-url }) )


;; ==
#_(def running (atom true))

#_(defn consume-topic-example []

    (let [cc {:bootstrap.servers       ["kafka:9092"]
              :group.id                "test"
              ;;jump as early as we can, note this isn't necessarily 0
                                        ;:auto.offset.reset       :earliest
              ;;here we turn on committing offsets to Kafka itself, every 1000 ms
                                        ;:enable.auto.commit      true
                                        ;:auto.commit.interval.ms 1000
              }
          key-deserializer (deserializers/keyword-deserializer)
          value-deserializer (deserializers/edn-deserializer)
          topic "scanner-command"

          ;;Here we are demonstrating the use of a consumer rebalance listener. Normally you'd use this with a manual
          ;;consumer to deal with offset management.
          ;;As more consumers join the consumer group, this callback should get fired among other reasons.
          ;;To implement a manual consumer without this function is folly, unless you care about losing data, and
          ;;probably your job.
          ;;One could argue though that most data is not as valuable as we are told. I heard this in a dream once or in
          ;;intro to Philosophy.
          rebalance-listener (consumer-rebalance-listener (fn [topic-partitions]
                                                            (println "topic partitions assigned:" topic-partitions))
                                                          (fn [topic-partitions]
                                                            (println "topic partitions revoked:" topic-partitions)))
          ;;We create custom producer options and set out listener callback like so.
          ;;Now we can avoid passing this callback every call that requires it, if we so desire
          ;;Avoiding the extra cost of creating and garbage collecting a listener is a best practice
          options (cd/make-default-consumer-options {:rebalance-listener-callback rebalance-listener})]

      (with-open [c (consumer/make-consumer cc key-deserializer value-deserializer options)]
        ;;Note! - The subscription will read your comitted offsets to position the consumer accordingly
        ;;If you see no data, try changing the consumer group temporarily
        ;;If still no, have a look inside Kafka itself, perhaps with franzy-admin!
        ;;Alternatively, you can setup another threat that will produce to your topic while you consume, and all should be well
        (subscribe-to-partitions! c [topic])
        ;;Let's see what we subscribed to, we don't need Cumberbatch to investigate here...
        (println "Partitions subscribed to:" (partition-subscriptions c))

        (loop [records nil]

          (if (true? @running)

            (let [cr (poll! c)


                  ;;a naive transducer, written the long way
                  filter-xf (filter (fn [cr] (= (:key cr) :inconceivable)))
                  ;;a naive transducer for viewing the values, again long way
                  value-xf (map (fn [cr] (:value cr)))
                  ;;more misguided transducers
                  inconceivable-transduction (comp filter-xf value-xf)]


              (println "Record count:" (record-count cr))
              (println "Records by topic:" (records-by-topic cr topic))
              ;;The source data is a seq, be careful!
              (println "Records from a topic that doesn't exist:" (records-by-topic cr "no-one-of-consequence"))
              (println "Records by topic partition:" (records-by-topic-partition cr topic 0))
              ;;The source data is a list, so no worries here....
              (println "Records by a topic partition that doesn't exist:" (records-by-topic-partition cr "no-one-of-consequence" 99))
              (println "Topic Partitions in the result set:" (record-partitions cr))
              (clojure.pprint/pprint (into [] inconceivable-transduction cr))
                                        ;(println "Now just the values of all distinct records:")
              (println "Put all the records into a vector (calls IReduceInit):" (into [] cr))

              ;; TODO

              ;; [ok] > Put the result onto a
              ;;   channel?
              ;;   atom?
              ;;   embedded db
              ;;     Apache derby
              ;;     H2
              ;;     FleetDB
              ;;     Datomic
              ;; Commands for: scanner, stock, historical


              ;; [ok] Rename to 'switchboard'


              ;; > SWITCHBOARD

              ;; - read from kafka
              ;; - store to DB state

              ;; Call ewrapper-impl/scanner-subscribe
              ;;   store request IDs (w/ associated STOCK or SCAN)
              ;;   lookup request IDs

              ;; [ on | subscribed ]

              ;; 1 0 - subscribe to scan, store TWS req ID
              ;; 1 1 - no op
              ;; 0 1 - find TWS req ID, unsubscribe
              ;; 0 0 - no op

              ;; Use n FSM For each scan, to track steps:
              ;; https://github.com/ztellman/automat
              ;; - check if subscription is on
              ;; - check if we're subscribed
              ;; - subscribe | unsuscribe
              ;; - store request ID to DB
              ;; ** triggered after startup
              ;; ** triggered when receiving a signal from kafka

              ;; ** a function to get an available Request ID
              ;; ** a function to i. find a request ID, ii. for an instrument, iii. for a scan type


              (recur cr))

            (clear-subscriptions! c))))))

(comment  ;; Workbench to consume from Kafka

    (consume-topic-example)

    #_(swap! running (constantly false))
    #_@running


    (import '[java.util Properties Arrays]
            '[org.apache.kafka.clients.consumer KafkaConsumer ConsumerRecords]
            '[org.apache.kafka.common TopicPartition])

    (def props (Properties.))
    (.put props "bootstrap.servers" "kafka:9092")
    (.put props "group.id" "test")
    (.put props "key.deserializer"  "org.apache.kafka.common.serialization.StringDeserializer" #_(deserializers/keyword-deserializer))
    (.put props "value.deserializer" "org.apache.kafka.common.serialization.StringDeserializer" #_(deserializers/edn-deserializer))

    (def consumer (KafkaConsumer. props))
    (.subscribe consumer ["scanner-command"])

    ;; consumer poll returns no records unless called more than once
    ;; http://grokbase.com/t/kafka/users/155m9916sv/consumer-poll-returns-no-records-unless-called-more-than-once-why#20150520qpwf3n0xdt20k0kg3fx83h1dmg

    ;; Have to call .poll a few times before getting back data
    (def ^ConsumerRecords records (.poll consumer 1000))
    (pprint (seq records))
    (.close consumer))

#_(comment  ;; DB workbench


    ;; Schema


    ;; Examples
    {:switchboard/scanner :stock-scanner
     :switchboard/state :on}

    {:switchboard/scanner :stock-price
     :switchboard/state :on
     :switchboard/instrument "TSLA"}

    {:switchboard/scanner :stock-historical
     :switchboard/state :on
     :switchboard/instrument "TSLA"}


    (require '[datomic.api :as d])
    (def uri "datomic:mem://ibgateway")

    (def rdelete (d/delete-database uri))
    (def rcreate (d/create-database uri))
    (def conn (d/connect uri))
    (def db (d/db conn))


    ;; SCHEMA
    (def scanner-schema
      [;; used for > MARKET SCANNER | STOCK | STOCK HISTORICAL
       {:db/ident :switchboard/scanner
        :db/valueType :db.type/ref
        :db/cardinality :db.cardinality/one
        :db/doc "The type of connection: market scanner | stock | stock historical"}

       {:db/ident :switchboard/state
        :db/valueType :db.type/ref
        :db/cardinality :db.cardinality/one
        :db/doc "A simple switch on whether or not, to scan"}

       {:db/ident :switchboard/request-id
        :db/valueType :db.type/long
        :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity
        :db/doc "Records the request id made to TWS"}

       {:db/ident :switchboard/instrument
        :db/valueType :db.type/string
        :db/cardinality :db.cardinality/one
        :db/doc "The stock symbol being tracked"}


       {:db/ident :switchboard/on}
       {:db/ident :switchboard/off}
       {:db/ident :switchboard/stock-scanner}
       {:db/ident :switchboard/stock-price}
       {:db/ident :switchboard/stock-historical}])

    (def schema-transact-result (d/transact conn scanner-schema))


    ;; TURN ON
    (def scanner-on [{:switchboard/scanner :switchboard/stock-scanner
                      :switchboard/state :switchboard/on}])

    (def scanner-off [{:switchboard/scanner :switchboard/stock-scanner
                       :switchboard/state :switchboard/off}])

    (def stock-scanner-on [{:switchboard/scanner :switchboard/stock-price
                            :switchboard/state :switchboard/on
                            :switchboard/instrument "TSLA"}])

    (def stock-historical-on [{:switchboard/scanner :switchboard/stock-historical
                               :switchboard/state :switchboard/on
                               :switchboard/instrument "TSLA"}])

    (def all-on [{:switchboard/scanner :switchboard/stock-scanner
                  :switchboard/state :switchboard/on}

                 {:switchboard/scanner :switchboard/stock-price
                  :switchboard/state :switchboard/on
                  :switchboard/instrument "TSLA"}

                 {:switchboard/scanner :switchboard/stock-historical
                  :switchboard/state :switchboard/on
                  :switchboard/instrument "TSLA"}])

    #_(d/transact conn scanner-on)
    #_(d/transact conn scanner-off)
    #_(d/transact conn stock-scanner-on)
    #_(d/transact conn stock-historical-on)

    (def result (d/transact conn all-on))

    (pprint (->> (for [color [:red :blue]
                       size [1 2]
                       type ["a" "b"]]
                   {:inv/color color
                    :inv/size size
                    :inv/type type})
                 (map-indexed
                  (fn [idx map]
                    (assoc map :inv/sku (str "SKU-" idx))))
                 vec))


    ;; QUERY
    (pprint (d/q '[:find (pull ?e [:switchboard/instrument
                                   {:switchboard/scanner [*]}
                                   {:switchboard/state [*]}])
                   :where
                   [?e :switchboard/state]]
                 (d/db conn)))

    (pprint (d/q '[:find (pull ?e [{:switchboard/state [*]}
                                   :switchboard/instrument])
                   :where
                   [?e :switchboard/scanner ?s]
                   [?s :db/ident :switchboard/stock-scanner]]
                 (d/db conn)))


    ;; TURN ON - IBM
    (def ibm-on [{:switchboard/scanner :switchboard/stock-price
                  :switchboard/state :switchboard/on
                  :switchboard/instrument "IBM"}])

    (d/transact conn ibm-on)


    ;; QUERY
    (pprint (d/q '[:find (pull ?e [{:switchboard/state [*]}
                                   :switchboard/instrument])
                   :where
                   [?e :switchboard/state]
                   [?e :switchboard/instrument "IBM"]
                   (d/db conn)]))


    ;;ADD an entity
    (def intl-on [{:switchboard/scanner {:db/id [:db/ident :switchboard/stock-price]}
                   :switchboard/state {:db/id [:db/ident :switchboard/on]}
                   :switchboard/instrument "INTL"}])

    (d/transact conn intl-on)

    ;; UPDATE an entity
    (def ibm-off [[:db/add
                   [:switchboard/instrument "IBM"]
                   :switchboard/state :stock-price-state/off]])

    #_(d/transact conn ibm-off)

    ;; QUERY - does a stock price scan exist?
    #_(pprint (d/q '[:find (pull ?e [{:switchboard/state [*]}
                                     :switchboard/instrument])
                     :where
                     [?e :switchboard/state ?s]
                     [?s :db/ident :stock-price-state/on]]
                   (d/db conn)))

    #_(pprint (d/q '[:find (pull ?e [{:switchboard/state [*]}
                                     :switchboard/instrument])
                     :where
                     [?e :switchboard/state ?s]
                     [?s :db/ident :stock-price-state/off]]
                   (d/db conn)))



    ;; QUERY - does a historical fetch exist?
    #_(pprint (d/q '[:find (pull ?e [{:switchboard/state [*]}
                                     :switchboard/instrument])
                     :where
                     [?e :switchboard/state ?s]
                     [?s :db/ident :stock-historical-state/on]]
                   (d/db conn)))

    #_(pprint (d/q '[:find (pull ?e [{:switchboard/state [*]}
                                     :switchboard/instrument])
                     :where
                     [?e :switchboard/state ?s]
                     [?s :db/ident :stock-historical-state/off]]
                   (d/db conn))))



#_(comment  ;; State Machine

    ;; reqMktData



    ;; Reduce-fsm
    (require '[reduce-fsm :as fsm])

    (defn print-message [[msg & etal]]
      (println "Message: " msg " / etal: " etal)
      \a)

    #_(fsm/defsm process-message
        [#_[:start
            {:stock-scanner/state _} -> {:action print-message} :received]
         [:received
          {:stock-scanner/state :on} -> {:action print-message} :on
          {:stock-scanner/state :off} -> {:action print-message} :off]
         [:on {:stock-scanner/state :on} -> {:action print-message} :subscribed]
         [:off \u -> {:action print-message} :unsubscribed]
         [:subscribed {:stock-scanner/state :on} -> {:action print-message} :done]
         [:unsubscribed \d -> {:action print-message} :done]
         [:done {:action print-message :is-terminal true}]])

    (fsm/defsm process-message
      [[:received {:stock-scanner/state :on} -> :on]
       [:on \a -> :done]
       [:done {:is-terminal true}]])

    (process-message {:writer println} [{:stock-scanner/state :on}])



    ;; Automat
    (require '[automat.viz :refer (view)])
    (require '[automat.core :as a])

    (view [1 2 3])


    (def switchboard-fsm-template [(a/or [(a/$ :received) (a/$ :on) (a/$ :subscribed) (a/$ :done)]
                                         [(a/$ :received) (a/$ :off) (a/$ :unsubscribed) (a/$ :done)])])

    (view switchboard-fsm-template)
    (def switchboard-fsm (a/compile switchboard-fsm-template
                                    {:signal :state
                                     :reducers {:received (fn [one two]
                                                            (println "recieved CALLED: one: " one " / two: " two)
                                                            (assoc one :received :received))
                                                :on (fn [one two]
                                                      (println "on CALLED: one: " one " / two: " two)
                                                      (assoc one :on :on))
                                                :subscribed (fn [one two]
                                                              (println "subscribed CALLED: one: " one " / two: " two)
                                                              (assoc one :subscribed :subscribed))
                                                :done (fn [one two]
                                                        (println "done CALLED: one: " one " / two: " two)
                                                        (assoc one :done :done))}}))
    (def value {:switchboard/scanner :stock-scanner
                :switchboard/state :on})

    (def adv (partial a/advance switchboard-fsm))
    (clojure.pprint/pprint (-> value
                               (adv {:state :received})
                               (adv {:state :on})
                               (adv {:state :subscribed})
                               (adv {:state :done})
                               ))

    (view switchboard-fsm)


    #_(clojure.pprint/pprint (a/advance switchboard-fsm
                                        {:switchboard/scanner :stock-scanner
                                         :switchboard/state :on}
                                        )))

#_(comment  ;; Automat directly

    (require '[automat.viz :refer (view)])
    (require '[automat.core :as a])

    ;;
    (def pages [:cart :checkout :cart])
    (def page-pattern
      (vec
       (interpose (a/* a/any) pages)))

    (view page-pattern)


    ;;
    (def page-pattern
      (->> [:cart :checkout :cart]
           (map #(vector [% (a/$ :save)]))
           (interpose (a/* a/any))
           vec))

    (def f
      (a/compile

       [(a/$ :init)
        page-pattern
        (a/$ :offer)]

       {:reducers {:init (fn [m _] (assoc m :offer-pages []))
                   :save (fn [m page] (update-in m [:offer-pages] conj page))
                   :offer (fn [m _] (assoc m :offer? true))}}))

    (view page-pattern)
    (view f)

    )


#_(defn subscribed? [conn scan instrument]

    (d/q '[:find (pull ?e [{:switchboard/state [*]}
                           :switchboard/instrument])
           :in $ ?scan ?instrument
           :where
           [?e :switchboard/scanner ?scan]
           [?e :switchboard/state :on]
           [?e :switchboard/instrument ?instrument]
           (d/db conn) scan instrument]))

#_(defn subscribe-to-scan [conn scanner]

    ;; Get the next request-id
    ;; ...
    (declare request-id)

    ;; TWS Stuff
    ;; ...

    ;; Store request-id
    (let [scan {:switchboard/scanner scanner ;; :switchboard/stock-scanner
                :switchboard/state :switchboard/on
                :switchboard/request-id request-id}]

      (d/transact conn scan)))

#_(defn unsubscribe-from-scan [scanner] 3)

#_(defmulti process-message (fn [{scanner :switchboard/scanner}] scanner))

#_(defmethod process-message :stock-scanner [{:keys [:switchboard/scanner :switchboard/state]}]


    (if (= state :on)

      ;; Do :on stuff
      (if (subscribed? scanner)

        :noop  ;; :on and :subscribed
        (subscribe-to-scan scanner))

      ;; Do :off stuff
      (if (subscribed? scanner)

        (unsubscribe-from-scan scanner)
        :noop  ;; :off and :unsubscribed
        )))

;; Exit to :done

#_(comment  ;; Do manually


    (process-message {:switchboard/scanner :stock-scanner
                      :switchboard/state :on})

    (process-message {:switchboard/scanner :stock-scanner
                      :switchboard/state :off})


    )


(comment

  ;; TODO

  ;; (require '[com.interrupt.ibgateway.component.ewrapper :as ew])
  ;; {:client #object[com.ib.client.EClientSocket]
  ;;  :publisher #object[clojure.core.async.impl.channels.ManyToManyChannel]}

  (pprint (mount/find-all-states))
  #_("#'com.interrupt.ibgateway.component.repl-server/server"
     "#'com.interrupt.ibgateway.component.ewrapper/ewrapper"
     "#'com.interrupt.ibgateway.component.switchboard.store/conn"
     "#'com.interrupt.ibgateway.core/state")


  ;; [ok] Store
  ;; store/conn


  ;; [ok] EWrapper - connection to TWS
  ;; com.interrupt.ibgateway.component.ewrapper/ewrapper


  ;; TODO -   ;; Brokerage
  ;; subscribe / unsubscribe - with reqid state
  ;; process-message under differing system states
  (def client (com.interrupt.ibgateway.component.ewrapper/ewrapper :client))
  (def publisher (com.interrupt.ibgateway.component.ewrapper/ewrapper :publisher))
  (def publication
    (pub publisher #(:req-id %)))

  (def scanner-subscriptions (brok/scanner-start client publication brok/config))
  (pprint scanner-subscriptions)


  ;; Edgar
  (require '[com.interrupt.edgar.core.edgar :as edg])

  (edg/play-live client ["IBM"])


  ;; HISTORICAL

  ;; (def historical-atom (atom {}))
  ;; (def historical-subscriptions (historical-start 4002 client publication historical-atom))
  ;;
  ;; ;; ====
  ;; ;; Requesting historical data
  ;;
  ;; ;; (def cal (Calendar/getInstance))
  ;; ;; #_(.add cal Calendar/MONTH -6)
  ;; ;;
  ;; ;; (def form (SimpleDateFormat. "yyyyMMdd HH:mm:ss"))
  ;; ;; (def formatted (.format form (.getTime cal)))
  ;; ;;
  ;; ;; (let [contract (doto (Contract.)
  ;; ;;                  (.symbol "TSLA")
  ;; ;;                  (.secType "STK")
  ;; ;;                  (.currency "USD")
  ;; ;;                  (.exchange "SMART")
  ;; ;;                  #_(.primaryExch "ISLAND"))]
  ;; ;;
  ;; ;;   (.reqHistoricalData client 4002 contract formatted "4 W" "1 min" "MIDPOINT" 1 1 nil))
  ;;
  ;;
  ;; (require '[clojure.string :as str])
  ;;
  ;; (pprint (take 6 (->> @historical-atom
  ;;                      (sort-by first)
  ;;                      reverse
  ;;                      (remove (fn [[k {:keys [date] :as v}]]
  ;;                                (or (nil? k)
  ;;                                    (str/starts-with? date "finished-")))))))
  ;;
  ;; (def historical-final (->> @historical-atom
  ;;                            (sort-by first)
  ;;                            (remove (fn [[k {:keys [date] :as v}]]
  ;;                                      (or (nil? k)
  ;;                                          (str/starts-with? date "finished-"))))))
  ;;
  ;;
  ;; (pprint (take 7 historical-final))
  ;;
  ;; ;; 1. write to edn
  ;; #_(spit "tesla-historical-20170901-20170915.edn" @historical-atom)
  ;; (spit "tesla-historical-20170819-20170829.edn" (pr-str historical-final))
  ;;
  ;; ;; 2. write to json
  ;; (require '[clojure.data.json :as json])
  ;; (spit "tesla-historical-20170819-20170829.json" (json/write-str historical-final))


  (pprint high-opt-imp-volat)
  (pprint high-opt-imp-volat-over-hist)
  (pprint hot-by-volume)
  (pprint top-volume-rate)
  (pprint hot-by-opt-volume)
  (pprint opt-volume-most-active)
  (pprint combo-most-active)
  (pprint most-active-usd)
  (pprint hot-by-price)
  (pprint top-price-range)
  (pprint hot-by-price-range)

  ;; (ei/scanner-unsubscribe 1 client)
  ;; (ei/scanner-unsubscribe 2 client)
  ;; (ei/scanner-unsubscribe 3 client)
  ;; (ei/scanner-unsubscribe 4 client)
  ;; (ei/scanner-unsubscribe 5 client)
  ;; (ei/scanner-unsubscribe 6 client)
  ;; (ei/scanner-unsubscribe 7 client)
  ;; (ei/scanner-unsubscribe 8 client)
  ;; (ei/scanner-unsubscribe 9 client)
  ;; (ei/scanner-unsubscribe 10 client)
  ;; (ei/scanner-unsubscribe 11 client)

  ;; (def ss (let [scan-names (->> config :scanners (map :scan-name))
  ;;               scan-subsets #spy/d (map (fn [sname]
  ;;                                          (->> @scanner-subscriptions
  ;;                                               (filter (fn [e] (= (::scan-name e) sname)))
  ;;                                               first ::scan-value vals (map :symbol)
  ;;                                               (fn [e] {sname e}))))]
  ;;           scan-subsets))


  (def sone (set (map :symbol (vals @high-opt-imp-volat))))
  (def stwo (set (map :symbol (vals @high-opt-imp-volat-over-hist))))
  (def s-volatility (cs/intersection sone stwo))  ;; OK

  (def sthree (set (map :symbol (vals @hot-by-volume))))
  (def sfour (set (map :symbol (vals @top-volume-rate))))
  (def sfive (set (map :symbol (vals @hot-by-opt-volume))))
  (def ssix (set (map :symbol (vals @opt-volume-most-active))))
  (def sseven (set (map :symbol (vals @combo-most-active))))
  (def s-volume (cs/intersection sthree sfour #_sfive #_ssix #_sseven))

  (def seight (set (map :symbol (vals @most-active-usd))))
  (def snine (set (map :symbol (vals @hot-by-price))))
  (def sten (set (map :symbol (vals @top-price-range))))
  (def seleven (set (map :symbol (vals @hot-by-price-range))))
  (def s-price-change (cs/intersection seight snine #_sten #_seleven))

  (cs/intersection sone stwo snine)
  (cs/intersection sone stwo seleven)


  (def intersection-subsets
    (filter (fn [e] (> (count e) 1))
            (cmb/subsets [{:name "one" :val sone}
                          {:name "two" :val stwo}
                          {:name "three" :val sthree}
                          {:name "four" :val sfour}
                          {:name "five" :val sfive}
                          {:name "six" :val ssix}
                          {:name "seven" :val sseven}
                          {:name "eight" :val seight}
                          {:name "nine" :val snine}
                          {:name "ten" :val sten}
                          {:name "eleven" :val seleven}])))

  (def sorted-intersections
    (sort-by #(count (:intersection %))
             (map (fn [e]
                    (let [result (apply cs/intersection (map :val e))
                          names (map :name e)]
                      {:names names :intersection result}))
                  intersection-subsets)))

  (def or-volatility-volume-price-change
    (filter (fn [e]
              (and (> (count (:intersection e)) 1)
                   (some #{"one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten" "eleven"} (:names e))
                   #_(or (some #{"one" "two"} (:names e))
                         (some #{"three" "four" "five" "six" "seven"} (:names e))
                         (some #{"eight" "nine" "ten" "eleven"} (:names e)))))
            sorted-intersections))

  (clojure.pprint/pprint intersection-subsets)
  (clojure.pprint/pprint sorted-intersections)
  (clojure.pprint/pprint or-volatility-volume-price-change)

  )
