(ns com.interrupt.ibgateway.component.switchboard.brokerage
  (:require [clojure.core.match :refer [match]]
            [clojure.string :as str]
            [clojure.set :as cs]
            [clojure.spec.alpha :as s]
            
            [clojure.core.async :refer [chan >! <! merge go go-loop pub sub unsub-all sliding-buffer]]
            [com.interrupt.ibgateway.component.ewrapper-impl :as ei]))

;; TODO - replace with DB
(def config
  {:stocks {:default-instrument "STK"
            :default-location "STK.US.MAJOR"}

   :scanners [{:scan-name "HIGH_OPT_IMP_VOLAT"
               :scan-value {}
               :tag :volatility}
              {:scan-name "HIGH_OPT_IMP_VOLAT_OVER_HIST"
               :scan-value {}
               :tag :volatility}
              {:scan-name "HOT_BY_VOLUME"
               :scan-value {}
               :tag :volume}
              {:scan-name "TOP_VOLUME_RATE"
               :scan-value {}
               :tag :volume}
              {:scan-name "HOT_BY_OPT_VOLUME"
               :scan-value {}
               :tag :volume}
              {:scan-name "OPT_VOLUME_MOST_ACTIVE"
               :scan-value {}
               :tag :volume}
              {:scan-name "COMBO_MOST_ACTIVE"
               :scan-value {}
               :tag :volume}
              {:scan-name "MOST_ACTIVE_USD"
               :scan-value {}
               :tag :price}
              {:scan-name "HOT_BY_PRICE"
               :scan-value {}
               :tag :price}
              {:scan-name "TOP_PRICE_RANGE"
               :scan-value {}
               :tag :price}
              {:scan-name "HOT_BY_PRICE_RANGE"
               :scan-value {}
               :tag :price}]})

#_(s/def ::reqid pos-int?)
#_(s/def ::subscription-element (s/keys :req [::reqid]))
#_(s/def ::subscriptions (s/coll-of ::subscription-element))

(defn scannerid-availableid-pairs [scanner-subscriptions]
  (let [scannerids (sort (map ::reqid scanner-subscriptions))
        scannerids-largest (last scannerids)
        first-id (first scannerids)
        contiguous-numbers (take 10 (range 1 scannerids-largest))
        availableids (sort (cs/difference (into #{} contiguous-numbers)
                                          (into #{} scannerids)))]

    [scannerids availableids]))

;; TODO - put scanner-subscriptions in DB. Structure is below

;; {::reqid next-id
;;  ::scan-name scan-name
;;  ::scan-value {}
;;  ::tag tag}

;; {:com.interrupt.ibgateway.component.switchboard.brokerage/reqid 11
;;  :com.interrupt.ibgateway.component.switchboard.brokerage/scan-name "HOT_BY_PRICE_RANGE"
;;  :com.interrupt.ibgateway.component.switchboard.brokerage/scan-value {}
;;  :com.interrupt.ibgateway.component.switchboard.brokerage/tag :price}

;; {:com.interrupt.ibgateway.component.switchboard.brokerage/reqid 7
;;  :com.interrupt.ibgateway.component.switchboard.brokerage/scan-name "COMBO_MOST_ACTIVE"
;;  :com.interrupt.ibgateway.component.switchboard.brokerage/scan-value {}
;;  :com.interrupt.ibgateway.component.switchboard.brokerage/tag :volume}

(defn next-reqid [scanner-subscriptions]
  (match [scanner-subscriptions]
         [nil] 1
         [[]] 1
         :else (let [[scannerids availableids] (scannerid-availableid-pairs scanner-subscriptions)]
                 (if-not (empty? availableids)
                   (first availableids)
                   (+ 1 (last scannerids))))))

(comment

  (next-reqid [])
  (scannerid-availableid-pairs []))

#_(s/fdef next-reqid
        :args (s/cat :subscriptions ::subscriptions)
        :ret number?
        :fn (s/and

             ;; Handles nil and empty sets
             #(if (empty? (-> % :args :subscriptions))
                (= 1 (:ret %))
                (pos-int? (:ret %)))

             ;; Finds the first gap number
             ;; Can be in first position
             ;; Gap can be on left or right side
             (fn [x]
               (let [reqids (sort (map ::reqid (-> x :args :subscriptions)))
                     fid (first reqids)]
                 (match [fid]
                        [nil] 1
                        [(_ :guard #(> % 1))] (= 1 (:ret x))
                        :else (pos-int? (:ret x)))))))

;; TODO - replace with kafka + stream processing asap
(defn top-level-scan-item [scan-name]
  (let [scan-sym (-> scan-name (str/lower-case) (str/replace "_" "-") symbol)]
    (if-let [scan-resolved (resolve scan-sym)]
      scan-resolved
      (intern *ns* scan-sym (atom {})))))

(defn scanner-subscriptions-with-ids [confg scanner-subscriptions]

  (let [scan-types (->> config :scanners (map #(select-keys % [:scan-name :tag])))]

    (reduce (fn [acc {:keys [scan-name tag]}]
              (let [next-id (next-reqid acc)
                    subscription {::reqid next-id
                                  ::scan-name scan-name
                                  ::scan-value {}
                                  ::tag tag}]
                (conj acc subscription)))
            scanner-subscriptions
            scan-types)))

;; TODO - put ranks from scan-atoms, into DB
(defn consume-subscriber [scan-atom subscriber-chan]
  (go-loop [r1 nil]
    (let [{:keys [req-id symbol rank] :as val} (select-keys r1 [:req-id :symbol :rank])]
      (if (and r1 rank)
        (swap! scan-atom assoc rank val)))

    (recur (<! subscriber-chan))))

(defn scanner-start [client publication config]

  (let [default-instrument (-> config :stocks :default-instrument)
        default-location (-> config :stocks :default-location)
        scanner-subscriptions-init []
        scanner-subscriptions (scanner-subscriptions-with-ids config scanner-subscriptions-init)]

    (doseq [{:keys [::reqid ::scan-name ::tag] :as val} scanner-subscriptions
            :let [subscriber (chan)]]

      ;; TODO - replace with kafka + stream processing asap
      (let [scan-var (top-level-scan-item scan-name)
            scan-atom (var-get scan-var)]

        (ei/scanner-subscribe reqid client default-instrument default-location scan-name)
        (sub publication reqid subscriber)

        ;; TODO - Simply forward the data to "market-scanner"
        ;; New entry point is here:


        ;; com.interrupt.edgar.service/get-streaming-stock-data		< com.interrupt.edgar.core.tee.live/tee-fn
        ;;
        ;; com.interrupt.edgar.core.edgar/play-live
        ;; com.interrupt.edgar.ib.market/subscribe-to-market
        ;; com.interrupt.edgar.ib.market/request-market-data
        ;;
        ;; < com.interrupt.edgar.core.tee.live/tee-fn
        ;; com.interrupt.edgar.core.analysis.lagging/simple-moving-average
        ;; com.interrupt.edgar.core.analysis.lagging/exponential-moving-average
        ;; ...
        ;; < manage-orders
        ;; < (output-fn) com.interrupt.edgar.service/stream-live


        (consume-subscriber scan-atom subscriber)))

    scanner-subscriptions))

(defn scanner-stop [])
