(ns com.interrupt.edgar.obj-convert
  (:require [clojure.string :as str]
            [inflections.core :as inflections])
  (:import [java.util List]
           [java.lang.reflect AccessibleObject]))

(defprotocol ObjConvert
  (convert [obj] "Convert object to representation we like, e.g. map."))

(defn field-name-kw
  [name]
  (-> name
      (str/replace-first "m_" "")
      inflections/hyphenate
      keyword))

(defn to-map
  [obj]
  (reduce (fn [m field]
            (if-let [v (convert (.get field obj))]
              (assoc m (field-name-kw (.getName field)) v)
              m))
          {}
          (doto (.getDeclaredFields (.getClass obj))
            (AccessibleObject/setAccessible true))))

(extend-protocol ObjConvert
  nil
  (convert [_] nil)

  List
  (convert [this] (into [] this))

  Object
  (convert [this] this))
