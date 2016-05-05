(ns conveyare.core
  (:require [conveyare.model :as model]
            [conveyare.router :as router]
            [conveyare.transport :as transport]
            [clojure.core.async :as a]
            [clojure.tools.logging :as log]
            [clj-time.core :as t]
            [clj-time.format :as tf]))

(def default-opts
  {:topics {}
   :transport {:bootstrap.servers "localhost:9092"
               :consumer-ops {:group.id "my-service"}
               :producer-ops {:compression.type "gzip"
                              :max.request.size 5000000}}})

; TODO topics passed to kafka can be determined by routes created

(defonce ^:private state
  (atom {}))

; pass in map topic to route function
(defn start
  "Start conveyare system."
  [& opts]
  (let [opts (merge default-opts
                    (apply hash-map opts))
        t (transport/start opts)
        r (router/start opts t)]
    (swap! state merge
           {:transport t
            :router r
            :up true})
    :started))

(defn stop
  "Stop conveyare system."
  []
  (let [this @state]
    (router/stop (:router this))
    (transport/stop (:transport this))
    (swap! state assoc :up false)
    :stopped))

(defn status
  "Returns true if conveyare is up"
  []
  (:up @state))

(defn send-message!
  [uuid topic action data]
  (let [record (model/record topic uuid action data)
        t (:transport @state)]
    (transport/send-record! t record)))

(defn route-case
  "Creates a router function, that takes a Record and returns either
  a Receipt if processed, or nil if not"
  [& clauses]
  (apply router/route-case clauses))

(defn accept
  ""
  [route & args]
  (apply router/accept route args))