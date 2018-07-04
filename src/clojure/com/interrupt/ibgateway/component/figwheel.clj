(ns com.interrupt.ibgateway.component.figwheel
  #_(:require [mount.core :refer [defstate] :as mount]
            [figwheel-sidecar.repl-api :as ra]))

#_(defstate figwheel
  :start (ra/start-figwheel!)
  :stop (ra/stop-figwheel!))

