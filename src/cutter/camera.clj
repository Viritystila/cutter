(ns #^{:author "Mikael Reponen"}
  cutter.camera
  (:require ;[clojure.tools.namespace.repl :refer [refresh]]
                                        ;[watchtower.core :as watcher]
                                        ;[clojure.java.io :as io]
   [while-let.core :as while-let]
   [cutter.cutter :refer :all]
   [cutter.opencv :refer :all]
   [cutter.texturearray :refer :all]
   [clojure.core.async
    :as async
    :refer [>! <! >!! <!! go go-loop chan sliding-buffer dropping-buffer close! thread
            alts! alts!! timeout]]
   clojure.string)
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
        mat                       (:mat (destination-texture-key (:i-textures @cutter.cutter/the-window-state)))
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
        ;;(.open capture device-id  org.opencv.videoio.Videoio/CAP_V4L2)
        (.open capture device-id  org.opencv.videoio.Videoio/CAP_GSTREAMER)
        ;;(.open capture device-id  org.opencv.videoio.Videoio/CAP_FFMPEG)
        ;;(.set capture org.opencv.videoio.Videoio/CAP_PROP_FOURCC (org.opencv.videoio.VideoWriter/fourcc \M \J \P \G ))
        ;(Thread/sleep (+ 1000 (/ 1 (max 1 fps))))
         (.set capture org.opencv.videoio.Videoio/CAP_PROP_FPS 30)
        (swap! cutter.cutter/the-window-state assoc :cameras
               (assoc cameras
                      camera-key (assoc camera :running true,
                                        :fps (cutter.opencv/oc-get-capture-property :fps capture))))
        (async/thread
           (.set capture org.opencv.videoio.Videoio/CAP_PROP_FPS 30)
          (while-let/while-let [running (:running (camera-key (:cameras @cutter.cutter/the-window-state)))]
            (let [fps                 (:fps (camera-key (:cameras @cutter.cutter/the-window-state)))
                  camera-destination  (:destination (camera-key (:cameras @cutter.cutter/the-window-state)))
                  queue               (:queue ( camera-destination (:i-textures @cutter.cutter/the-window-state)))
                  mat                 (:mat (  destination-texture-key (:i-textures @cutter.cutter/the-window-state)))
                  pbo_id              (:pbo (  destination-texture-key (:i-textures @cutter.cutter/the-window-state)))
                  ]
              (reset! startTime (System/nanoTime))
              (oc-query-frame capture mat)
              (if (not (= 0  (.rows mat)))
                (async/offer!
                 queue
                 (conj  (matInfo mat) pbo_id)))
               ;(println @startTime fps)
              (Thread/sleep
               (cutter.general/sleepTime @startTime
                                         (System/nanoTime)
                                         (max 1 fps) ))))
          (.release capture))))
    nil))

(defn stop-camera [device]
  (let [device-id                (read-string (str (last device)))
        cameras                  (:cameras @the-window-state)
        camera-key               (keyword device)
        camera                   (camera-key cameras)
        capture                  (:source camera)
        camera                   (assoc camera :running false)
        cameras                  (assoc cameras camera-key camera)]
    (swap! cutter.cutter/the-window-state assoc :cameras cameras)
    (println "Stopping camera " device)
    (Thread/sleep 500)
    ;(.release capture)
    )
  nil)


(defn stop-all-cameras []
  (mapv (fn [x]  (stop-camera (clojure.string/join (rest (str x)))))(vec (keys (:cameras @cutter.cutter/the-window-state)))))


