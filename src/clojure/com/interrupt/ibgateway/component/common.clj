(ns com.interrupt.ibgateway.component.common
  (:require [clojure.core.async :refer [mult tap] :as async]))

(defn bind-channels->mult [source-list-ch & channels]
  (let [source-list->sink-mult (mult source-list-ch)]
    (doseq [c channels]
      (tap source-list->sink-mult c))))
