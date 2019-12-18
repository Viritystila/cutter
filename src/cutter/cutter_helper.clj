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

;
;
;sleepTime [startTime endTime fps]
(defn set-live-camera-texture [device destination-texture-key]
  "Set texture by filename and adds the filename to the list"
  (let [device-id                (read-string (str (last device)))
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
                (reset! startTime (System/nanoTime))
                ;(oc-query-frame capture mat)
                (async/offer!
                  (:queue ((:destination (camera-key (:cameras @cutter.cutter/the-window-state)))
                    (:i-textures @cutter.cutter/the-window-state)))
                    (matInfo (oc-query-frame capture (oc-new-mat))))
                (Thread/sleep
                  (cutter.general/sleepTime @startTime
                    (System/nanoTime)
                    (:fps (camera-key (:cameras @cutter.cutter/the-window-state))))))
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

(defn rec [device buffername]
  (let [device-id                (read-string (str (last device)))
        cameras                  (:cameras @the-window-state)
        camera-key               (keyword device)
        camera                   (camera-key cameras)
        destination              (:destination camera)
        source                   (:source camera)
        fps                      (:fps camera)
        texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        texture-array            {:idx buffername, :destination destination :source [] :running false, :fps fps}
        i-textures               (:i-textures @cutter.cutter/the-window-state)
        texture                  (destination i-textures)
        queue                    (:queue texture)
        mlt                      (:mult texture)
        ;width                    (atom 0)
        ;height                   (atom 0)
        ;image-bytes              (atom 0)
        maximum-buffer-length    (:maximum-buffer-length @cutter.cutter/the-window-state)
        ;vector-buffer           (mapv (fn [x] (org.opencv.core.Mat/zeros  height width org.opencv.core.CvType/CV_8UC3)) (range maximum-buffer-length))
        image-buffer             (atom [])
        out                      (clojure.core.async/chan (async/buffer 1))
        _                        (clojure.core.async/tap mlt out)
        ;image                    (async/poll! out)
        ]
        (println "Recording from: " device " to " "buffername")
        (async/thread
        (while (and (.isOpened source) (< (count @image-buffer) maximum-buffer-length))
          (do
            (let [image               (async/<!! out)
                  h                   (nth image 4)
                  w                   (nth image 5)
                  ib                  (nth image 6)
                  buffer              (.convertFromAddr matConverter (long (nth image 0))  (int (nth image 1)) (long (nth image 2)) (long (nth image 3)))
                  buffer-capacity     (.capacity buffer)
                  ;_ (println buffer-capacity)
                  buffer-copy         (ByteBuffer/allocateDirect buffer-capacity)
                  readOnlyCopy        (.asReadOnlyBuffer buffer)
                  ;_                   (.flip readOnlyCopy)
                  ;_ (.rewind buffer)
                  _                   (.put buffer-copy readOnlyCopy)
                  ;_                   (.flip buffer-copy)
                  ;_ (println buffer-copy)
                  ]
                  (swap! image-buffer conj [buffer-copy -1 -1 -1 h w ib]))))
          (swap! cutter.cutter/the-window-state assoc :texture-arrays
            (assoc texture-arrays (keyword buffername) (assoc texture-array :idx buffername
              :destination destination
              :source @image-buffer
              :running false
              :fps fps)))
              (clojure.core.async/untap mlt out)
              (println "Finished recording from:" device "to" buffername))))

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


;[140547741813184 480 1920 1 480 640 3]
(defn set-buffer-texture [buffername destination-texture-key]
  "Set texture by filename and adds the filename to the list"
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        ;texture-array            {:idx buffername, :destination destination :source [] :running false, :fps fps, :width 0, :height 0, :image-bytes 0}
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        ;_ (println texture-array)

        source                   (:source texture-array)
        running?                 (:running texture-array)
        fps                      (:fps texture-array)
        idx                      (:idx texture-array)
        i-textures               (:i-textures @cutter.cutter/the-window-state)
        texture                  (destination-texture-key i-textures)
        queue                    (:queue texture)
        buffer-length            (count source)
        texture-array            (assoc texture-array :idx idx,
                                    :destination destination-texture-key,
                                    :source source,
                                    :running running?,
                                    :fps fps)
        texture-arrays            (assoc texture-arrays  buffername-key texture-array)
        startTime                 (atom (System/nanoTime))
        index                     (atom 0)
        ]
        ;(swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays)
        (doseq [x source] (Thread/sleep 30) (async/offer!  queue x))
        ))


;buffername
