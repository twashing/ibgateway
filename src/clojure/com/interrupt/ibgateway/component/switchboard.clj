(ns com.interrupt.ibgateway.component.switchboard
  (:require [clojure.core.async :refer [chan >! <! merge go go-loop pub sub unsub-all sliding-buffer]]
            [com.stuartsierra.component :as component]
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
            [com.interrupt.ibgateway.component.switchboard.store :as store]))


(def topic-scanner-command "scanner-command")
(def topic-scanner-command-result "scanner-command-result")
(def topic-scanner "scanner")
(def topic-filtered-stocks "filtered-stocks")
(def topic-stock-command "stock-command")

(defn setup-topics [zookeeper-url]

  (def zk-utils (client/make-zk-utils {:servers [zookeeper-url]} false))
  (doseq [topic [topic-scanner-command
                 topic-scanner-command-result
                 topic-scanner
                 topic-filtered-stocks
                 topic-stock-command]
          :let [partition-count 1]]
    (topics/create-topic! zk-utils topic partition-count))

  (topics/all-topics zk-utils))

(defrecord Switchboard [zookeeper-url kafka-url]
  component/Lifecycle
  (start [component]

    (setup-topics zookeeper-url)
    (assoc component :status :up))
  (stop [{server :server :as component}]
    (assoc component :status :down)))

(defn new-switchboard [zookeeper-url kafka-url]
  (map->Switchboard {:zookeeper-url zookeeper-url
                     :kafka-url kafka-url }) )


;; ==
(def running (atom true))

(defn consume-topic-example []

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

  (swap! running (constantly false))
  @running


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

(comment  ;; DB workbench


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

;; TWS Connection

(comment  ;; State Machine

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

(comment  ;; Automat directly

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


(defn subscribed? [conn scan instrument]

  (d/q '[:find (pull ?e [{:switchboard/state [*]}
                         :switchboard/instrument])
         :in $ ?scan ?instrument
         :where
         [?e :switchboard/scanner ?scan]
         [?e :switchboard/state :on]
         [?e :switchboard/instrument ?instrument]
         (d/db conn) scan instrument]))

(defn subscribe-to-scan [conn scanner]

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

(defn unsubscribe-from-scan [scanner] 3)

(defmulti process-message (fn [{scanner :switchboard/scanner}] scanner))

(defmethod process-message :stock-scanner [{:keys [:switchboard/scanner :switchboard/state]}]


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

(comment  ;; Do manually


  (process-message {:switchboard/scanner :stock-scanner
                    :switchboard/state :on})

  (process-message {:switchboard/scanner :stock-scanner
                    :switchboard/state :off})


  )


(comment

  (require '[mount.core :refer [defstate] :as mount])

  (def uri "datomic:mem://ibgateway")
  (defstate conn
    :start (store/initialize-store store/schema uri)
    :stop (store/teardown-store uri))


  ;; com.interrupt.ibgateway.component.ewrapper/ewrapper

  (mount/start)
  (mount/stop)


  ;; TODO
  ;; set up channel stream
  ;; initialize store
  ;; process-message under differing system states

  ;; (require '[com.interrupt.ibgateway.component.ewrapper :as ew])
  ;; {:client #object[com.ib.client.EClientSocket]
  ;;  :publisher #object[clojure.core.async.impl.channels.ManyToManyChannel]}


  )
