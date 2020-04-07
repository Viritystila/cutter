(ns #^{:author "Mikael Reponen"}
  cutter.video
  (:require ;[clojure.tools.namespace.repl :refer [refresh]]
            ;[watchtower.core :as watcher]
            ;[clojure.java.io :as io]
            [while-let.core :as while-let]
            [cutter.cutter :refer :all]
            [cutter.opencv :refer :all]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan sliding-buffer dropping-buffer close! thread
                     alts! alts!! timeout]]
            ;clojure.string
            )
                                        ;
  (:import
  ;   [org.bytedeco.javacpp Pointer]
  ;   [org.bytedeco.javacpp BytePointer]
  ;   [org.bytedeco.javacpp v4l2]
  ;   [org.opencv.core Mat Core CvType]
  ;   [org.opencv.videoio Videoio VideoCapture]
  ;   [org.opencv.video Video]
  ;   [org.opencv.imgproc Imgproc]
  ;   [org.opencv.imgcodecs Imgcodecs]
  ;          (java.awt.image BufferedImage DataBuffer DataBufferByte WritableRaster)
  ;          (java.io File FileInputStream)
  ;          (java.nio IntBuffer ByteBuffer FloatBuffer ByteOrder)
  ;          (java.util Calendar)
  ;          (java.util List)
  ;          (javax.imageio ImageIO)
  ;          (java.lang.reflect Field)
            (org.lwjgl BufferUtils)
  ;          (org.lwjgl.glfw GLFW GLFWErrorCallback GLFWKeyCallback)
                                        ;          (org.lwjgl.opengl GL GL11 GL12 GL13 GL15 GL20 GL30 GL40)
   )

           )

;
;Video
(defn- set-live-video [filename destination-texture-key start-frame fps-in]
  "Set texture by filename and adds the filename to the list"
  (let [filename                  filename
        video-key                 (keyword filename)
        videos                    (:videos @cutter.cutter/the-window-state)
        video                     (video-key videos)
        capture                   (:source video)
        running?                  (:running video)
        capture                   (if (= nil capture ) (new org.opencv.videoio.VideoCapture) capture)
        mat                       (:mat (destination-texture-key (:i-textures @cutter.cutter/the-window-state)))
        index                     (if (nil? (:index video)) 0 (:index video))
        start-index               (if (nil? (:start-index video)) 0  (:start-index video))
        start-index               (if (= -1 start-frame) start-index start-frame)
        stop-index                (if (and (nil? (:stop-index video)) (not (nil? capture))) (oc-get-capture-property :frame-count  capture) (:stop-index video))
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
            ;;(.open capture filename org.opencv.videoio.Videoio/CAP_GSTREAMER)
            (swap! cutter.cutter/the-window-state assoc :videos
              (assoc videos
                video-key (assoc video :running true
                  :fps (if (= -1 fps-in) (cutter.opencv/oc-get-capture-property :fps capture) fps-in)
                  :stop-index (oc-get-capture-property :frame-count  capture) )))
            (async/thread
              (oc-set-capture-property :pos-frames capture start-frame)
              ;(if (= -1 start-frame) nil (oc-set-capture-property :pos-frames capture start-frame))
              (while-let/while-let [running (:running (video-key (:videos @cutter.cutter/the-window-state))) ]
                (let [fps                   (:fps (video-key (:videos @cutter.cutter/the-window-state)))
                      video-destination     (:destination (video-key (:videos @cutter.cutter/the-window-state)))
                      queue                 (:queue ( video-destination (:i-textures @cutter.cutter/the-window-state)))
                      frame-index           (oc-get-capture-property :pos-frames capture)
                      index                 (:index (video-key (:videos @cutter.cutter/the-window-state)))
                      start-index           (:start-index (video-key (:videos @cutter.cutter/the-window-state)))
                      stop-index            (:stop-index (video-key (:videos @cutter.cutter/the-window-state)))
                      mat                   (:mat (  destination-texture-key (:i-textures @cutter.cutter/the-window-state)))
                      pbo_id                (:pbo (  destination-texture-key (:i-textures @cutter.cutter/the-window-state)))]
                    ;(println mat)
                    (reset! startTime (System/nanoTime))
                    (if (< (oc-get-capture-property :pos-frames capture) stop-index )
                    (oc-query-frame capture mat)
                    (do (oc-set-capture-property :pos-frames capture start-index) )
                    )
                    (async/offer!
                      queue
                      (conj (matInfo mat) pbo_id))
                    (Thread/sleep
                      (cutter.general/sleepTime @startTime
                        (System/nanoTime)
                        fps ))))
              (.release capture)))))
        nil)

(defn set-live-video-texture [filename destination-texture-key] (set-live-video filename destination-texture-key -1 -1))


(defn stop-video [filename]
  (let [device-id                filename
        videos                   (:videos @the-window-state)
        video-key                (keyword filename)
        video                    (video-key videos)
        capture                  (:source video)
        video                    (assoc video :running false)
        videos                   (assoc videos video-key video)]
    (swap! cutter.cutter/the-window-state assoc :videos videos)
    (Thread/sleep 100)
    (.release capture))
  nil)


(defn stop-all-videos []
   (mapv (fn [x] (stop-video (clojure.string/join (rest (str x) )) ) ) (vec (keys (:videos @cutter.cutter/the-window-state)))))
;

