(ns com.interrupt.ibgateway.component.switchboard.store
  (:require [datomic.api :as d]))


(def schema
  [{:db/ident :switchboard/scanner
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "A simple switch on whether or not, to scan the stock-market'"}

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


(comment  ;; DB workbench

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
  (def schema
    [;; used for >  STOCK SCANNER | STOCK | STOCK HISTORICAL
     {:db/ident :switchboard/scanner   ;; :stock-scanner/state
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/one
      :db/doc "A simple switch on whether or not, to scan the stock-market'"}

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

  (def schema-transact-result (d/transact conn schema))


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

