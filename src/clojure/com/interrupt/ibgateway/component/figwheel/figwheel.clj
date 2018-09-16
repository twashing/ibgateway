(ns com.interrupt.ibgateway.component.figwheel.figwheel
  (:require [mount.core :refer [defstate] :as mount]
            [figwheel-sidecar.repl-api :as ra]))

(defstate figwheel
  :start (do
           (ra/start-figwheel!)
           (ra/cljs-repl))
  :stop (ra/stop-figwheel!))
