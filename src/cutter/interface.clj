(ns #^{:author "Mikael Reponen"}
  cutter.interface
  (:use [overtone.osc])
  (:require ;[clojure.tools.namespace.repl :refer [refresh]]
            ;[watchtower.core :as watcher]
            ;[clojure.java.io :as io]
            ;[while-let.core :as while-let]
            [cutter.cutter :refer :all]
            [cutter.texturearray :refer :all]
            [cutter.camera :refer :all]
            [cutter.video :refer :all]
            [cutter.opencv :refer :all]
            [cutter.cutter_helper :refer :all]
            ; [clojure.core.async
            ;  :as async
            ;  :refer [>! <! >!! <!! go go-loop chan sliding-buffer dropping-buffer close! thread
            ;          alts! alts!! timeout]]
            ;clojure.string
            ))
;;;;;;;;;;;;
;;;Camera;;;
;;;;;;;;;;;;
(defn cam [device destination-texture-key]
  "Start camera, example (cam \"0\" :iChannel1)"
  (cutter.camera/set-live-camera-texture device destination-texture-key))

(defn stop-cam [device]
  "Stop camera, example (stop-cam \"0\")"
  (cutter.camera/stop-camera device))

(defn rec [device buffername]
  "Record camera, example (rec \"0\" \"a \" )"
  (cutter.camera/rec-camera device buffername))

(defn set-cam [device property val]
  "Set camera property, example (cam \"0\" :fps 30)"
  (cutter.camera/set-camera-property device property val))

(defn get-cam [device property]
  "Get camera property, example (cam \"0\" :fps)"
  (cutter.camera/get-camera-property device property))

(defn sac []
  "Stop all cameras"
  (cutter.camera/stop-all-cameras) )

;;;;;;;;;;;
;;;Video;;;
;;;;;;;;;;;
(defn vid [filename destination-texture-key]
  "Start video, example (vid \"./test1.mp4\" :iChannel1)"
  (cutter.video/set-live-video-texture filename destination-texture-key))

(defn stop-vid [filename]
  "Stop video, example (stop-vid \"./test1.mp4\")"
  (cutter.video/stop-video filename))

(defn set-vid [filename property val]
  "Set video property, example (set-vid \"./test1.mp4\" :fps 30)"
  (cutter.video/set-video-property filename property val))

(defn get-vid [filename property]
  "get video property, example (get-vid \"./test1.mp4\" :fps)"
  (cutter.video/get-video-property filename property))

(defn svl [filename start-index stop-index]
  "Set video play limits in frames, example (svl \"./test1.mp4\" 0 100)"
  (cutter.video/set-video-limits filename start-index stop-index))

(defn cut [filename buffername start-frame]
  "Cut a video segment to buffer, example (cut \"./test1.mp4\" \"a\" 0)"
  (cutter.video/cut-video filename buffername start-frame))

(defn sav []
  "Stop all videos"
  (cutter.video/stop-all-videos))

;;;;;;;;;;;;
;;;Buffer;;;
;;;;;;;;;;;;
(defn buf [buffername destination-texture-key]
  "Play buffer, example (buf \"a \" :iChannel1)"
  (cutter.texturearray/set-buffer-texture buffername destination-texture-key))

(defn c-buf [src tgt]
  "Copy buffer, example (c-buf \"a \" \"b \")"
  (cutter.texturearray/copy-buffer src tgt))

(defn stop-buf [buffername]
  "Stop buffer, example (stop-buf \"a \" )"
  (cutter.texturearray/stop-buffer buffername))

(defn fps-buf [buffername val]
  "Set buffer frame rate, example (fps-buf \"a \"  30)"
  (cutter.texturearray/set-buffer-fps buffername val))

(defn f-buf [buffername]
  "Play buffer forwards, example (f-buf \"a \" )"
  (cutter.texturearray/set-buffer-fw buffername))

(defn b-buf [buffername]
  "Play buffer backwards, example (b-buf \"a \" )"
  (cutter.texturearray/set-buffer-bw buffername))

(defn p-buf [buffername]
  "Pause buffer continous playback, example (p-buf \"a \" )"
  (cutter.texturearray/set-buffer-paused buffername))

(defn i-buf [buffername val]
  "Set buffer index, example (i-buf \"a \" 25)"
  (cutter.texturearray/set-buffer-index buffername val))

(defn l-buf [buffername start-index stop-index]
  "Set buffer playback limits, example (l-buf \"a \" 25 50)"
  (cutter.texturearray/set-buffer-limits buffername start-index stop-index))

