(ns com.interrupt.ibgateway.component.account.common
  (:require [clojure.set :as cs]))


(def reqids (atom []))
(def id-largest 100)


;; Message structure
;;
;; (availableids [2 3])   ;; [(2 3) (1 4 5 6 7 8 9 10)]
;; (availableids [6 9])   ;; [(6 9) (1 2 3 4 5 7 8 10)]
;; (availableids [2 1 6 9])   ;; [(1 2 6 9) (3 4 5 7 8 10)]
(defn availableids [scanner-subscriptions]
  (let [existing-ids (sort scanner-subscriptions)
        first-id (first existing-ids)
        contiguous-numbers (take 10 (range 1 id-largest))
        aids (sort (cs/difference (into #{} contiguous-numbers)
                                  (into #{} existing-ids)))]

    [existing-ids aids]))


;; (next-reqid [])
;; (next-reqid [2 1 4 6 9])  ;; 3
;; (next-reqid [2 1 3 6 9])  ;; 4
(defn next-reqid
  ([] (next-reqid reqids))
  ([rids]
   (cond (->> rids
              ((juxt nil? empty?))
              (every? (comp not false?))) 1
         :else (let [ids (availableids rids)]
                 (if-not (empty? ids)
                   (-> ids second first)
                   1)))))

(defn next-reqid! []
  (let [rid (next-reqid @reqids)]
    (swap! reqids conj rid)
    rid))

(defn release-reqid! [rid]
  (swap! reqids (fn [a] (remove #(= rid %) a))))
