(ns com.interrupt.edgar.service
  (:import [javax.servlet.http HttpServletRequest HttpServletResponse]
           [java.text SimpleDateFormat])
  (:require [com.interrupt.edgar.datomic :as edatomic]
            [com.interrupt.edgar.ib.market :as market]
            [com.interrupt.edgar.core.analysis.lagging :as alagging]
            [com.interrupt.edgar.core.signal.lagging :as slagging]
            [com.interrupt.edgar.core.signal.leading :as sleading]
            [com.interrupt.edgar.core.signal.confirming :as sconfirming]
            [com.interrupt.edgar.core.strategy.strategy :as strategy]
            [com.interrupt.edgar.core.strategy.target :as target]
            [com.interrupt.edgar.core.signal.common :as common]
            [com.interrupt.edgar.core.tee.live :as tlive]
            [com.interrupt.edgar.server.handler :as shandler]

            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [io.pedestal.log :as log]
            [io.pedestal.http :as bootstrap]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.sse :as sse]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [io.pedestal.http.ring-middlewares :as middlewares]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.helpers :refer [defhandler definterceptor defon-response defbefore defafter]]
            [ring.middleware.session.memory :as rmemory]
            [ring.middleware.session.cookie :as rcookie]
            [ring.util.response :as ring-resp]))


;; HOME Page
(defhandler home-page
  [request]

  (-> (ring-resp/response (slurp (io/resource "include/index.html")))
      (ring-resp/content-type "text/html")))


;; LIST Filtered Stocks
(defhandler list-filtered-input
  "List high-moving stocks"
  [request]

  (let [conn (edatomic/database-connect nil)
        result (live/load-filtered-results 20 conn)]
    (ring-resp/response result)))


;; HISTORICAL Data
#_(defn resume-historical [context result-map]

    (let [response-result (ring-resp/response result-map)]

      (log/info :resume-historical (str "... resume-historical > paused-context class["
                                        (class context) "] > response ["
                                        (class response-result) "] [" response-result "]"))
      (iimpl/resume
       (-> context
           (assoc :response response-result)))))

#_(defn async-historical [paused-context]

  (let [client (:interactive-brokers-client (edgar/initialize-workbench))
        stock-selection [ (-> paused-context :request :query-params :stock-selection) ]
        time-duration (-> paused-context :request :query-params :time-duration)
        time-interval (-> paused-context :request :query-params :time-interval)]

    (log/info :async-historical (str "... async-historical 1 > paused-context class["
                                     (class paused-context) "] > stock-selection["
                                     stock-selection "] > time-duration["
                                     time-duration "] > time-interval["
                                     time-interval "] > client-from-session["
                                     (:session (:request paused-context)) "]"))

    (market/create-event-channel)

    ;; Output here will be a map with a key / value of { :stock-name tick-list }
    (edgar/play-historical
     client
     stock-selection
     time-duration
     time-interval
     [(fn [tick-list]

        ;; tick-list format will be:
        ;; [{:id 0, :symbol DDD, :company 3D Systems Corporation, :price-difference 0.09000000000000341, :event-list []}]
        (reduce (fn [rslt ech-list]

                  (let [

                        ;; from Historical feed, dates will be strings that look like: "20130606  15:33:00"
                        date-format (SimpleDateFormat. "yyyyMMdd HH:mm:ss")

                        tick-list-formatted (map (fn [inp]
                                                   {:last-trade-price (:close inp)
                                                    :last-trade-time (->> (:date inp)
                                                                          (.parse date-format)
                                                                          .getTime)
                                                    :total-volume (:volume inp)})
                                                 (reverse (walk/keywordize-keys
                                                           (-> ech-list :event-list))))


                        final-list (reduce (fn [rslt ech]
                                             (conj rslt [(:last-trade-time ech) (:last-trade-price ech)]))
                                           []
                                           tick-list-formatted)


                        sma-list (alagging/simple-moving-average {:input :last-trade-price
                                                                  :output :last-trade-price-average
                                                                  :etal [:last-trade-price :last-trade-time]}
                                                                 20
                                                                 tick-list-formatted)
                        smaF (reduce (fn [rslt ech]
                                       (conj rslt [(:last-trade-time ech) (:last-trade-price-average ech)]))
                                     []
                                     sma-list)

                        ema-list (alagging/exponential-moving-average nil 20 tick-list-formatted sma-list)
                        emaF (reduce (fn [rslt ech]
                                       (conj rslt [(:last-trade-time ech) (:last-trade-price-exponential ech)]))
                                     []
                                     ema-list)

                        signals-ma (slagging/moving-averages 20 tick-list-formatted sma-list ema-list)
                        signals-bollinger (slagging/bollinger-band 20 tick-list-formatted sma-list)
                        signals-macd (sleading/macd nil 20 tick-list-formatted sma-list)
                        signals-stochastic (sleading/stochastic-oscillator 14 3 3 tick-list-formatted)
                        signals-obv (sconfirming/on-balance-volume 10 tick-list-formatted)

                        sA (strategy/strategy-fill-A tick-list-formatted
                                                     signals-ma
                                                     signals-bollinger
                                                     signals-macd
                                                     signals-stochastic
                                                     signals-obv)

                        #_sA #_[(assoc (nth tick-list-formatted 10) :strategies [{:signal :up
                                                                                  :why "test"}])]

                        sB (strategy/strategy-fill-B tick-list-formatted
                                                     signals-ma
                                                     signals-bollinger
                                                     signals-macd
                                                     signals-stochastic
                                                     signals-obv)]


                    ((:resume-fn paused-context) {:stock-name (:company ech-list)
                                                  :stock-symbol (:symbol ech-list)
                                                  :stock-list final-list
                                                  :source-list ech-list
                                                  :sma-list smaF
                                                  :ema-list emaF
                                                  :signals {:moving-average signals-ma
                                                            :bollinger-band signals-bollinger
                                                            :macd signals-macd
                                                            :stochastic-oscillator signals-stochastic
                                                            :obv signals-obv}
                                                  :strategies {:strategy-A sA
                                                               :strategy-B sB}})))
                []
                tick-list))])))