(defn set-video-property [filename property val]
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


(defn get-video-property [filename property val]
  (let [device-id                 filename
        videos                    (:videos @cutter.cutter/the-window-state)
        video-key                 (keyword filename)
        video                     (video-key videos)
        source                    (:source video)
        fps                       (:fps video)]
        (if (= property :fps)
          fps
          (cutter.opencv/oc-get-capture-property property source))))

(defn cut-video [filename buffername start-frame]
  "Cut a segment from a video with a length of :maximum-buffer-length startin from start-frame. If thr video is already running, the recording is from the running video"
  (let [device-id                filename
        videos                   (:videos @the-window-state)
        video-key                (keyword filename)
        video                    (video-key videos)
        start-video?             (or (nil? video) (not (:running video)))
        _                        (if start-video? (set-live-video filename :iChannelNull start-frame 100)  )
                                        ;_                        (Thread/sleep 500)
        current-frame            @(:current-frame @the-window-state)
        _                        (while (not  (:running (video-key (:videos @cutter.cutter/the-window-state)))) (Thread/sleep 500))
        videos                   (:videos @the-window-state)
        video                    (video-key videos)
        destination              (:destination video)
        source                   (:source video)
        texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        mat                      (:mat (destination (:i-textures @cutter.cutter/the-window-state)))
        maximum-buffer-length    (:maximum-buffer-length @cutter.cutter/the-window-state)
        texture-array            (buffername-key  (:texture-arrays @cutter.cutter/the-window-state))
        running?                 false
        idx                      buffername
        bufdestination           (:destination texture-array)
        bufdestination           (if (nil? bufdestination) (:destination video) bufdestination)
        running?                 (:running texture-array)
        running?                 (if (nil? running?) false running?)
        mode                     (:mode texture-array)
        mode                     (if (nil? mode) :fw mode)
        loop?                    (:loop texture-array)
        loop?                    (if (nil? loop?) true loop?)
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
        req                      (:req texture)
        image-buffer             (atom [])
        pbo_ids                  (atom [])
        rejected-images          (atom [])
        rejected-pbos            (atom [])
        t-a-index                (atom 0)
        out                      (if start-video? queue (clojure.core.async/chan (async/buffer 1)))
        _                        (if start-video? nil (clojure.core.async/tap mlt out))
        _                        (while (not  (:running (video-key (:videos @cutter.cutter/the-window-state)))) (Thread/sleep 500))
        init_image               (async/poll! out)
        init_image               (async/<!! out)
        h                        (nth init_image 4)
        w                        (nth init_image 5)
        c                        (nth init_image 6)
        req-input                (clojure.core.async/>!! (:request-queue @the-window-state) {:type :new :destination destination :buf-name buffername-key :data [[w h c maximum-buffer-length]]})
        orig_source_dat          (clojure.core.async/<!! req)
        req-buffers              (first orig_source_dat)
        req-pbo_ids              (last orig_source_dat)
        ]
    ;(println (first orig_source_dat))
    ;; (cutter.cutter/set-request
    ;;  buffername-key
    ;;  destination
    ;;  w
    ;;  h
    ;;  c
    ;;  maximum-buffer-length)
    (while  @(:request-buffers @the-window-state) (Thread/sleep 500))
        (println "Recording from: " filename " to " buffername)
        (async/thread
          (while (and (.isOpened source) (< @t-a-index maximum-buffer-length))
            (let [fps                 (cutter.opencv/oc-get-capture-property :fps source)
                                        ;orig_source         (nth  (:source  (buffername-key  (:texture-arrays @cutter.cutter/the-window-state)) ) @t-a-index)
                  ;orig_source         (nth orig_source_dat  @t-a-index)
                  ;dest-buffer         (first orig_source)
                  ;pbo_id              (last orig_source)
                  dest-buffer         (nth req-buffers @t-a-index)
                  pbo_id              (nth req-pbo_ids @t-a-index)
                  ;_ (println dest-buffer)
                  ;_ (println pbo_id)
                  image               (async/<!! out)
                  h                   (nth image 4)
                  w                   (nth image 5)
                  ib                  (nth image 6)
                  ;mat                 (nth image 7)
                  buffer_i            (nth image 0)
                  ;image               (assoc image 9 pbo_id)
                  ;pbo_ids             (:pbo_ids texture-array)
                  copybuf             (oc-mat-to-bytebuffer mat)
                  buffer-capacity     (.capacity copybuf)
                  dest-capacity       (.capacity dest-buffer)
                  _                   (if (= buffer-capacity dest-capacity)
                                        (do (let [image           (assoc image 9 pbo_id)
                                                  _               (swap! pbo_ids conj pbo_id)
                                                  _               (swap! image-buffer conj (assoc image 0  (.flip (.put dest-buffer copybuf ))))]) ))])
            (swap! t-a-index inc)
            )
          (clojure.core.async/untap mlt out)
          (if start-video? (stop-video (str device-id)))
          (swap! cutter.cutter/the-window-state assoc :texture-arrays
                 (assoc texture-arrays buffername-key
                        (assoc texture-array :idx buffername
                               :destination bufdestination
                               :source @image-buffer
                               :running running?
                               :fps fps ;;(cutter.opencv/oc-get-capture-property :fps source)
                               :index 0
                               :mode mode
                               :loop loop?
                               :start-index 1
                               :stop-index maximum-buffer-length
                               :pbo_ids  @pbo_ids)))
          (println "Finished recording from:" filename "to" buffername))))
