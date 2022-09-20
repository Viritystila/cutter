(ns #^{:author "Mikael Reponen"}
  cutter.sync
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.math.numeric-tower :as math]
            [cutter.cutter :refer :all]
            [cutter.texturearray :refer :all]
            ;[cutter.camera :refer :all]
            ;[cutter.video :refer :all]
            ;[cutter.opencv :refer :all]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan sliding-buffer dropping-buffer close! thread
                     alts! alts!! timeout]]
            ;clojure.string
            )
  )

;;;;;;;;;;;;;;
;;;Videosync;;
;;;;;;;;;;;;;;
(defonce videoSync (atom {}))

(defn sync-thread [buf-key synth-name-key ctrl-bus start-frame sample-rate frame-rate control-queue]
  (let []
    (async/thread
      (while (not (= :stop (async/poll! control-queue)))
        (let [spos    (first (overtone.sc.bus/control-bus-get ctrl-bus))
              tpos    (/ spos sample-rate)
              fram    (int (- (* tpos frame-rate) start-frame))]
          (cutter.texturearray/set-buffer-index buf-key  fram)))
      :stopped)))

(defn store-sync [control-queue status-queue synth-name-key]
  (let [vsync     @videoSync
        has-key   (contains? vsync synth-name-key)
        data      {:control control-queue :status status-queue}]
    (if (not has-key)
      (swap! videoSync assoc synth-name-key data)
      (do
        (async/offer! (:control (synth-name-key @videoSync)) :stop)
        (swap! videoSync assoc synth-name-key data)))))

(defn avs
  ([buf-key synth-name-key ctrl-bus start-frame sample-rate frame-rate]
   (let [control-queue (chan (sliding-buffer 1))
         stc           (sync-thread buf-key synth-name-key ctrl-bus start-frame sample-rate frame-rate control-queue)]
     (store-sync control-queue stc synth-name-key)))
([buf-key synth-name-key ctrl-bus start-frame sample-rate]
   (let [frame-rate        25
         control-queue     (chan (sliding-buffer 1))
         stc              (sync-thread buf-key synth-name-key ctrl-bus start-frame sample-rate frame-rate control-queue)]
         (store-sync control-queue stc synth-name-key)))
  ([buf-key synth-name-key ctrl-bus start-frame]
   (let [sample-rate       48000
         frame-rate        25
         control-queue     (chan (sliding-buffer 1))
         stc              (sync-thread buf-key synth-name-key ctrl-bus start-frame sample-rate frame-rate control-queue)]
         (store-sync control-queue stc synth-name-key))))


(defn stop-sync [synth-name-key]
  (async/offer! (:control (synth-name-key @videoSync)) :stop )
  (swap! videoSync dissoc synth-name-key ))

;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;
