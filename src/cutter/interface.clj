(ns #^{:author "Mikael Reponen"}
  cutter.interface
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [watchtower.core :as watcher]
            [clojure.java.io :as io]
            [while-let.core :as while-let]
            [cutter.cutter :refer :all]
            [cutter.buffer :refer :all]
            [cutter.camera :refer :all]
            [cutter.video :refer :all]
            [cutter.opencv :refer :all]
            [cutter.cutter_helper :refer :all]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer sliding-buffer dropping-buffer close! thread
                     alts! alts!! timeout]]
            clojure.string))
;;;;;;;;;;;;
;;;Camera;;;
;;;;;;;;;;;;
(defn cam [device destination-texture-key]
  (cutter.camera/set-live-camera-texture device destination-texture-key))

(defn stop-cam [device]
  (cutter.camera/stop-camera device))

(defn rec [device buffername]
  (cutter.camera/rec-camera device buffername))

(defn set-cam [device property val]
  (cutter.camera/set-camera-property device property val))

(defn get-cam [device property]
  (cutter.camera/set-camera-property device property))

(defn sac []
  (cutter.camera/stop-all-cameras) )

;;;;;;;;;;;
;;;Video;;;
;;;;;;;;;;;
(defn vid [filename destination-texture-key]
  (cutter.video/set-live-video-texture filename destination-texture-key))

(defn stop-vid [filename]
  (cutter.video/stop-video filename))

(defn set-vid [filename property val]
  (cutter.video/set-video-property filename property val))

(defn get-vid [filename property]
  (cutter.video/get-video-property filename property))

(defn svl [filename start-index stop-index]
  (cutter.video/set-video-limits filename start-index stop-index))

(defn cut [filename buffername start-frame]
  (cutter.video/cut-video filename buffername start-frame))

(defn sav []
  (cutter.video/stop-all-videos []))

;;;;;;;;;;;;
;;;Buffer;;;
;;;;;;;;;;;;
(defn buf [buffername destination-texture-key]
  (cutter.buffer/set-buffer-texture buffername destination-texture-key))

(defn c-buf [src tgt]
  (cutter.buffer/copy-buffer src tgt))

(defn stop-buf [buffername]
  (cutter.buffer/stop-buffer buffername))

(defn fps-buf [buffername val]
  (cutter.buffer/set-buffer-fps buffername val))

(defn f-buf [buffername]
  (cutter.buffer/set-buffer-fw buffername))

(defn b-buf [buffername]
  (cutter.buffer/set-buffer-bw buffername))

(defn p-buf [buffername]
  (cutter.buffer/set-buffer-paused buffername))

(defn i-buf [buffername val]
  (cutter.buffer/set-buffer-index buffername val))

(defn l-buf [buffername start-index stop-index]
  (cutter.buffer/set-buffer-limits buffername start-index stop-index))

(defn r-buf [filename buffername index]
  (cutter.buffer/replace-in-buffer filename buffername index))

(defn sab []
  (cutter.buffer/stop-all-buffers []))

;;;;;;;;;;
;;;V4l2;;;
;;;;;;;;;;
(defn v4l2 [device]
  (cutter.cutter_helper/toggle-recording device))

;;;;;;;;;;;;
;;;Arrays;;;
;;;;;;;;;;;;
(defn set-arr [arraykey idx val]
  (cutter.cutter_helper/set-dataArray-item arraykey idx val))

(defn get-arr [arraykey idx]
  (cutter.cutter_helper/get-dataArray-item arraykey idx))

;;;;;;;;;;;;
;;;Floats;;;
;;;;;;;;;;;;
(defn set-flt [floatKey val]
  (cutter.cutter_helper/set-float floatKey val))

(defn get-flt [floatKey val]
  (cutter.cutter_helper/get-float floatKey val))

;;;;;;;;;;;;
;;;Images;;;
;;;;;;;;;;;;
(defn img [filename destination-texture-key]
  (cutter.cutter_helper/set-texture-by-filename filename destination-texture-key))

(defn add-img [filename buffername]
  (cutter.cutter_helper/add-to-buffer filename buffername))

(defn add-dir [dir buffername]
  (cutter.cutter_helper/add-from-dir dir buffername))

;;;;;;;;;;
;;;Misc;;;
;;;;;;;;;;
(defn list-cameras [] (println (:cameras @the-window-state)))
(defn get-camera-keys [] (keys (:cameras @the-window-state)))

(defn list-videos [] (println (:videos @the-window-state)))
(defn get-video-keys [] (keys (:videos @the-window-state)))

(defn list-textures [] (println (:textures @the-window-state)))
(defn get-texture-keys [] (keys (:textures @the-window-state)))

(defn list-texture-arrays [] (println (:texture-arrays @the-window-state)))
(defn list-texture-array-keys [] (keys (:texture-arrays @the-window-state)))
