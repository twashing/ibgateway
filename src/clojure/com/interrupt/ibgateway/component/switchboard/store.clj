(ns com.interrupt.ibgateway.component.switchboard.store
  (:require [mount.core :refer [defstate] :as mount]
            [datomic.api :as d]))


(def schema
  [{:db/ident :switchboard/scanner
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "The type of connection: market scanner | stock | stock historical"}

   {:db/ident :switchboard/state
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "A simple switch on whether or not, to scan the stock-market'"}

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

(defn initialize-store [schema uri]

  (d/create-database uri)

  (let [conn (d/connect uri)
        schema-transact-result (d/transact conn schema)]
    conn))

(defn teardown-store [uri]

  (d/delete-database uri))


(def uri "datomic:mem://ibgateway")

(defstate conn
  :start (initialize-store schema uri)
  :stop (teardown-store uri))
