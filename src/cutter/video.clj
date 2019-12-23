(ns #^{:author "Mikael Reponen"}
  cutter.video
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
;Video
(defn- set-live-video [filename destination-texture-key start-frame]
  "Set texture by filename and adds the filename to the list"
  (let [filename                  filename
        video-key                 (keyword filename)
        videos                    (:videos @cutter.cutter/the-window-state)
        video                     (video-key videos)
        capture                   (:source video)
        running?                  (:running video)
        capture                   (if (= nil capture ) (new org.opencv.videoio.VideoCapture) capture)
        index                     (if (nil? (:index video)) 0 (:index video))
        start-index               (if (nil? (:start-index video)) 0  (:start-index video))
        start-index               (if (= -1 start-frame) start-index start-frame)
        stop-index                (if (and (nil? (:stop-index video)) (not (nil? capture))) (oc-get-capture-property :frame-count  capture) (:stop-index video))
        mat                       (oc-new-mat)
        fps                       (if (= nil capture ) 30 (cutter.opencv/oc-get-capture-property :fps capture))
        video                     {:idx filename,
                                   :destination destination-texture-key,
                                   :source capture,
                                   :running running?,
                                   :fps fps
                                   :index index
                                   :start-index start-index
                                   :stop-index stop-index}
        videos                    (assoc videos video-key video)
        startTime                 (atom (System/nanoTime))]
        (swap! cutter.cutter/the-window-state assoc :videos videos)
        (if (and (not running?) (= :yes (:active @cutter.cutter/the-window-state)))
          (do
            (.open capture filename org.opencv.videoio.Videoio/CAP_FFMPEG)
            (swap! cutter.cutter/the-window-state assoc :videos
              (assoc videos
                video-key (assoc video :running true
                  :fps (cutter.opencv/oc-get-capture-property :fps capture)
                  :stop-index (oc-get-capture-property :frame-count  capture) )))
            (async/thread
              (oc-set-capture-property :pos-frames capture start-frame)
              ;(if (= -1 start-frame) nil (oc-set-capture-property :pos-frames capture start-frame))
              (while-let/while-let [running (:running (video-key (:videos @cutter.cutter/the-window-state)))]
                (let [fps                   (:fps (video-key (:videos @cutter.cutter/the-window-state)))
                      video-destination     (:destination (video-key (:videos @cutter.cutter/the-window-state)))
                      queue                 (:queue ( video-destination (:i-textures @cutter.cutter/the-window-state)))
                      frame-index           (oc-get-capture-property :pos-frames capture)
                      index                 (:index (video-key (:videos @cutter.cutter/the-window-state)))
                      start-index           (:start-index (video-key (:videos @cutter.cutter/the-window-state)))
                      stop-index            (:stop-index (video-key (:videos @cutter.cutter/the-window-state)))]
                    (reset! startTime (System/nanoTime))
                    (if (< (oc-get-capture-property :pos-frames capture) stop-index )
                    (oc-query-frame capture mat)
                    (do (oc-set-capture-property :pos-frames capture start-index) )
                    )
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
(defn set-live-video-texture [filename destination-texture-key] (set-live-video filename destination-texture-key -1))


(defn stop-video [filename]
  (let [device-id                filename
        videos                   (:videos @the-window-state)
        video-key                (keyword filename)
        video                    (video-key videos)
        capture                  (:source video)
        video                    (assoc video :running false)
        videos                   (assoc videos video-key video)]
        (swap! cutter.cutter/the-window-state assoc :videos videos)
        (.release capture))
  nil)


(defn stop-all-videos []
   (mapv (fn [x] (stop-video (clojure.string/join (rest (str x) )) ) ) (vec (keys (:videos @cutter.cutter/the-window-state)))))
;

(defn- set-video-property [filename property val]
  (let [device-id                 filename
        videos                    (:videos @cutter.cutter/the-window-state)
        video-key                 (keyword filename)
        video                     (video-key videos)
        source                    (:source video)
        _ (println source)]
        (if (= property :fps)
          (swap! cutter.cutter/the-window-state assoc :videos (assoc videos video-key (assoc video :fps val)))
          (cutter.opencv/oc-set-capture-property property source val)))
          nil)

(defn set-video-limits [filename start-index stop-index]
  (let [maximum-buffer-length    (:maximum-buffer-length @cutter.cutter/the-window-state)
        videos                   (:videos @cutter.cutter/the-window-state)
        video-key                (keyword filename)
        video                    (video-key videos)
        video                    (assoc video :start-index (max 0 (int start-index)))
        video                    (assoc video :stop-index (min maximum-buffer-length (int stop-index)))
        videos                   (assoc videos video-key video)]
        (swap! cutter.cutter/the-window-state assoc :videos videos) nil))


(defn- get-video-property [filename property val]
  (let [device-id                 filename
        videos                    (:videos @cutter.cutter/the-window-state)
        video-key                 (keyword filename)
        video                     (video-key videos)
        source                    (:source video)
        fps                       (:fps video)]
        (if (= property :fps)
          fps
          (cutter.opencv/oc-get-capture-property property source))))
;
(defn cut-video [filename buffername start-frame]
  "Cut a segment from a video with a length of :maximum-buffer-length startin from start-frame. If thr video is already running, the recording is from the running video"
  (let [device-id                filename
        videos                   (:videos @the-window-state)
        video-key                (keyword filename)
        video                    (video-key videos)
        start-video?             (or (nil? video) (not (:running video)))
        _                        (if start-video? (set-live-video filename :iChannelNull start-frame)  )
        videos                   (:videos @the-window-state)
        video                    (video-key videos)
        destination              (:destination video)
        source                   (:source video)
        texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        running?                 false
        idx                      buffername
        maximum-buffer-length    (:maximum-buffer-length @cutter.cutter/the-window-state)
        bufdestination           (:destination texture-array)
        bufdestination           (if (nil? bufdestination) (:destination video) bufdestination)
        running?                 (:running texture-array)
        running?                 (if (nil? running?) false running?)
        mode                     (:mode texture-array)
        mode                     (if (nil? mode) :fw mode)
        fps                      (:fps texture-array)
        fps                      (if (nil? fps) (:fps video) fps)
        start-index              (:start-index texture-array)
        start-index              (if (nil? start-index) 0 start-index)
        start-index              (if (= -1 start-frame) start-index start-frame)
        stop-index               (:stop-index texture-array)
        stop-index               (if (nil? stop-index) maximum-buffer-length (min maximum-buffer-length stop-index))
        texture-array            {:idx buffername, :destination bufdestination :source [], :running running?, :fps fps}
        i-textures               (:i-textures @cutter.cutter/the-window-state)
        texture                  (destination i-textures)
        queue                    (:queue texture)
        mlt                      (:mult texture)
        image-buffer             (atom [])
        out                      (if start-video? queue (clojure.core.async/chan (async/buffer 1)))
        _                        (if start-video? nil (clojure.core.async/tap mlt out))]
        (println "Recording from: " filename " to " buffername)
        (async/thread
          (while (and (.isOpened source) (< (count @image-buffer) maximum-buffer-length))
            (do
              (let [image               (async/<!! out)
                    h                   (nth image 4)
                    w                   (nth image 5)
                    ib                  (nth image 6)
                    buffer_i            (.convertFromAddr matConverter (long (nth image 0))  (int (nth image 1)) (long (nth image 2)) (long (nth image 3)))
                    buffer-capacity     (.capacity buffer_i)
                    buffer-copy         (-> (BufferUtils/createByteBuffer buffer-capacity)
                                          (.put buffer_i)
                                        (.flip))]
                    (swap! image-buffer conj [buffer-copy (nth image 1) (nth image 2) (nth image 3) h w ib]))))
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
              (println "Finished recording from:" filename "to" buffername)
              (if start-video? (stop-video (str device-id))))))



(defn rfs []  (stop)
              (cutter.buffer/stop-all-buffers)
              (cutter.camera/stop-all-cameras)
              (stop-all-videos)
              (refresh))
