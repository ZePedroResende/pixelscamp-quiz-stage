(ns pixelsquiz.buzz
  (:gen-class))

(require '[pixelsquiz.logger :as logger])
(require '[pixelsquiz.types])

(require '[clojure.string :refer [upper-case]])
(require '[clojure.core.async :refer [>!!]])

(import '[pixelsquiz.types Event])
(import '[com.codeminders.hidapi HIDManager])


(def controller-buttons [:red :yellow :green :orange :blue])

(defn load-hid-natives []
  (let [bits (System/getProperty "sun.arch.data.model")]
    (clojure.lang.RT/loadLibrary (str "hidapi-jni-" bits))))


(defn open-buzz []
  (logger/info "Trying to open Buzz controllers")
  (let [_ (load-hid-natives)
        manager (HIDManager/getInstance)
        all_devs (.listDevices manager)
        filter_pred (fn [dev] (= (.getProduct_string dev) "Buzz"))
        buzz_dev (first (filter filter_pred all_devs))]
    (println buzz_dev)
    (if (nil? buzz_dev)
      (logger/info "Could not connect Buzz controllers")

      (let [buzz (.openByPath manager (.getPath buzz_dev))]
        (logger/info "Buzz controllers detected") buzz)
    )
  )
)

(defn debounce-buttons
  [current previous]
  (bit-and current (bit-xor current previous))
  )


(def button-mapping [nil 3 2 1 0])
(def button-colours ["blue" "orange" "green" "yellow"])
;; this is overly complicated, buzz buttons are numbered yellow to blue but the controller ordering is blue to yellow so we use that.

(defn buzz-to-properties
  [buttons team]
  (map #(assoc {}
          :button (get controller-buttons %)
          :button-index (get button-mapping %)
          :pressed (> (bit-and buttons (bit-shift-left 0x1 %)) 0)
          :team team
          ) (range (count controller-buttons))))

(defn read-buzz [dev channel]
  (try
    (let [buf (byte-array 5)
          ]
      (loop [br 0
             previous [0 0 0 0]]
        (let [states (if (= br 5)
                       (let [b1 (aget buf 2)
                             b2 (aget buf 3)
                             b3 (aget buf 4)
                             states [
                                     ;; A b1 0-4
                                     ;; B b1 5-7 b2 0-1
                                     ;; C b2 2-6
                                     ;; D b2 7 b3 0-3
                                     (bit-and 0x1f b1)
                                     (bit-and 0x1f (bit-or (bit-shift-left b2 3) (bit-and 0x7 (unsigned-bit-shift-right b1 5))))
                                     (bit-and 0x1f (unsigned-bit-shift-right b2 2))
                                     (bit-and 0x1f (bit-or (bit-shift-left b3 1) (bit-and 0x1 (unsigned-bit-shift-right b2 7))))
                                     ]
                             ]
                         (doseq [props (flatten (map buzz-to-properties (map debounce-buttons states previous) (range 4)))
                                 :when (:pressed props)
                                 ]
                            (logger/log :info :bright-cyan "Team #" (+ (:team props) 1) " pressed " (upper-case (name (:button props))) ".")
                            (>!! channel (Event. (case (:button props)
                                                  :red :buzz-pressed
                                                  :option-pressed) props))
                           )
                         states)
                       previous)
              ]
          (recur (.readTimeout dev buf -1) states)))
      )
    (catch Exception e nil))
  )

(defn open-and-read-buzz-into [channel]
  (loop [dev (open-buzz)]
    (if (nil? dev)
      (do
        (Thread/sleep 1000)
        (recur (open-buzz)))
      (do
        (read-buzz dev channel)
        (recur (open-buzz)))
      )))
