(ns #^{:author "Mikael Reponen"}
  cutter.cutter_helper
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
;;Data array
(defn set-dataArray1-item [idx val]
    (let [  oa  (:dataArray1  @the-window-state)
            na  (assoc oa idx val)]
        (swap! the-window-state assoc :dataArray1 na)
        nil))

(defn set-dataArray2-item [idx val]
    (let [  oa  (:dataArray2  @the-window-state)
            na  (assoc oa idx val)]
        (swap! the-window-state assoc :dataArray2 na)
        nil))

(defn set-dataArray3-item [idx val]
    (let [  oa  (:dataArray3  @the-window-state)
            na  (assoc oa idx val)]
        (swap! the-window-state assoc :dataArray3 na)
        nil))

(defn set-dataArray4-item [idx val]
    (let [  oa  (:dataArray4  @the-window-state)
            na  (assoc oa idx val)]
        (swap! the-window-state assoc :dataArray4 na)
        nil))

;v4l2
(defn openV4L2output [device]
  (let [h (:height @the-window-state)
          w                (:width @the-window-state)
          in_fd            (org.bytedeco.javacpp.v4l2/v4l2_open device 02)
          cap              (new org.bytedeco.javacpp.v4l2$v4l2_capability)
          flag             (org.bytedeco.javacpp.v4l2/v4l2_ioctl in_fd (long org.bytedeco.javacpp.v4l2/VIDIOC_QUERYCAP) cap)
          _                (println "VIDIOC_QUERYCAP: " flag)
          v4l2_format      (new org.bytedeco.javacpp.v4l2$v4l2_format)
          _               (.type v4l2_format (long org.bytedeco.javacpp.v4l2/V4L2_BUF_TYPE_VIDEO_OUTPUT))
          v4l2_pix_format (new org.bytedeco.javacpp.v4l2$v4l2_pix_format)
          _               (.pixelformat v4l2_pix_format (long org.bytedeco.javacpp.v4l2/V4L2_PIX_FMT_RGB24))
          _               (.width v4l2_pix_format w)
          _               (.height v4l2_pix_format h)
          minsize         (* 3 (.width v4l2_pix_format))
          _               (if (< (.bytesperline v4l2_pix_format) minsize) (.bytesperline v4l2_pix_format minsize))
          minsize         (* (.height v4l2_pix_format) (.bytesperline v4l2_pix_format))
          _               (if (< (.sizeimage v4l2_pix_format) minsize) (.sizeimage v4l2_pix_format minsize))
          _               (.fmt_pix v4l2_format v4l2_pix_format)
          flag            (org.bytedeco.javacpp.v4l2/v4l2_ioctl in_fd (long org.bytedeco.javacpp.v4l2/VIDIOC_S_FMT) v4l2_format)
          _               (println "VIDIOC_S_FMT: " flag)
          bff             (new org.bytedeco.javacpp.BytePointer minsize)]
          (reset! (:deviceName @the-window-state) device)
          (reset! (:deviceId @the-window-state) in_fd)
          (reset! (:minsize @the-window-state) minsize)
          (reset! (:bff @the-window-state) bff)
          (reset! (:isInitialized @the-window-state) true)))

(defn closeV4L2output [] (org.bytedeco.javacpp.v4l2/v4l2_close @(:deviceId @the-window-state))
                              (reset! (:isInitialized @the-window-state) false))

(defn toggle-recording [device]
  (let [  save    (:save-frames @the-window-state)]
          (if (= false @save)
            (do
              (openV4L2output device)
              (println "Start recording")
              (reset! (:save-frames @the-window-state) true ))
            (do (println "Stop recording")
              (reset! (:save-frames @the-window-state) false )
              (closeV4L2output)
              (Thread/sleep 100)))))

;Data array
(defn set-dataArray-item [arraykey idx val]
    (let [{:keys [maxDataArrays maxDataArraysLength i-dataArrays]} @cutter.cutter/the-window-state
          haskey        (contains? i-dataArrays arraykey)
          idx           (mod idx (- maxDataArraysLength 1))
          dataArray     (arraykey i-dataArrays)
          data          (if haskey (:datavec dataArray) nil )
          data          (if haskey (assoc data idx val) nil)
          dataArray     (if haskey (assoc dataArray :datavec data))
          i-dataArrays  (if haskey (assoc i-dataArrays arraykey dataArray))]
        (swap! the-window-state assoc :i-dataArrays i-dataArrays)
        nil))

;
(defn get-dataArray-item [arraykey idx]
    (let [{:keys [maxDataArrays maxDataArraysLength i-dataArrays]} @cutter.cutter/the-window-state
          haskey        (contains? i-dataArrays arraykey)
          idx           (mod idx (- maxDataArraysLength 1))
          dataArray     (arraykey i-dataArrays)
          data          (if haskey (:datavec dataArray) nil)
          val           (if haskey (nth data idx) nil)]
          val))

(defn list-cameras [] (println (:cameras @the-window-state)))
(defn get-camera-keys [] (keys (:cameras @the-window-state)))

(defn list-videos [] (println (:videos @the-window-state)))
(defn get-video-keys [] (keys (:videos @the-window-state)))

(defn list-textures [] (println (:textures @the-window-state)))
(defn get-texture-keys [] (keys (:textures @the-window-state)))

(defn list-texture-arrays [] (println (:texture-arrays @the-window-state)))

(defn list-camera-devices [] (println (:camera-devices @the-window-state)))

(defn list-video-filenames [] (println (:video-filenames @the-window-state)))

(defn list-texture-filenames [] (println (:texture-filenames @the-window-state)))

(defn list-texture-folders [] (println (:texture-folders @the-window-state)))

(defn add-texture-filename [filename]
  (let [filenames           (:texture-filenames @cutter.cutter/the-window-state)
        filenames           (conj filenames filename)
        filenames   (cutter.general/remove-inexistent filenames (:maximum-textures @cutter.cutter/the-window-state))]
        (swap! cutter.cutter/the-window-state
           assoc :texture-filenames filenames))
           nil)

(defn set-texture_by_filename [filename destination-texture-key]
  "Set texture by filename and adds the filename to the list"
  (let [  filenames           (:texture-filenames @cutter.cutter/the-window-state)
          textures            (:textures @cutter.cutter/the-window-state)
          idx                 (count filenames)
          _                   (add-texture-filename filename)
          i-textures          (:i-textures @cutter.cutter/the-window-state)
          texture             (destination-texture-key i-textures)
          mat                 (cutter.opencv/oc_load_image filename)
          height              (.height mat)
          width               (.width mat)
          channels            (.channels mat)
          internal-format     (cutter.opencv/oc-tex-internal-format mat)
          format              (cutter.opencv/oc-tex-format mat)
          queue               (:queue texture)
          texture             (assoc texture :width width :height height :channels channels :internal-format internal-format :format format :init-opengl true)
          i-textures          (assoc i-textures destination-texture-key texture)
          textures            (assoc textures (keyword filename) {:idx idx,
                                                                  :destination destination-texture-key,
                                                                  :source mat,
                                                                  :running true})
          ]
          (swap! cutter.cutter/the-window-state assoc :textures textures)
          (swap! cutter.cutter/the-window-state assoc :i-textures i-textures)
          (async/offer! queue (matInfo mat))
          )
          nil)

(defn set-texture_by_idx [idx destination-texture-key]
  "Set the texture from he list of texture filenames"
  (let [  filenames           (:texture-filenames @cutter.cutter/the-window-state)
          textures            (:textures @cutter.cutter/the-window-state)
          filename            (nth filenames (mod idx (count filenames)))]
          (set-texture_by_filename filename destination-texture-key))
          nil)

;Text
(defn write-text
  "(cutter.cutter/write-text \"cutter\" 0 220 10 100 0.2 0.4 20 10 true)"
    [text x y size r g b thickness linetype clear]
        (let [  i-textures          (:i-textures @the-window-state)
                texture             (:iText i-textures)
                width               (:width texture)
                height              (:height texture)
                oldmat              (:mat texture)
                queue               (:queue texture)
                mat                 (if clear (org.opencv.core.Mat/zeros  height width org.opencv.core.CvType/CV_8UC3) oldmat)
                corner              (new org.opencv.core.Point x y)
                style               (org.opencv.imgproc.Imgproc/FONT_HERSHEY_TRIPLEX)
                colScal             (new org.opencv.core.Scalar (float r) (float g) (float b))
                _                   (org.opencv.imgproc.Imgproc/putText mat text corner style size colScal thickness linetype)
                buffer              (oc-mat-to-bytebuffer mat)
                texture             (assoc texture :mat mat)
                texture             (assoc texture :buffer buffer)
                texture             (assoc texture :init-opengl true)
                i-textures          (assoc i-textures :iText texture)]
                (async/offer! queue (matInfo mat))
                (swap! the-window-state assoc :i-textures i-textures))
                nil)
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
   (mapv (fn [x] (stop-camera (stop-camera (clojure.string/join (rest (str x) )) ) )) (vec (keys (:cameras @cutter.cutter/the-window-state)))))


(defn rec [device buffername]
  (let [device-id                (read-string (str (last device)))
        cameras                  (:cameras @the-window-state)
        camera-key               (keyword device)
        camera                   (camera-key cameras)
        start-camera?            (or (nil? camera) (not (:running camera)))
        _                        (if start-camera? (cutter.cutter_helper/set-live-camera-texture (str device-id) :iChannelNull)  )
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
                      (reset! index (:index (buffername-key (:texture-arrays @cutter.cutter/the-window-state))))
                      (async/offer!
                        queue
                        (nth source (mod (max @index start-index) (min stop-index buffer-length))))
                      (case mode
                        :fw  (do (swap! index inc))
                        :bw  (do (swap! index dec))
                        :idx (reset! index (:index (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))))
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
        _                        (if start-video? (cutter.cutter_helper/set-live-video filename :iChannelNull start-frame)  )
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
        _                        (if start-video? nil (clojure.core.async/tap mlt out) )]
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
              (stop-all-buffers)
              (stop-all-cameras)
              (stop-all-videos)
              (refresh))
