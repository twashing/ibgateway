(ns com.interrupt.ibgateway.component.switchboard
  (:require [clojure.core.async :refer [chan >! <! close! merge go go-loop pub sub unsub-all sliding-buffer
                                        mult tap pipeline]]
            [clojure.set :as cs]
            [clojure.math.combinatorics :as cmb]
            [clojure.tools.logging :refer [debug info warn error]]
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
            [com.interrupt.ibgateway.component.ewrapper-impl :as ei]
            [com.interrupt.ibgateway.component.switchboard.store :as store]
            [com.interrupt.ibgateway.component.switchboard.brokerage :as brok]
            [com.interrupt.edgar.core.tee.live :as tlive]
            [com.interrupt.edgar.ib.market :as market]
            [com.interrupt.edgar.ib.handler.live :as live]

            [mount.core :refer [defstate] :as mount]
            [overtone.at-at :refer :all]
            [com.interrupt.ibgateway.component.ewrapper :as ew]
            [com.interrupt.ibgateway.component.processing-pipeline :as pp]))


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
                                                            (info "topic partitions assigned:" topic-partitions))
                                                          (fn [topic-partitions]
                                                            (info "topic partitions revoked:" topic-partitions)))
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
        (info "Partitions subscribed to:" (partition-subscriptions c))

        (loop [records nil]

          (if (true? @running)

            (let [cr (poll! c)


                  ;;a naive transducer, written the long way
                  filter-xf (filter (fn [cr] (= (:key cr) :inconceivable)))
                  ;;a naive transducer for viewing the values, again long way
                  value-xf (map (fn [cr] (:value cr)))
                  ;;more misguided transducers
                  inconceivable-transduction (comp filter-xf value-xf)]


              (info "Record count:" (record-count cr))
              (info "Records by topic:" (records-by-topic cr topic))
              ;;The source data is a seq, be careful!
              (info "Records from a topic that doesn't exist:" (records-by-topic cr "no-one-of-consequence"))
              (info "Records by topic partition:" (records-by-topic-partition cr topic 0))
              ;;The source data is a list, so no worries here....
              (info "Records by a topic partition that doesn't exist:" (records-by-topic-partition cr "no-one-of-consequence" 99))
              (info "Topic Partitions in the result set:" (record-partitions cr))
              (clojure.pprint/pprint (into [] inconceivable-transduction cr))
                                        ;(info "Now just the values of all distinct records:")
              (info "Put all the records into a vector (calls IReduceInit):" (into [] cr))

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

#_(comment  ;; Workbench to consume from Kafka

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

(comment  ;; DB workbench

  ;; Schema

  ;; Examples

  ;; HISTORICAL entry
  {:open 313.97
   :date "20180104  20:17:00"
   :req-id 4002
   :topic :historical-data
   :close 313.95
   :has-gaps false
   :volume 21
   :high 314.04
   :wap 314.01
   :count 19
   :low 313.95}

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

  ;; Live data keys
  :total-volume
  :last-trade-size
  :vwap
  :last-trade-price

  (def historical-schema
    [{:db/ident :historical/open
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/float}

     {:db/ident :historical/date
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/instant}

     {:db/ident :historical/req-id
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}

     {:db/ident :historical/topic
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/keyword}

     {:db/ident :historical/close
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/double}

     {:db/ident :historical/has-gaps
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/boolean}

     {:db/ident :historical/volume
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}

     {:db/ident :historical/high
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/double}

     {:db/ident :historical/low
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/double}

     {:db/ident :historical/wap
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/double}

     {:db/ident :historical/count
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}])

  (def live-schema
    [{:db/ident :live/tick-price-tickerId
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}

     {:db/ident :live/tick-price-field
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}

     {:db/ident :live/tick-price-price
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/double}

     {:db/ident :live/tick-price-canAutoExecute
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}


     {:db/ident :live/tick-size-tickerId
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}

     {:db/ident :live/tick-size-field
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}

     {:db/ident :live/tick-size-size
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}


     {:db/ident :live/tick-string-tickerId
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}

     {:db/ident :live/tick-price-tickType
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}

     {:db/ident :live/tick-price-value
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/string}])



  (def schema-transact-result (d/transact conn scanner-schema))
  (def schema-historical-result (d/transact conn historical-schema))
  (def schema-live-result (d/transact conn live-schema))

  ;; #inst "20180104  20:17:00"
  (def historical-input [{:historical/open 313.97
                          :historical/date #inst "2018-01-04T20:17:00Z"
                          :historical/req-id 4002
                          :historical/topic :historical-data
                          :historical/close 313.95
                          :historical/has-gaps false
                          :historical/volume 21
                          :historical/high 314.04
                          :historical/wap 314.01
                          :historical/count 19
                          :historical/low 313.95}])

  (def result (d/transact conn historical-input))

  (pprint (d/q '[:find (pull ?e [*])
                 :where
                 [?e :historical/topic :historical-data]]
               (d/db conn)))

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
      (info "Message: " msg " / etal: " etal)
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

    (process-message {:writer info} [{:stock-scanner/state :on}])



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
                                                            (info "recieved CALLED: one: " one " / two: " two)
                                                            (assoc one :received :received))
                                                :on (fn [one two]
                                                      (info "on CALLED: one: " one " / two: " two)
                                                      (assoc one :on :on))
                                                :subscribed (fn [one two]
                                                              (info "subscribed CALLED: one: " one " / two: " two)
                                                              (assoc one :subscribed :subscribed))
                                                :done (fn [one two]
                                                        (info "done CALLED: one: " one " / two: " two)
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


  ;; SCANNER
  ;; TODO -   ;; Brokerage
  ;; subscribe / unsubscribe - with reqid state
  ;; process-message under differing system states
  (def client (com.interrupt.ibgateway.component.ewrapper/ewrapper :client))
  (def publisher (com.interrupt.ibgateway.component.ewrapper/ewrapper :publisher))
  (def publication
    (pub publisher #(:req-id %)))

  (def scanner-subscriptions (brok/scanner-start client publication brok/config))
  (pprint scanner-subscriptions)



  ;; LIVE
  (require '[com.interrupt.edgar.core.edgar :as edg]
           '[com.interrupt.edgar.ib.market :as mkt])

  (def client (com.interrupt.ibgateway.component.ewrapper/ewrapper :client))
  (def publisher (com.interrupt.ibgateway.component.ewrapper/ewrapper :publisher))
  (def ewrapper-impl (com.interrupt.ibgateway.component.ewrapper/ewrapper :ewrapper-impl))
  (def publication
    (pub publisher #(:req-id %)))

  (let [stock-name "IBM"
        stream-live (fn [event-name result]
                      (info :stream-live (str "... stream-live > event-name[" event-name
                                              "] response[" result "]")))]

    ;; TODO - replace this with analogy to brok/scanner-start
    (edg/play-live client [stock-name] [(partial tlive/tee-fn stream-live stock-name)])
    #_(brok/live-subscribe req-id client stock-name))

  (pprint mkt/kludge)
  (mkt/cancel-market-data client 0)


  ;; STATE
  (mount/find-all-states)
  #_("#'com.interrupt.ibgateway.component.repl-server/server" "#'com.interrupt.ibgateway.component.ewrapper/ewrapper" "#'com.interrupt.ibgateway.component.switchboard.store/conn" "#'com.interrupt.ibgateway.core/state")

  (mount/start #'com.interrupt.ibgateway.component.ewrapper/ewrapper)
  (mount/stop #'com.interrupt.ibgateway.component.ewrapper/ewrapper)


  ;; LOCAL data loading
  (def input (read-string (slurp "live.1.clj")))
  (doseq [{:keys [dispatch] :as ech} input]

    (case dispatch
      :tick-string (as-> ech e
                     (dissoc e :dispatch)
                     (vals e)
                     (apply #(.tickString ewrapper-impl %1 %2 %3) e))
      :tick-price (as-> ech e
                    (dissoc e :dispatch)
                    (vals e)
                    (apply #(.tickPrice ewrapper-impl %1 %2 %3 %4) e))
      :tick-size (as-> ech e
                   (dissoc e :dispatch)
                   (vals e)
                   (apply #(.tickSize ewrapper-impl %1 %2 %3) e))))

  #_(.tickPrice ewrapper-impl 0 0 1.2 0)
  #_(.tickSize ewrapper-impl 0 1 2)
  #_(.tickString ewrapper-impl 0 1 "Foo")


  ;; LIVE output
  ;; ...

  ;; HISTORICAL
  (defn consume-subscriber-historical [historical-atom subscriber-chan]
    (go-loop [r1 nil]

      (let [{:keys [req-id date open high low close volume count wap has-gaps] :as val} r1]
        (swap! historical-atom assoc date val))
      (recur (<! subscriber-chan))))

  (defn historical-start [req-id client publication historical-atom]

    (let [subscriber (chan)]
      (ei/historical-subscribe req-id client)
      (sub publication req-id subscriber)
      (consume-subscriber-historical historical-atom subscriber)))

  (defn historical-stop [])


  (def client (com.interrupt.ibgateway.component.ewrapper/ewrapper :client))
  (def publisher (com.interrupt.ibgateway.component.ewrapper/ewrapper :publisher))
  (def publication
    (pub publisher #(:req-id %)))

  (def historical-atom (atom {}))
  (def historical-subscriptions (historical-start 4002 client publication historical-atom))


  ;; HISTORICAL output
  #_([nil nil]
     ["20171121  20:14:00"
      {:open 316.91,
       :date "20171121  20:14:00",
       :req-id 4002,
       :topic :historical-data,
       :close 316.77,
       :has-gaps false,
       :volume 47,
       :high 316.91,
       :wap 316.771,
       :count 26,
       :low 316.7}]
     ["20171116  16:23:00"
      {:open 314.74,
       :date "20171116  16:23:00",
       :req-id 4002,
       :topic :historical-data,
       :close 314.88,
       :has-gaps false,
       :volume 80,
       :high 314.99,
       :wap 314.85,
       :count 49,
       :low 314.6}]
     ["20180104  20:17:00"
      {:open 313.97,
       :date "20180104  20:17:00",
       :req-id 4002,
       :topic :historical-data,
       :close 313.95,
       :has-gaps false,
       :volume 21,
       :high 314.04,
       :wap 314.01,
       :count 19,
       :low 313.95}])




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
  ;;               scan-subsets (map (fn [sname]
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

(comment


  ;; LIVE
  (require '[com.interrupt.edgar.core.edgar :as edg]
           '[com.interrupt.edgar.ib.market :as mkt]
           '[com.interrupt.edgar.ib.handler.live :as live])

  (def client (com.interrupt.ibgateway.component.ewrapper/ewrapper :client))
  (def publisher (com.interrupt.ibgateway.component.ewrapper/ewrapper :publisher))
  (def ewrapper-impl (com.interrupt.ibgateway.component.ewrapper/ewrapper :ewrapper-impl))
  (def publication
    (pub publisher #(:topic %)))


  (require '[datomic.api :as d])
  (def uri "datomic:mem://ibgateway")

  (def rdelete (d/delete-database uri))
  (def rcreate (d/create-database uri))
  (def conn (d/connect uri))
  (def db (d/db conn))

  (def live-schema
    [{:db/ident :live/tick-price-tickerId
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}

     {:db/ident :live/tick-price-field
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}

     {:db/ident :live/tick-price-price
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/double}

     {:db/ident :live/tick-price-canAutoExecute
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}



     {:db/ident :live/tick-size-tickerId
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}

     {:db/ident :live/tick-size-field
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}

     {:db/ident :live/tick-size-size
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}



     {:db/ident :live/tick-string-tickerId
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}

     {:db/ident :live/tick-price-tickType
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/long}

     {:db/ident :live/tick-price-value
      :db/cardinality :db.cardinality/one
      :db/valueType :db.type/string}])

  (def schema-live-result (d/transact conn live-schema))


  ;; TODO
  ;; 1. Get more data with setting: 233 (RT Volume (Time & Sales))
  ;; 2. dispatch on the different types of tickPrice + tickSize (reference: https://interactivebrokers.github.io/tws-api/tick_types.html)
  ;;   Details we need to collect
  ;;
  ;;   :last-trade-price
  ;;   :last-trade-size
  ;;   :total-volume
  ;;   ~ :last-trade-time
  ;;   ~ :vwap


  ;; GET the data
  (let [stock-name "TSLA"
        stream-live (fn stream-live [event-name result]
                      (info :stream-live (str "... stream-live > event-name[" event-name
                                              "] response[" result "]")))
        options-datomic {:tick-list (ref [])
                         :stock-match {:symbol stock-name :ticker-id-filter 0}}

        datomic-feed-handler (fn [options evt]

                               ;; (info "datomic-feed-handler: " options " / " evt)
                               (spit "live.5.edn" evt :append true)

                               ;; Example datomic inputs
                               #_(def live-input-size [{:live/tick-size-tickerId 0
                                                        :live/tick-size-field 433
                                                        :live/tick-size-size 50}])

                               #_(def live-input-string [{:live/tick-string-tickerId 0
                                                          :live/tick-price-tickType 43
                                                          :live/tick-price-value ";1;2;3;4"}])

                               #_(def live-input-price [{:live/tick-price-tickerId 0
                                                         :live/tick-price-field 433
                                                         :live/tick-price-price 62.45
                                                         :live/tick-price-canAutoExecute 1}])

                               #_(def result (d/transact conn live-input-string)))]

    (market/subscribe-to-market publisher (partial datomic-feed-handler options-datomic))

    (edg/play-live client publisher [stock-name] [(partial tlive/tee-fn stream-live stock-name)]))m

  (mkt/cancel-market-data client 0)



  ;; PLAY the data
  (require '[overtone.at-at :refer :all]
           '[com.interrupt.edgar.ib.handler.live :refer [feed-handler] :as live])

  (def publisher (com.interrupt.ibgateway.component.ewrapper/ewrapper :publisher))
  (def ewrapper-impl (com.interrupt.ibgateway.component.ewrapper/ewrapper :ewrapper-impl))
  (def publication
    (pub publisher #(:topic %)))


  (let [stock-name "TSLA"

        output-fn (fn [event-name result]
                    (info :stream-live (str "... stream-live > event-name[" event-name "] response[" result "]")))

        options {:tick-list (ref [])
                 :tee-list [(partial tlive/tee-fn output-fn stock-name)]
                 :stock-match {:symbol "TSLA" :ticker-id-filter 0}}]

    (market/subscribe-to-market publisher (partial feed-handler options)))

  (def my-pool (mk-pool))
  (def input-source (atom (read-string (slurp "live.3.edn"))))
  (def scheduled-fn (every 1000
                           (fn []
                             (let [{:keys [topic] :as ech} (first @input-source)]

                               ;; (info "Sanity Check: " ech)
                               (case topic
                                 :tick-string (as-> ech e
                                                (dissoc e :topic)
                                                (vals e)
                                                (apply #(.tickString ewrapper-impl %1 %2 %3) e))
                                 :tick-price (as-> ech e
                                               (dissoc e :topic)
                                               (vals e)
                                               (apply #(.tickPrice ewrapper-impl %1 %2 %3 %4) e))
                                 :tick-size (as-> ech e
                                              (dissoc e :topic)
                                              (vals e)
                                              (apply #(.tickSize ewrapper-impl %1 %2 %3) e))))

                             (swap! input-source #(rest %)))
                           my-pool))
  (stop scheduled-fn)



  ;; STATE
  (mount/find-all-states)

  (mount/start #'com.interrupt.ibgateway.component.ewrapper/ewrapper)
  (mount/stop #'com.interrupt.ibgateway.component.ewrapper/ewrapper)


  (defn take-and-print [channel]
    (go-loop []
      (info (<! channel))
      (recur)))


  (def subscriber (chan))
  (sub publication :tick-price subscriber)
  (sub publication :tick-size subscriber)
  (sub publication :tick-string subscriber)
  (take-and-print subscriber)

  ;; LOCAL data loading
  (def input (read-string (slurp "live.1.clj")))
  (doseq [{:keys [dispatch] :as ech} input]

    #_(info)
    #_(info "Sanity Check: " ech)
    (case dispatch
      :tick-string (as-> ech e
                     (dissoc e :dispatch)
                     (vals e)
                     (apply #(.tickString ewrapper-impl %1 %2 %3) e))
      :tick-price (as-> ech e
                    (dissoc e :dispatch)
                    (vals e)
                    (apply #(.tickPrice ewrapper-impl %1 %2 %3 %4) e))
      :tick-size (as-> ech e
                   (dissoc e :dispatch)
                   (vals e)
                   (apply #(.tickSize ewrapper-impl %1 %2 %3) e)))))

(comment   ;; PLAY the data

  (mount/stop #'ew/ewrapper #'pp/processing-pipeline)
  (mount/start #'ew/ewrapper #'pp/processing-pipeline)
  (mount/find-all-states)


  (do

    (mount/stop #'ew/ewrapper #'pp/processing-pipeline)
    (mount/start #'ew/ewrapper #'pp/processing-pipeline)

    (let [ewrapper-impl (ew/ewrapper :ewrapper-impl)
          my-pool (mk-pool)
          input-source (atom (read-string (slurp "live.4.edn")))
          string-count (atom 0)
          consume-fn (fn []
                       (let [{:keys [topic] :as ech} (first @input-source)]

                         (case topic
                           :tick-string (do
                                          #_(when (< @string-count 600)
                                            (info "Sanity check" (swap! string-count inc)topic))
                                          (as-> ech e
                                            (dissoc e :topic)
                                            (vals e)
                                            (apply #(.tickString ewrapper-impl %1 %2 %3) e)))
                           :tick-price (as-> ech e
                                         (dissoc e :topic)
                                         (vals e)
                                         (apply #(.tickPrice ewrapper-impl %1 %2 %3 %4) e))
                           :tick-size (as-> ech e
                                        (dissoc e :topic)
                                        (vals e)
                                        (apply #(.tickSize ewrapper-impl %1 %2 %3) e))))

                       (swap! input-source #(rest %)))]

      (def scheduled-fn (every 10
                               consume-fn
                               my-pool))))

  (stop scheduled-fn)


  #_(let [;; ch (:strategy-stochastic-oscillator-ch pp/processing-pipeline)
          ;; ch (:strategy-on-balance-volume-ch pp/processing-pipeline)
        ch (:moving-averages-strategy-OUT pp/processing-pipeline)]

      (go-loop [r (<! ch)]
        (info r)
        (when r
          (recur (<! ch))))))

(comment

  (def tick-list (atom []))
  (def options {:tick-list tick-list})
  (def evt1 {:value ";0;1522337866199;67085;253.23364232;true"
             :topic "tickString"
             :ticker-id 0})
  (def evt2 {:value "255.59;1;1522337865948;67077;253.23335428;true"
             :topic "tickString"
             :ticker-id 0})

  (live/handle-tick-string options evt1))

;; :value       ;0;1522337866199;67085;253.23364232;true
;; :value 255.59;1;1522337865948;67077;253.23335428;true