(defn rec-camera [device buffername length]
  (let [device-id                (read-string (str (last device)))
        cameras                  (:cameras @the-window-state)
        camera-key               (keyword device)
        camera                   (camera-key cameras)
        start-camera?            (or (nil? camera) (not (:running camera)))
        _                        (if start-camera? (set-live-camera-texture (str device-id) :iChannelNull)  )
        _ (Thread/sleep 500)
        cameras                  (:cameras @the-window-state)
        camera                   (camera-key cameras)
        destination              (:destination camera)
        source                   (:source camera)
        texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        current-frame            @(:current-frame @the-window-state)
        mat                      (:mat (destination (:i-textures @cutter.cutter/the-window-state)))
        maximum-buffer-length    (:maximum-buffer-length @cutter.cutter/the-window-state)
        maximum-buffer-length    (min length maximum-buffer-length)
        texture-array            (buffername-key  (:texture-arrays @cutter.cutter/the-window-state))
        running?                 false
        idx                      buffername
        bufdestination           (:destination texture-array)
        bufdestination           (if (nil? bufdestination) (:destination camera) bufdestination)
        running?                 (:running texture-array)
        running?                 (if (nil? running?) false running?)
        mode                     (:mode texture-array)
        mode                     (if (nil? mode) :fw mode)
        loop?                    (:loop texture-array)
        loop?                    (if (nil? loop?) true loop?)
        fps                      (:fps texture-array)
        fps                      (if (nil? fps) (:fps camera) fps)
        start-index              (:start-index texture-array)
        start-index              (if (nil? start-index) 0 start-index)
        stop-index               (:stop-index texture-array)
        stop-index               (if (nil? stop-index) maximum-buffer-length stop-index)
        ;texture-array            {:idx buffername, :destination bufdestination :source [], :running running?, :fps fps}
        old-pbo-ids              (:pbo_ids texture-array)
        old-pbo-ids              (if (nil? old-pbo-ids) [] old-pbo-ids)
        i-textures               (:i-textures @cutter.cutter/the-window-state)
        texture                  (destination i-textures)
        queue                    (:queue texture)
        mlt                      (:mult texture)
        req                      (:req texture)
        image-buffer             (atom [])
        pbo_ids                  (atom [])
        rejected-buffers         (atom [])
        rejected-pbos            (atom [])
        ;ib                       (:buffer texture-array)
        ;ib                       (if (nil? ib) [] ib)
        ;pb                       (:pbo_ids texture)
        ;pb                       (if (nil? pb) [] pb)
        ;image-buffer             (atom ib)
        t-a-index                (atom 0)
        out                      (if start-camera? queue (clojure.core.async/chan (async/buffer 1)))
        _                        (if start-camera? nil (clojure.core.async/tap mlt out))
        _                        (while (not  (:running (camera-key (:cameras @cutter.cutter/the-window-state)))) (Thread/sleep 500))
        init_image               (async/poll! out)
        init_image               (async/<!! out)
        h                        (nth init_image 4)
        w                        (nth init_image 5)
        c                        (nth init_image 6)
        _                        (async/poll! req)
        req-delete               (clojure.core.async/>!! (:request-queue @the-window-state) {:type :del :destination destination :buf-name buffername-key :data old-pbo-ids})
        req-delete-reply         (clojure.core.async/<!! req)
        req-input                (clojure.core.async/>!! (:request-queue @the-window-state) {:type :new :destination destination :buf-name buffername-key :data [[w h c maximum-buffer-length]]})
        orig_source_dat          (clojure.core.async/<!! req)
        is_good_dat              (vector? orig_source_dat)
        req-buffers              (if is_good_dat (first orig_source_dat) nil)
        req-pbo_ids              (if is_good_dat (last orig_source_dat) nil)]
     (while  @(:request-buffers @the-window-state) (Thread/sleep 500))
     (println "Recording from: " device " to " buffername)
     (async/thread
       ( while (and (.isOpened source) (< @t-a-index maximum-buffer-length)  is_good_dat )
        (let [dest-buffer         (nth req-buffers @t-a-index)
              pbo_id              (nth req-pbo_ids @t-a-index)
              image               (async/<!! out)
              rows                (nth image 1)
              step                (nth image 2)
              h                   (nth image 4)
              w                   (nth image 5)
              ib                  (nth image 6)
              buffer_i            (nth image 0)
              image               (assoc image 9 pbo_id)
              copybuf             (oc-mat-to-bytebuffer mat)
              buffer-capacity     (.capacity copybuf)
              dest-capacity       (.capacity dest-buffer)
              _                   (if (= buffer-capacity dest-capacity)
                                    (do (let [image           (assoc image 9 pbo_id)
                                              _               (swap! pbo_ids conj pbo_id)
                                              _               (swap! image-buffer conj (assoc image 0  (.flip (.put dest-buffer copybuf ))))]) )
                                    (do (swap! rejected-pbos conj pbo_id)
                                        (swap! rejected-buffers conj dest-buffer)))
              ] (swap! t-a-index inc)
             ))
      (swap! cutter.cutter/the-window-state assoc :texture-arrays
             (assoc texture-arrays buffername-key (assoc texture-array :idx buffername
                                                         :destination bufdestination
                                                         :source @image-buffer
                                                         :running running?
                                                         :fps fps
                                                         :index 0
                                                         :mode mode
                                                         :loop loop?
                                                         :start-index 1
                                                         :stop-index maximum-buffer-length
                                                         :pbo_ids   @pbo_ids)))
        ;(swap! t-a-index inc)
      (clojure.core.async/>!! (:request-queue @the-window-state) {:type :del :destination destination :buf-name buffername-key :data @rejected-pbos})
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
      (.set source org.opencv.videoio.Videoio/CAP_PROP_FPS  val)
                                        ;(cutter.opencv/oc-set-capture-property property source val)
      ))
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
