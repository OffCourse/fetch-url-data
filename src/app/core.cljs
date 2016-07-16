(ns app.core
  (:require [cljs.nodejs :as node]
            [cljs.core.match :refer-macros [match]]
            [app.action :as action]
            [app.message :as message]
            [app.resource :as resource]
            [app.specs :as specs]
            [cljs.spec :as spec]
            [cljs.core.async :refer [<! put! close! chan >!]]
            [clojure.string :as str])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(node/enable-util-print!)

(defn handle-error [reason payload cb]
  (let [error (clj->js {:type :error
                        :error reason
                        :payload payload})]
    (println (.stringify js/JSON error))
    (cb error nil)))

(defn ^:export handler [event context cb]
  (println "Event: " (.stringify js/JSON (clj->js event)) "\n")
  (let [incoming-action (action/convert event)]
    (println "Incoming: " (.stringify js/JSON (clj->js incoming-action)) "\n")
    (if (spec/valid? ::specs/action incoming-action)
      (go
        (let [{:keys [payload type]} (spec/conform ::specs/action incoming-action)
              raw-bookmarks          (second payload)
              urls                   (map :url raw-bookmarks)
              resources              (<! (resource/fetch urls))
              bookmarks              (map (fn [[bookmark resource]]
                                            (assoc bookmark :url (:url resource)))
                                          (zipmap raw-bookmarks resources))
              bookmarks-action       (action/create bookmarks)
              resources-action       (action/create resources)]
          (if (spec/valid? ::specs/action bookmarks-action)
            (let [response (<! (message/send bookmarks-action :timestamp))]
              (println "Outgoing: " (.stringify js/JSON (clj->js bookmarks-action)) "\n"))
            (handle-error :invalid-outgoing-action (spec/explain-data ::specs/action bookmarks-action) cb))
          (if (spec/valid? ::specs/action resources-action)
            (let [response (<! (message/send resources-action :url))]
              (println "Outgoing: " (.stringify js/JSON (clj->js resources-action)) "\n"))
            (handle-error :invalid-outgoing-action (spec/explain-data ::specs/action resources-action) cb))
          (cb nil (clj->js "success"))))
      (handle-error :invalid-incoming-action (spec/explain-data ::specs/action incoming-action) cb))))

(defn -main [] identity)
(set! *main-cli-fn* -main)
