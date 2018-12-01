(ns com.interrupt.edgar.core.utils
  (:require [clojure.reflect :refer [reflect]]
            [clojure.string :refer [join]]
            [com.interrupt.edgar.core.analysis.lagging :as lag]))


(defn inspect [obj]
  (let [reflection (reflect obj)
        members (sort-by :name (:members reflection))]
    (println "Class:" (.getClass obj))
    (println "Bases:" (:bases reflection))
    (println "---------------------\nConstructors:")
    (doseq [constructor (filter #(instance? clojure.reflect.Constructor %) members)]
      (println (:name constructor) "(" (join ", " (:parameter-types constructor)) ")"))
    (println "---------------------\nMethods:")
    (doseq [method (filter #(instance? clojure.reflect.Method %) members)]
      (println (:name method) "(" (join ", " (:parameter-types method)) ") ;=>" (:return-type method)))))


(defn sine
  "This is a basic sine equation. Below is a simple Clojure implementation, based on the algebra we've written.

  f(x) = a sin (b(x − c)) + d
  y = a sin (b(x − c)) + d
  y = a sin (b(x − Pi/2)) + d

  f(x) = y
  a - is the amplitude of the wave (makes the wave taller or sharter)
  b - is the horizontal dilation (makes the wave wider or thinner)
  c - Pi/2 , is the horizontal translation (moves the wave left or right)
  d - is the vertical translation (moves the wave up or down)

  Mapping this function over an integer range of -5 to 5, provides the accompanying y values.

  (map #(sine 2 2 % 0) '(-5 -4 -3 -2 -1 0 1 2 3 4 5))
  (-1.0880422217787393 1.9787164932467636 -0.5588309963978519 -1.5136049906158564 1.8185948536513634 -2.4492935982947064E-16
  -1.8185948536513632 1.5136049906158566 0.5588309963978515 -1.9787164932467636 1.0880422217787398)"
  [a b input d]
  (- (* a
        (Math/sin (* b
                     (- input
                        (/ Math/PI 2)))))
     d))


;; VISUALIZATION
(defn time-seq->time-series [time-seq]
  (->> time-seq
       (map (fn [e] [e (sine 2 2 e 0)]))
       (map (fn [[time price]]
              {:last-trade-time time
               :last-trade-price price}))))

(defn time-seq->simple-moving-averages [time-seq]
  (let [time-series (time-seq->time-series time-seq)
        time-series-partitioned (partition 20 1 time-series)]
    (->> time-series-partitioned
         (map #(lag/simple-moving-average {} %)))))

(defn simple->expoential-moving-averages [simple-moving-averages]
  (->> simple-moving-averages
       (lag/exponential-moving-average {} 20)))

(defn time-seq->simple-exponential-pair [time-seq]

  (let [simple-moving-averages (time-seq->simple-moving-averages time-seq)
        expoential-moving-averages (simple->expoential-moving-averages simple-moving-averages)]

    {:sma-list (map #(dissoc % :population) simple-moving-averages)
     :ema-list expoential-moving-averages}))

(comment  ;; Exponential faster than Simple Moving Average

  (let [simple-exponential-pair (time-seq->simple-exponential-pair (range 1 41 1/10))
        simple-exponential-joined (->> (vals simple-exponential-pair)
                                       (map #(sort-by :last-trade-time %))
                                       (apply map merge)
                                       (map #(dissoc % :uuid)))]

    (let [headers (-> simple-exponential-joined first keys)
          values (map vals simple-exponential-joined)]

      (with-open [out-file (clojure.java.io/writer "exponential-faster-than-simple-moving-average.csv")]
        (csv/write-csv out-file (concat [headers] values))))))