#_(defbefore get-historical-data
    "Get historical data for a particular stock"
    [{request :request :as context}]

    (iimpl/with-pause [paused-context context]
      (async-historical
       (assoc paused-context :resume-fn (partial resume-historical paused-context)))))


;; LIVE Data
(def stored-streaming-context (atom nil))

(defn init-streaming-stock-data [sse-context]
  (log/info :init-streaming-stock-data (str "... init-streaming-stock-data CALLED > sse-context[" sse-context "]"))
  (reset! stored-streaming-context sse-context))

(defn stop-streaming-stock-data
  "Stops streaming stock data for 1 or a list of stocks"
  []
  #_(when-let [streaming-context @stored-streaming-context]
    (reset! stored-streaming-context nil)
    (sse/end-event-stream streaming-context)))


(defn stream-live [event-name result]

  #_(log/info :stream-live (str "... stream-live > context[" streaming-context "] > event-name[" event-name "] response[" result "]"))

  (try
    (sse/send-event @stored-streaming-context event-name (pr-str result))
    (catch java.io.IOException ioe
      (stop-streaming-stock-data))))

#_(defn get-streaming-stock-data [request]

  (let [client (:interactive-brokers-client edgar/*interactive-brokers-workbench*)
        stock-selection [ (-> request :query-params :stock-selection) ]
        stock-name (-> request :query-params :stock-name)]

    (edgar/play-live client stock-selection [(partial tlive/tee-fn stream-live stock-name)])
    { :status 204 }))

(definterceptor session-interceptor
  (middlewares/session {:store (rcookie/cookie-store)} ))

(defroutes routes
  [[["/" {:get home-page}

     ^:interceptors [body-params/body-params, session-interceptor]
     ["/list-filtered-input" {:get list-filtered-input}]
     #_["/get-historical-data" {:get get-historical-data}]
     #_["/get-streaming-stock-data" {:get [::init-streaming-stock-data (sse/start-event-stream init-streaming-stock-data)]
                                   :post get-streaming-stock-data}]
     ]]])

;; You can use this fn or a per-request fn via io.pedestal.http.route/url-for
(def url-for (route/url-for-routes routes))

;; Consumed by sseve.server/create-server
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; :bootstrap/interceptors []
              ::bootstrap/routes routes

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ;;::boostrap/allowed-origins ["scheme://host:port"]

              ;; Root for resource interceptor that is available by default.
              ::bootstrap/resource-path "/public"
              ::bootstrap/file-path "/public"

              ;; Either :jetty or :tomcat (see comments in project.clj
              ;; to enable Tomcat)
              ;;::bootstrap/host "localhost"
              ::bootstrap/type :jetty
              ::bootstrap/port 8080})
