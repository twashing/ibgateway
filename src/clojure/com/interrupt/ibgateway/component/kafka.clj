(ns com.interrupt.ibgateway.component.kafka
  (:require [com.stuartsierra.component :as component]
            [franzy.clients.producer.client :as producer]
            [franzy.clients.consumer.client :as consumer]
            [franzy.clients.producer.protocols :refer :all]
            [franzy.clients.consumer.protocols :refer :all]
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
