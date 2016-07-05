(ns app.core
  (:require [cljs.nodejs :as node]
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

#_(defn create-message [link]
  {:Data (.stringify js/JSON (clj->js link))
   :StreamName "expanded-links"
   :PartitionKey "url"})

#_(defn send-message [msg]
  (.putRecord Kinesis (clj->js msg) #(if %1
                                       (println %1)
                                       (println %2))))

(defn ^:export handler [event context cb]
  (go
    (let [event (js->clj event :keywordize-keys true)
          payload (-> event
                      (extract-payload)
                      (convert-payload))
          resource (<! (request (:url payload)))]
      (println (clj->js resource))
      (cb nil (clj->js resource)))))

(defn -main [] identity)
(set! *main-cli-fn* -main)
