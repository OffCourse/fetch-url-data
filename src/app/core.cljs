(ns app.core
  (:require [cljs.nodejs :as node]
            [cljs.core.match :refer-macros [match]]
            [cljs.core.async :refer [<! put! close! chan >!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def AWS (node/require "aws-sdk"))
(def expander (node/require "unshortener"))
(def Kinesis (new AWS.Kinesis))

(def ^:private js-request (node/require "request"))

(node/enable-util-print!)

(defn- -request [url-or-opts]
  (let [c-resp (chan 1)]
    (js-request (clj->js url-or-opts)
                (fn [error response body]
                  (put! c-resp
                        (if error
                          {:error error}
                          {:response response :body body})
                        #(close! c-resp))))
    c-resp))

(defn request [url]
  (go
    (let [base-url "http://api.embed.ly/1/extract?key=5406650948f64aeb9102b9ea2cb0955c&url="
          embedly-url (str base-url url "&maxwidth-500")
          raw-resource (<! (-request embedly-url))]
      (js->clj (:body raw-resource) :keywordize-keys true))))


(defn convert-payload [data]
  (-> js/JSON
      (.parse (.toString (js/Buffer. data "base64") "ascii"))
      (js->clj :keywordize-keys true)))

(defn extract-payload [event]
  (-> (:Records event)
      first
      first
      second
      :data))


(defn event->payload [event]
  (-> event
      (js->clj :keywordize-keys true)
      (extract-payload)
      (convert-payload)
      (dissoc :id)))

(defn create-message [resource]
  {:Data (.stringify js/JSON (clj->js resource))
   :StreamName "fetched-resources-data"
   :PartitionKey "url"})

(defn send-message [msg]
  (let [c (chan)]
    (.putRecord Kinesis (clj->js msg) #(go (>! c (or %1 %2))))
    c))

(defn ^:export handler [event context cb]
  (go
    (let [event (js->clj event :keywordize-keys true)
          payload (-> event
                      (extract-payload)
                      (convert-payload))
          resource-js (->> (<! (request (:url payload)))
                           (.parse js/JSON))
          resource (js->clj resource-js :keywordize-keys true)
          message (create-message resource)
          response (<! (send-message message))]
      (println (.stringify js/JSON resource-js))
      (cb nil (clj->js response)))))

(defn -main [] identity)
(set! *main-cli-fn* -main)
