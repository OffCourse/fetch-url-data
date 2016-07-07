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
(def r (node/require "rethinkdb"))
(def db-config {:host (.. js/process -env -RETHINK_HOST)
                :port (.. js/process -env -RETHINK_PORT)
                :ssl {:ca (.. js/process -env -RETHINK_CERT)}
                :authKey (.. js/process -env -RETHINK_AUTH_KEY)})

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

(defn db-connect []
  (let [c (chan)]
    (.connect r (clj->js db-config)
              #(go
                 (if %1
                   (>! c {:error %1})
                   (>! c {:connection %2}))))
    c))

(defn handle-response [res]
  (match [res]
         [{:connection _}] (:connection res)
         [{:error _}] (do
                        (println (:error res))
                        nil)))

(defn insert-resource [conn resource]
  (let [c (chan)
        db    (.db r "offcourse")
        table (.table db "resources")
        opp  (.insert table (clj->js resource))]
    (.run opp conn #(go (>! c (or %1 %2))))
    c))

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
          connection (-> (<! (db-connect))
                         handle-response )
          payload (-> event
                      (extract-payload)
                      (convert-payload))
          resource-js (->> (<! (request (:url payload)))
                       (.parse js/JSON))
          resource (js->clj resource-js :keywordize-keys true)
          response (<! (insert-resource connection resource))]
      (println resource-js)
    (.close connection #(cb nil (clj->js response))))))

(defn -main [] identity)
(set! *main-cli-fn* -main)
