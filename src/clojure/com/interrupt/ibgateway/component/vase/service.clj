(ns com.interrupt.ibgateway.component.vase.service
  (:require [clojure.core.async :refer [go-loop <! chan sliding-buffer] :as async]
            [clojure.java.io :as io]
            [io.pedestal.http :as http]
            [io.pedestal.log :as log]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.jetty.websockets :as ws]
            [mount.core :refer [defstate] :as mount]
            [ring.util.response :as ring-resp]
            [com.cognitect.vase :as vase]
            [net.cgrand.enlive-html :as enl]
            [cognitect.transit :as transit]
            [com.interrupt.ibgateway.component.switchboard :as sw]
            [com.interrupt.ibgateway.component.processing-pipeline :as pp]
            [com.interrupt.ibgateway.component.figwheel])
  (:import [org.eclipse.jetty.websocket.api Session]
           [java.io ByteArrayInputStream ByteArrayOutputStream]))


(enl/deftemplate t1 "public/index.html" []
  [:#app]
  (enl/set-attr :data-key "foo"))

(comment
  (apply str (t1)))

(defn about-page
  [request]
  (ring-resp/response (format "Clojure %s - served from %s"
                              (clojure-version)
                              (route/url-for ::about-page))))

(defn home-page [request]

  (log/debug "index-handler CALLED > request [%s]" request)
  (-> (apply str (t1))
      ring-resp/response
      (ring-resp/content-type "text/html")
      (ring-resp/header "Access-Control-Expose-Headers" "Content-Length, Last-Modified, Content-Type")))

(defn favicon [_]
  (-> (io/resource "public/favicon.ico")
      slurp
      ring-resp/response
      (ring-resp/content-type "image/x-icon")))

;; Defines "/" and "/about" routes with their associated :get handlers.
;; The interceptors defined after the verb map (e.g., {:get home-page}
;; apply to / and its children (/about).
(def common-interceptors [(body-params/body-params) http/html-body])

;; Tabular routes
(def routes #{["/" :get (conj common-interceptors `home-page)]
              ["/about" :get (conj common-interceptors `about-page)]
              ["/favicon.ico" :get `favicon]

              #_[true (fn [req]
                      {:body (slurp
                               (io/resource (str "public/"
                                                 (:uri req))))})]})


;; ==>

(def ws-clients (atom {}))

(defn new-ws-client [ws-session send-ch]

  (log/info :msg (str "new-ws-client CALLED: " ws-session send-ch))
  ;; (async/put! send-ch "This will be a text message")
  (swap! ws-clients assoc ws-session send-ch))

;; This is just for demo purposes
(defn send-and-close! []
  (let [[ws-session send-ch] (first @ws-clients)]
    (async/put! send-ch "A message from the server")
    ;; And now let's close it down...
    (async/close! send-ch)
    ;; And now clean up
    (swap! ws-clients dissoc ws-session)))

;; Also for demo purposes...
#_(defn send-message-to-all!
  [message]
  (let [out (ByteArrayOutputStream. 4096)
        writer (transit/writer out :json)]

    (transit/write writer message)
    (.toString out)))

(defn send-message-to-all!
  [message]
  (doseq [[^Session session channel] @ws-clients]

    (let [out (ByteArrayOutputStream. 4096)
          writer (transit/writer out :json)]

      (transit/write writer message)

      ;; The Pedestal Websocket API performs all defensive checks before sending,
      ;;  like `.isOpen`, but this example shows you can make calls directly on
      ;;  on the Session object if you need to
      (when (.isOpen session)
        (async/put! channel (.toString out))))))

(defn send-message-to-all-2!
  [message]
  (doseq [[^Session session channel] @ws-clients]
    (async/put! channel message)))

(def ws-paths
  {"/ws" {:on-connect (ws/start-ws-connection new-ws-client)
          :on-text (fn [msg] (log/info :msg (str "A client sent - " msg)))
          :on-binary (fn [payload offset length] (log/info :msg "Binary Message!" :bytes payload))
          :on-error (fn [t] (log/error :msg "WS Error happened" :exception t))
          :on-close (fn [num-code reason-text]
                      (log/info :msg "WS Closed:" :reason reason-text))}})


(comment

  (mount/find-all-states)

  (mount/stop #'com.interrupt.ibgateway.component.ewrapper/ewrapper
              #'com.interrupt.ibgateway.component.switchboard.store/conn
              #'com.interrupt.ibgateway.component.processing-pipeline/processing-pipeline
              #'com.interrupt.ibgateway.component.vase/server
              ;; #'com.interrupt.ibgateway.component.figwheel/figwheel
              #'com.interrupt.ibgateway.core/state)

  (mount/start #'com.interrupt.ibgateway.component.ewrapper/ewrapper
               #'com.interrupt.ibgateway.component.switchboard.store/conn
               #'com.interrupt.ibgateway.component.processing-pipeline/processing-pipeline
               #'com.interrupt.ibgateway.component.vase/server
               ;; #'com.interrupt.ibgateway.component.figwheel/figwheel
               #'com.interrupt.ibgateway.core/state)

  (sw/kickoff-stream-workbench)

  (sw/stop-stream-workbench)

  (let [{joined-channel :joined-channel} pp/processing-pipeline]

    #_(go-loop [events (<! merged-averages->tracer)]
      (log/info "merged-averages->tracer: " events)
      (if-let [next (<! merged-averages->tracer)]
        (recur next)))

    (go-loop [r (<! joined-channel)]

      (log/info "joined-channel : " r)
      (send-message-to-all! r)
      (if-not r
        r
        (recur (<! joined-channel))))))


(def service
  {:env :prod
   ;; You can bring your own non-default interceptors. Make
   ;; sure you include routing and set it up right for
   ;; dev-mode. If you do, many other keys for configuring
   ;; default interceptors will be ignored.
   ;; ::http/interceptors []

   ;; Uncomment next line to enable CORS support, add
   ;; string(s) specifying scheme, host and port for
   ;; allowed source(s):
   ;;
   ;; "http://localhost:8080"
   ;;
   ;;::http/allowed-origins ["scheme://host:port"]

   ::route-set routes
   ::vase/api-root "/api"
   ::vase/spec-resources ["my-vase-service_service.edn"]

   ;; Root for resource interceptor that is available by default.
   ::http/resource-path "/public"

   ;; Either :jetty, :immutant or :tomcat (see comments in project.clj)
   ::http/type :jetty
   ;;::http/host "localhost"
   ::http/port 8080

   ::http/secure-headers {:content-security-policy-settings {:object-src "'none'"}}

   ;; Options to pass to the container (Jetty)
   ::http/container-options {:h2c? true
                             :h2? false
                             ;:keystore "test/hp/keystore.jks"
                             ;:key-password "password"
                             ;:ssl-port 8443
                             :ssl? false
                             :context-configurator #(ws/add-ws-endpoints % ws-paths)}})
