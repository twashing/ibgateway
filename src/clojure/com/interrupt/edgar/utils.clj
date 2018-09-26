(ns com.interrupt.edgar.utils
  (:require [clojure.string :as str])
  (:import [java.math RoundingMode]
           [java.text DecimalFormat]))

(defn double->str
  ([d]
   (double->str d 4))
  ([d n]
   (let [dfmt (DecimalFormat. (str "#." (str/join (repeat n "#"))))]
     (.setRoundingMode dfmt RoundingMode/CEILING)
     (.format dfmt d))))
