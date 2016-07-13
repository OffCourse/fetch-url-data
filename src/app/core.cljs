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
  (println (.stringify js/JSON (clj->js event)))
  (let [incoming-action (action/convert event)]
    (if (spec/valid? ::specs/action incoming-action)
      (go
        (let [{:keys [payload type]} (spec/conform ::specs/action incoming-action)
              urls                   (map :url (second payload))
              resources              (<! (resource/fetch urls))
              outgoing-action        (action/create resources)]
          (if (spec/valid? ::specs/action outgoing-action)
            (let [response               (<! (message/send outgoing-action :url))]
              (println (.stringify js/JSON (clj->js outgoing-action)))
              (cb nil (clj->js "success")))
            (handle-error :invalid-outgoing-action (spec/explain-data ::specs/action outgoing-action) cb))))
      (handle-error :invalid-incoming-action (spec/explain-data ::specs/action incoming-action) cb))))

(defn -main [] identity)
(set! *main-cli-fn* -main)
