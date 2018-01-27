(ns com.interrupt.ibgateway.component.kafka
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
            [franzy.clients.consumer.defaults :as cd]))


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

(defrecord Kafka [zookeeper-url kafka-url]
  component/Lifecycle
  (start [component]

    (setup-topics zookeeper-url)
    (assoc component :status :up))
  (stop [{server :server :as component}]
    (assoc component :status :down)))

(defn new-kafka [zookeeper-url kafka-url]
  (map->Kafka {:zookeeper-url zookeeper-url
               :kafka-url kafka-url }) )


;; ==
(def running (atom true))

(defn foo []

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
            ;; ** rename to 'switchboard'
            ;; ** Put the result onto a
            ;;   channel?
            ;;   atom?
            ;;   embedded db
            ;;     Apache derby
            ;;     H2
            ;;     FleetDB
            ;;     Datomic

            ;; Commands for: scanner, stock, historical

            ;; Am I on? (input from kafka)
            ;; Am I subscribed? (state from client)

            ;; Call ewrapper-impl/scanner-subscribe
            ;;   store request IDs (w/ associated STOCK or SCAN)
            ;;   lookup request IDs

            (recur cr))

          (clear-subscriptions! c))))))

(comment


  ;; schema

  {:stock-scanner/state :on}
  {:stock-price/state :on
   :stock-price/instrument :TSLA}
  {:stock-historical/state :on
   :stock-historical/instrument :TSLA}





  (require '[datomic.api :as d])
  (def uri "datomic:mem://ibgateway")
  (def result (d/create-database uri))
  (def conn (d/connect uri))
  (def db (d/db conn))

  (def scanner-schema
    [;; STOCK SCANNER
     {:db/ident :stock-scanner/state
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/one
      :db/doc "A simple switch on whether or not, to scan the stock-market'"}


     ;; STOCK
     {:db/ident :stock-price/state
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/one
      :db/doc "A switch on what stock price the system is tracking"}

     {:db/ident :stock-price/instrument
      :db/valueType :db.type/keyword
      :db/cardinality :db.cardinality/one
      :db/doc "The stock symbol being tracked"}


     ;; STOCK HISTORICAL
     {:db/ident :stock-historical/state
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/one
      :db/doc "A switch on what historical stock data is being fetched"}

     {:db/ident :stock-historical/instrument
      :db/valueType :db.type/keyword
      :db/cardinality :db.cardinality/one
      :db/doc "The stock symbol's historical data being fetched"}


     {:db/ident :stock-scanner-state/on}
     {:db/ident :stock-scanner-state/off}

     {:db/ident :stock-price-state/on}
     {:db/ident :stock-price-state/off}

     {:db/ident :stock-historical-state/on}
     {:db/ident :stock-historical-state/off}])

  (def scanner-on [{:stock-scanner/state :stock-scanner-state/on}])
  (def scanner-off [{:stock-scanner/state :stock-scanner-state/off}])

  (def all-on [{:stock-scanner/state :stock-scanner-state/on}

               {:stock-price/state :stock-price-state/on
                :stock-price/instrument :TSLA}

               {:stock-historical/state :stock-historical-state/on
                :stock-historical/instrument :TSLA}])


  (def db1 (d/transact conn scanner-schema))
  (def db2 (d/transact conn all-on))

  (pprint (d/q '[:find (pull ?e [{:stock-scanner/state [*]}])
                 :where
                 [?e :stock-scanner/state]]
               (:db-after @db2)))

  (pprint (d/q '[:find (pull ?e [{:stock-price/state [*]}
                                 :stock-price/instrument])
                 :where
                 [?e :stock-price/state]]
               (:db-after @db2)))

  (pprint (d/q '[:find (pull ?e [{:stock-historical/state [*]}
                                 :stock-historical/instrument])
                 :where
                 [?e :stock-historical/state]]
               (:db-after @db2)))


  (foo)

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
