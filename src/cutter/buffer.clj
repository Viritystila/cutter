(ns #^{:author "Mikael Reponen"}
  cutter.buffer
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [watchtower.core :as watcher]
            [clojure.java.io :as io]
            [while-let.core :as while-let]
            [cutter.cutter :refer :all]
            [cutter.opencv :refer :all]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer sliding-buffer dropping-buffer close! thread
                     alts! alts!! timeout]]
            clojure.string)
  (:import
    [org.bytedeco.javacpp Pointer]
    [org.bytedeco.javacpp BytePointer]
    [org.bytedeco.javacpp v4l2]
    [org.bytedeco.javacpp Loader]
    [org.viritystila opencvMatConvert]
    [org.opencv.core Mat Core CvType]
    [org.opencv.videoio Videoio VideoCapture]
    [org.opencv.video Video]
    [org.opencv.utils.Converters]
    [org.opencv.imgproc Imgproc]
    [org.opencv.imgcodecs Imgcodecs]
           (java.awt.image BufferedImage DataBuffer DataBufferByte WritableRaster)
           (java.io File FileInputStream)
           (java.nio IntBuffer ByteBuffer FloatBuffer ByteOrder)
           (java.util Calendar)
           (java.util List)
           (javax.imageio ImageIO)
           (java.lang.reflect Field)
           (org.lwjgl BufferUtils)
           (org.lwjgl.glfw GLFW GLFWErrorCallback GLFWKeyCallback)
           (org.lwjgl.opengl GL GL11 GL12 GL13 GL15 GL20 GL30 GL40)))

;
;Texture buffers
(defn set-buffer-texture [buffername destination-texture-key]
  "Set texture by filename and adds the filename to the list"
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        source                   (:source texture-array)
        running?                 (:running texture-array)
        fps                      (:fps texture-array)
        idx                      (:idx texture-array)
        start-index              (:start-index texture-array)
        stop-index               (:stop-index texture-array)
        i-textures               (:i-textures @cutter.cutter/the-window-state)
        texture                  (destination-texture-key i-textures)
        queue                    (:queue texture)
        buffer-length            (count source)
        texture-array            (assoc texture-array
                                    :idx idx,
                                    :destination destination-texture-key,
                                    :source source,
                                    :running running?,
                                    :fps fps
                                    :start-index start-index
                                    :stop-index stop-index)
        texture-arrays            (assoc texture-arrays  buffername-key texture-array)
        startTime                 (atom (System/nanoTime))
        index                     (atom 0)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays)
        (if (and (not running?) (= :yes (:active @cutter.cutter/the-window-state)))
        (do
          (let [texture-array (assoc texture-array :running true :fps fps)]
          (swap! cutter.cutter/the-window-state assoc :texture-arrays
            (assoc texture-arrays
              buffername-key texture-array))
                (async/thread
                  (while-let/while-let [running (:running (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))]
                  (let [mode                (:mode (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))
                        fps                 (:fps (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))
                        buffer-destination  (:destination (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))
                        queue               (:queue ( buffer-destination (:i-textures @cutter.cutter/the-window-state)))
                        start-index         (:start-index (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))
                        stop-index          (:stop-index (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))
                        source              (:source (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))
                        texture-arrays      (:texture-arrays @cutter.cutter/the-window-state)]
                      (reset! startTime (System/nanoTime))
                      (reset! index (:index (buffername-key texture-arrays )))
                      (async/offer!
                        queue
                        (nth source (mod (max @index start-index) (min stop-index buffer-length))))
                      (case mode
                        :fw  (do (swap! index inc))
                        :bw  (do (swap! index dec))
                        :idx (reset! index (:index (buffername-key texture-arrays))))
                      (swap! cutter.cutter/the-window-state assoc :texture-arrays
                        (assoc texture-arrays buffername-key (assoc texture-array
                                                              :fps fps
                                                              :mode mode
                                                              :index @index
                                                              :destination buffer-destination
                                                              :queue queue
                                                              :start-index start-index
                                                              :stop-index stop-index
                                                              :source source)))
                      (Thread/sleep (cutter.general/sleepTime @startTime (System/nanoTime) fps))))))))))

(defn copy-buffer [src tgt]
  (let [texture-arrays            (:texture-arrays @cutter.cutter/the-window-state)
        source-key                (keyword src)
        target-key                (keyword tgt)
        source-array              (source-key texture-arrays)
        target-array              (assoc source-array :running false :idx tgt)
        texture-arrays            (assoc texture-arrays target-key target-array)]
        (println (keys texture-arrays))
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays)) nil)

(defn stop-buffer [buffername]
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        texture-array            (assoc texture-array :running false)
        texture-arrays           (assoc texture-arrays buffername-key texture-array)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays)) nil)

(defn set-buffer-fps [buffername val]
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        texture-array            (assoc texture-array :fps val)
        texture-arrays           (assoc texture-arrays buffername-key texture-array)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays) nil))

(defn set-buffer-fw [buffername]
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        texture-array            (assoc texture-array :mode :fw)
        texture-arrays           (assoc texture-arrays buffername-key texture-array)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays) nil))

(defn set-buffer-bw [buffername]
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        texture-array            (assoc texture-array :mode :bw)
        texture-arrays           (assoc texture-arrays buffername-key texture-array)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays) nil))

(defn set-buffer-paused [buffername]
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        texture-array            (assoc texture-array :mode :idx)
        texture-arrays           (assoc texture-arrays buffername-key texture-array)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays) nil))

(defn set-buffer-index [buffername val]
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        texture-array            (assoc texture-array :index (int val))
        texture-arrays           (assoc texture-arrays buffername-key texture-array)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays) nil))

(defn set-buffer-limits [buffername start-index stop-index]
  (let [maximum-buffer-length    (:maximum-buffer-length @cutter.cutter/the-window-state)
        texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        texture-array            (assoc texture-array :start-index (max 0 (int start-index)))
        texture-array            (assoc texture-array :stop-index (min maximum-buffer-length (int stop-index)))
        texture-arrays           (assoc texture-arrays buffername-key texture-array)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays) nil))

(defn stop-all-buffers []
  (mapv (fn [x] (stop-buffer (str (name x)))) (vec (keys (:texture-arrays @cutter.cutter/the-window-state)))))
