(ns app.resource
  (:require [cljs.core.async :refer [<! put! close! chan >!]]
            [clojure.string :as str]
            [cljs.nodejs :as node])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def ^:private js-request (node/require "request"))

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

(defn fetch [urls]
  (go
    (let [base-url "http://api.embed.ly/1/extract?key=5406650948f64aeb9102b9ea2cb0955c&urls="
          embedly-url (str base-url (str/join "," urls) "&maxwidth-500")
          response (<! (-request embedly-url))
          raw-resources (js->clj (:body response) :keywordize-keys true)
          js-resources  (.parse js/JSON raw-resources)
          resources     (map #(js->clj %1 :keywordize-keys true) js-resources)]
      resources)))
