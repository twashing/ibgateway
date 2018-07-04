(ns com.interrupt.ibgateway.component.figwheel
  (:require [mount.core :refer [defstate] :as mount]
            [figwheel-sidecar.repl-api :as ra]))

(defstate figwheel
  :start (ra/start-figwheel!)
  :stop (ra/stop-figwheel!))