(defn r-buf [filename buffername index]
  "Replace iteam at index in buffer, example (r-buf \"./test1.png \" 25)"
  (cutter.texturearray/replace-in-buffer filename buffername index))

(defn sab []
  "Stop all buffers"
  (cutter.texturearray/stop-all-buffers []))

;;;;;;;;;;
;;;V4l2;;;
;;;;;;;;;;
(defn v4l2 [device]
  "Toggle v4l2 output to device, example (v4l2 \"/dev/video3\")"
  (cutter.cutter_helper/toggle-recording device))

;;;;;;;;;;;;
;;;Arrays;;;
;;;;;;;;;;;;
(defn set-arr [arraykey idx val]
  "Set float value to data array unform index, example (set-arr :iDataArray1 0 44.5 )"
  (cutter.cutter_helper/set-dataArray-item arraykey idx val))

(defn get-arr [arraykey idx]
  "Get a float value from data array at index, example (get-arr :iDataArray1 0)"
  (cutter.cutter_helper/get-dataArray-item arraykey idx))

;;;;;;;;;;;;
;;;Floats;;;
;;;;;;;;;;;;
(defn set-flt [floatKey val]
  "Set float value to uniform, example (set-flt :iFloat1 33.3)"
  (cutter.cutter_helper/set-float floatKey val))

(defn get-flt [floatKey]
  "Get a float value, example (get-flt :iFloat1)"
  (cutter.cutter_helper/get-float floatKey))

;;;;;;;;;;;;
;;;Images;;;
;;;;;;;;;;;;
(defn img [filename destination-texture-key]
  "Set image by filename, example (img \"./test1.png\" :iChannel1)"
  (cutter.cutter_helper/set-texture-by-filename filename destination-texture-key))

(defn add-img [filename buffername]
  "Add an image to a buffer index by filename, example (add-img \"./test1.png\" \"a\" )"
  (cutter.cutter_helper/add-to-buffer filename buffername))

(defn add-dir [dir buffername]
  "Add an images from a directory to a buffer, example (add-dir \"./test/\" \"a\" )"
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

;;;;;;;;;
;;;OSC;;;
;;;;;;;;;

(defn set-camera-interface-handlers []
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/cam"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                    (if (and (= 2 ic) (string? (nth input 0)) (string? (nth input 1)))
                      (cam (nth input 0) (keyword (nth input 1)))))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/stop-cam"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                    (if (and (= 1 ic) (string? (nth input 0)) )
                      (stop-cam (nth input 0))))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/rec"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                    (if (and (= 2 ic) (string? (nth input 0)) (string? (nth input 1)))
                      (rec (nth input 0) (nth input 1) )))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/set-cam"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                    (if (and (= 3 ic) (string? (nth input 0)) (string? (nth input 1)))
                      (set-cam (nth input 0) (keyword (nth input 1)) (nth input 2))))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/get-cam"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                    (if (and (= 2 ic) (string? (nth input 0)) (string? (nth input 1)))
                      (overtone.osc/osc-send (:osc-client @cutter.cutter/the-window-state) "/cutter/cam-prop" (nth input 0) (nth input 1) (get-cam (nth input 0) (keyword (nth input 1))))))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/sac"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                      (sac))))
)

(defn set-video-interface-handlers []
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/vid"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                    (if (and (= 2 ic) (string? (nth input 0)) (string? (nth input 1)))
                      (vid (nth input 0) (keyword (nth input 1)))))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/stop-vid"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                    (if (and (= 1 ic) (string? (nth input 0)) )
                      (stop-vid (nth input 0))))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/cut"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                    (if (and (= 3 ic) (string? (nth input 0)) (string? (nth input 1))  )
                      (cut (nth input 0) (nth input 1) (int (nth input 2)) )))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/set-vid"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                    (if (and (= 3 ic) (string? (nth input 0)) (string? (nth input 1)))
                      (set-vid (nth input 0) (keyword (nth input 1)) (nth input 2))))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/get-vid"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                    (if (and (= 2 ic) (string? (nth input 0)) (string? (nth input 1)))
                      (overtone.osc/osc-send (:osc-client @cutter.cutter/the-window-state) "/cutter/vid-prop" (nth input 0) (nth input 1) (get-vid (nth input 0) (keyword (nth input 1))))))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/sav"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                      (sav))))
)

(defn set-buffer-interface-handlers []
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/buf"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                    (if (and (= 2 ic) (string? (nth input 0)) (string? (nth input 1)))
                      (buf (nth input 0) (keyword (nth input 1)))))))

)


(set-camera-interface-handlers)
(set-video-interface-handlers)
