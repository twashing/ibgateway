(ns clojure.com.interrupt.ibgateway.component.account
  (:require [mount.core :refer [defstate] :as mount]))


(def state (atom nil))

(defstate account
  :start (reset! state [])
  :stop (reset! state nil))
