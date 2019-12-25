(ns #^{:author "Mikael Reponen"}
  cutter.camera
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [watchtower.core :as watcher]
            [clojure.java.io :as io]
            [while-let.core :as while-let]
            [cutter.cutter :refer :all]
            [cutter.opencv :refer :all]
            [cutter.buffer :refer :all]
             [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer sliding-buffer dropping-buffer close! thread
                     alts! alts!! timeout]]
            clojure.string)
  (:import
    [org.bytedeco.javacpp Pointer]
    [org.bytedeco.javacpp BytePointer]
    [org.bytedeco.javacpp v4l2]
    ;[org.bytedeco.javacpp Loader]
    ;[org.viritystila opencvMatConvert]
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
;Camera
(defn set-live-camera-texture [device destination-texture-key]
  "Set texture by filename and adds the filename to the list"
  (let [device-id                 (read-string (str (last device)))
        camera-key                (keyword device)
        cameras                   (:cameras @cutter.cutter/the-window-state)
        camera                    (camera-key cameras)
        capture                   (:source camera)
        running?                  (:running camera)
        capture                   (if (= nil capture ) (new org.opencv.videoio.VideoCapture) capture)
        mat                       (oc-new-mat)
        fps                       (if (= nil capture ) 30 (cutter.opencv/oc-get-capture-property :fps capture))
        camera                    {:idx device-id,
                                   :destination destination-texture-key,
                                   :source capture,
                                   :running running?,
                                   :fps fps}
        cameras                   (assoc cameras camera-key camera)
        startTime                 (atom (System/nanoTime))]
        (swap! cutter.cutter/the-window-state assoc :cameras cameras)
        (if (and (not running?) (= :yes (:active @cutter.cutter/the-window-state)))
          (do
            (.open capture device-id)
            (swap! cutter.cutter/the-window-state assoc :cameras
              (assoc cameras
                camera-key (assoc camera :running true,
                  :fps (cutter.opencv/oc-get-capture-property :fps capture))))
            (async/thread
              (while-let/while-let [running (:running (camera-key (:cameras @cutter.cutter/the-window-state)))]
                (let [fps                 (:fps (camera-key (:cameras @cutter.cutter/the-window-state)))
                      camera-destination  (:destination (camera-key (:cameras @cutter.cutter/the-window-state)))
                      queue               (:queue ( camera-destination (:i-textures @cutter.cutter/the-window-state)))]
                    (reset! startTime (System/nanoTime))
                    (oc-query-frame capture mat)
                    (async/offer!
                      queue
                      (matInfo mat))
                    (Thread/sleep
                      (cutter.general/sleepTime @startTime
                        (System/nanoTime)
                        fps ))))
              (.release capture)))))
        nil)
;
(defn stop-camera [device]
  (let [device-id                (read-string (str (last device)))
        cameras                  (:cameras @the-window-state)
        camera-key               (keyword device)
        camera                   (camera-key cameras)
        capture                  (:source camera)
        camera                   (assoc camera :running false)
        cameras                  (assoc cameras camera-key camera)]
        (swap! cutter.cutter/the-window-state assoc :cameras cameras)
        (.release capture))
  nil)


(defn stop-all-cameras []
   (mapv (fn [x]  (stop-camera (clojure.string/join (rest (str x) )) ) ) (vec (keys (:cameras @cutter.cutter/the-window-state)))))


(defn rec [device buffername]
  (let [device-id                (read-string (str (last device)))
        cameras                  (:cameras @the-window-state)
        camera-key               (keyword device)
        camera                   (camera-key cameras)
        start-camera?            (or (nil? camera) (not (:running camera)))
        _                        (if start-camera? (set-live-camera-texture (str device-id) :iChannelNull)  )
        cameras                  (:cameras @the-window-state)
        camera                   (camera-key cameras)
        destination              (:destination camera)
        source                   (:source camera)
        texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        running?                 false
        idx                      buffername
        maximum-buffer-length    (:maximum-buffer-length @cutter.cutter/the-window-state)
        bufdestination           (:destination texture-array)
        bufdestination           (if (nil? bufdestination) (:destination camera) bufdestination)
        running?                 (:running texture-array)
        running?                 (if (nil? running?) false running?)
        mode                     (:mode texture-array)
        mode                     (if (nil? mode) :fw mode)
        fps                      (:fps texture-array)
        fps                      (if (nil? fps) (:fps camera) fps)
        start-index              (:start-index texture-array)
        start-index              (if (nil? start-index) 0 start-index)
        stop-index               (:stop-index texture-array)
        stop-index               (if (nil? stop-index) maximum-buffer-length stop-index)
        texture-array            {:idx buffername, :destination bufdestination :source [], :running running?, :fps fps}
        i-textures               (:i-textures @cutter.cutter/the-window-state)
        texture                  (destination i-textures)
        queue                    (:queue texture)
        mlt                      (:mult texture)
        image-buffer             (atom [])
        out                      (if start-camera? queue (clojure.core.async/chan (async/buffer 1)))
        _                        (if start-camera? nil (clojure.core.async/tap mlt out) )]
        (println "Recording from: " device " to " buffername)
        (async/thread
          (while (and (.isOpened source) (< (count @image-buffer) maximum-buffer-length))
            (do
              (let [image               (async/<!! out)
                    h                   (nth image 4)
                    w                   (nth image 5)
                    ib                  (nth image 6)
                    buffer_i            (nth image 0) ;(.convertFromAddr matConverter (long (nth image 0))  (int (nth image 1)) (long (nth image 2)) (long (nth image 3)))
                    ; buffer-capacity     (.capacity buffer_i)
                    ; buffer-copy         (-> (BufferUtils/createByteBuffer buffer-capacity)
                    ;                       (.put buffer_i)
                    ;                     (.flip))
                                        ]
                    (swap! image-buffer conj image)))) ;[buffer-copy (nth image 1) (nth image 2) (nth image 3) h w ib]
                (swap! cutter.cutter/the-window-state assoc :texture-arrays
                  (assoc texture-arrays buffername-key (assoc texture-array :idx buffername
                                                                            :destination bufdestination
                                                                            :source @image-buffer
                                                                            :running running?
                                                                            :fps fps
                                                                            :index 0
                                                                            :mode :fw
                                                                            :start-index start-index
                                                                            :stop-index stop-index)))
              (clojure.core.async/untap mlt out)
              (println "Finished recording from:" device "to" buffername)
              (if start-camera? (stop-camera (str device-id))))))

(defn set-camera-property [device property val]
  (let [device-id                (read-string (str (last device)))
        cameras                  (:cameras @cutter.cutter/the-window-state)
        camera-key               (keyword device)
        camera                   (camera-key cameras)
        source                   (:source camera)]
        (if (= property :fps)
          (swap! cutter.cutter/the-window-state assoc :cameras (assoc cameras camera-key (assoc camera :fps val)))
          (cutter.opencv/oc-set-capture-property property source val)))
          nil)

(defn get-camera-property [device property]
  (let [device-id                (read-string (str (last device)))
        cameras                  (:cameras @cutter.cutter/the-window-state)
        camera-key               (keyword device)
        camera                   (camera-key cameras)
        source                   (:source camera)
        fps                      (:fps camera)]
        (if (= property :fps)
          fps
          (cutter.opencv/oc-get-capture-property property source))))
