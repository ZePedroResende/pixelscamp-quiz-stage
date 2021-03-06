(ns pixelsquiz.stage
  (:gen-class))

(require '[pixelsquiz.buzz :as buzz])
(require '[pixelsquiz.sounds :as sounds])
(require '[pixelsquiz.util :refer [sort-teams-by-scores]])
(require '[pixelsquiz.logger :as logger])
(require '[pixelsquiz.types])

(require '[clojure.core.async :as async :refer [<! >!!]])
(require '[clojure.pprint :refer [pprint]])
(require '[clojure.string :refer [upper-case]])
(require '[compojure.core :as compojure :refer (defroutes GET POST)])
(require '[compojure.route :as route])
(require '[cheshire.core :as json])
(require '[org.httpkit.server :as http :refer [with-channel send! websocket? on-receive on-close]])
(require '[ring.middleware.defaults :as ring])

(import '[pixelsquiz.types Event])


(defn buttons-actor
  []
  (let [c (async/chan 16)]
    (async/go (buzz/open-and-read-buzz-into c))
    {:actor :buttons
     :chan c
     :routes (POST "/buttons/:action" [action :as request]
              (let [team-index (read-string (-> request :params :team))
                    button-index (read-string (-> request :params :button-index))
                    button-color (read-string (-> request :params :button))]
                (logger/log :info :bright-cyan "Team #" (+ team-index 1) " pressed " (upper-case button-color) " (synthetic).")
                (>!! c (Event. (keyword action) (assoc (:params request) :team team-index
                                                                         :button-index button-index)))
                (str "ok " (keyword action) "\n")))
     }
  ))

(defn play-sounds-for!
  [ev]
  (case (:kind ev)
    :timer-start (do
                   (sounds/stop)
                   (sounds/play :thinking-music))
    :buzzed (do
              (sounds/stop)
              (sounds/play :buzz))
    :timer-update (if (= 0 (-> ev :bag-of-props :value))
                    (do
                      (sounds/stop)
                      (sounds/play :timeout)))
    :show-question-results (if (not (sounds/is-playing :timeout))
                             (do
                               (sounds/stop)
                               (sounds/play :ping)))
    :qm-choice (if (= (-> ev :bag-of-props :right-wrong) :select-wrong)
                 (do
                   (sounds/stop)
                   (sounds/play :error)))
    :default
  ))


(defn format-for-displays
  [ev]
  (if (not (contains? #{:timer-update :starting} (get ev :kind)))
    (logger/log :info :bright-magenta "Displays: " (name (get ev :kind))))
  (try
    (case (:kind ev)
      :question-starting {:do :lights-off}
      :timer-update {:do :timer-update :value (-> ev :bag-of-props :value)}
      :buzzed {:do :highlight :team (-> ev :bag-of-props :team-buzzed)}
      :qm-choice {:do :update-lights
                    :colours (assoc ["off" "off" "off" "off"]
                                    (-> ev :bag-of-props :team)
                                    (case (-> ev :bag-of-props :right-wrong)
                                      :select-right "right"
                                      :select-wrong "wrong"))
                    }
      :update-lights {:do :update-lights
                      :colours (mapv #(if (nil? %)
                                       "off"
                                       (get buzz/button-colours %))
                      (-> ev :bag-of-props :answers)) }
      :show-question {:do :show-question
                      :text (-> ev :bag-of-props :text)
                      :options ["" "" "" ""]
                      }
      :show-options {:do :show-question ; ev bag-of-props Answer
                     :text (-> ev :bag-of-props :question :text)
                     :options (map #(:text %) (-> ev :bag-of-props :question :shuffled-options))
                     }
      :show-question-results {:do :update-scores  ; ev bag-of-props Answer
                              :text (-> ev :bag-of-props :question :text)
                              :scores (-> ev :bag-of-props :scores)
                              :options (mapv #(:text %) (-> ev :bag-of-props :question :shuffled-options))
                              :correctidx (.indexOf (mapv #(:original-pos %) (-> ev :bag-of-props :question :shuffled-options)) 0)
                              :answers (-> ev :bag-of-props :answers)
                              }
      :update-scores {:do :update-scores :scores (-> ev :bag-of-props :scores) :questionnum (-> ev :bag-of-props :question-index) } ; Round
      :end-of-round {:do :update-all ; ev bag-of-props Round
                     :text "Round ended!"
                     :options (map #(str "Team #" (:team %) " — " (:score %) " points")
                                   (sort-teams-by-scores (-> ev :bag-of-props :scores)))
                     :correctidx 0  ;; ...highlight winning team.
                     :scores (-> ev :bag-of-props :scores)
                     :questionnum (-> ev :bag-of-props :question-index)
                     }
      :team-number {:do :team-number}
      :starting {}
      :for-quizmaster {}
      :timer-start {}
      (do
        (logger/error "Unhandled event in format-for-displays: " ev)
        (pprint ev)))
    (catch Exception e (logger/error "Exception in format-for-displays: " ev e))))

(defn format-for-quizmaster
  [ev]
  (try
    (case (:kind ev)
      :for-quizmaster (merge {:do :quizmaster-only} (:bag-of-props ev))
      :buzzed (assoc {:do :quizmaster-only} :getrightwrong (-> ev :bag-of-props :team-buzzed))
      nil)
    (catch Exception e (logger/error "Exception in format-for-quizmaster:" ev e))
    ))

(defn displays-actor
  []
  (let [ws-connections (atom {})
        qm-connections (atom {})
        displays-channel (async/chan 16)]
    (async/go-loop
      [ev {:kind :starting}]
      (let [message (format-for-displays ev)
            qm-mesg (format-for-quizmaster ev)]
        (play-sounds-for! ev)
        (if (not (nil? message))
          (doseq [client (keys @ws-connections)]
            ;; send all, client will filter them
            (send! client (json/generate-string message))))
        (if (not (nil? qm-mesg))
          (doseq [client (keys @qm-connections)]
            (send! client (json/generate-string qm-mesg)))))
      (recur (<! displays-channel)))
    {:actor :displays
     :chan displays-channel
     :routes (GET "/displays" req
                  (with-channel req channel              ; get the channel
                    ;; communicate with client using method defined above
                    (on-close channel (fn [status]
                                        (swap! ws-connections dissoc channel)
                                        (swap! qm-connections dissoc channel)
                                        (logger/info "Display channel closed.")))
                    (if (websocket? channel)
                      (do
                        (logger/info "WebSocket display channel connected.")
                        (swap! ws-connections assoc channel true))
                      (logger/info "HTTP display channel connected."))
                    (on-receive channel (fn [data]
                                          ; data received from client
                                          ;; An optional param can pass to send!: close-after-send?
                                          ;; When unspecified, `close-after-send?` defaults to true for HTTP channels
                                          ;; and false for WebSocket.  (send! channel data close-after-send?)
                                          (let [message (json/parse-string data true)]
                                            (case (:kind message)
                                                  "quizmaster-auth" (do (swap! qm-connections assoc channel true)
                                                                       (send! channel (json/generate-string {:kind :info
                                                                                                             :text "OK!"})))
                                                  (logger/warn "Received display data:" data)))))))
     }))


(defn quizmaster-actor
  []
  (let [quizmaster-channel (async/chan 16)]
    {:actor :quizmaster
     :chan quizmaster-channel
     :routes (POST "/actions/:action" [action :as request]
               (do
                 (logger/log :info :bright-cyan "Quizmaster pressed " (upper-case action) ".")
                 (>!! quizmaster-channel (Event. (keyword action) (:params request))))
                 (str "ok " (keyword action) "\n"))
     }
    ))

(defn setup-stage
  []
  (let [
        actors [(displays-actor) (quizmaster-actor) (buttons-actor)]
        ui-routes [(:routes (nth actors 0))
                   (:routes (nth actors 1))
                   (:routes (nth actors 2)) ; XXX humm ...
                   (route/files "/static/" {:root "static/" :allow-symlinks? true})
                   (route/files "/" {:root "static/index.html"})]
        ]
    (http/run-server (ring/wrap-defaults (apply compojure/routes ui-routes) ring/api-defaults) {:port 3000})
    (logger/log :info :bright-white "Game engine READY at: http://localhost:3000/")
    (apply merge (map #(assoc {} (:actor %) (:chan %)) actors))
    ))
