(ns #^{:author "Mikael Reponen"}
  cutter.cutter_helper
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            ;[watchtower.core :as watcher]
            ;[clojure.java.io :as io]
            ;[while-let.core :as while-let]
            [cutter.cutter :refer :all]
            [cutter.texturearray :refer :all]
            [cutter.camera :refer :all]
            [cutter.video :refer :all]
            [cutter.opencv :refer :all]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan sliding-buffer dropping-buffer close! thread
                     alts! alts!! timeout]]
            ;clojure.string
            )
  ;(:import
    ;[org.bytedeco.javacpp Pointer]
    ;[org.bytedeco.javacpp BytePointer]
    ;[org.bytedeco.javacpp v4l2]
    ;[org.opencv.core Mat Core CvType]
    ; [org.opencv.videoio Videoio VideoCapture]
    ; [org.opencv.video Video]
    ; [org.opencv.imgproc Imgproc]
    ; [org.opencv.imgcodecs Imgcodecs]
           ; (java.awt.image BufferedImage DataBuffer DataBufferByte WritableRaster)
           ; (java.io File FileInputStream)
           ; (java.nio IntBuffer ByteBuffer FloatBuffer ByteOrder)
           ; (java.util Calendar)
           ; (java.util List)
           ; (javax.imageio ImageIO)
           ; (java.lang.reflect Field)
           ; (org.lwjgl BufferUtils)
           ; (org.lwjgl.glfw GLFW GLFWErrorCallback GLFWKeyCallback)
           ; (org.lwjgl.opengl GL GL11 GL12 GL13 GL15 GL20 GL30 GL40)
           ;)
           )

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
    (if (and (not (nil? device))  (= false @save))
      (do
        (openV4L2output device)
        (println "Start recording")
        (reset! (:save-frames @the-window-state) true ))
      (do (println "Stop recording")
          (reset! (:save-frames @the-window-state) false )
          (closeV4L2output)
          (Thread/sleep 100)))))

;Data array
;(apply assoc [1 2 3 4] (interleave (range (count [1 2 3 4])) [11 32 13 14] ))
(defn set-dataArray-item [arraykey idx val]
    (let [{:keys [maxDataArrays maxDataArraysLength i-dataArrays]} @cutter.cutter/the-window-state
          haskey        (contains? i-dataArrays arraykey)
          idx           (mod idx (- maxDataArraysLength 1))
          dataArray     (arraykey i-dataArrays)
          isarray       (vector? val)
          data          (if haskey (:datavec dataArray) nil )
          data          (if haskey
                            (if isarray  (apply assoc data (interleave (range idx (+ idx (count val ))) val ))
                              (assoc data idx val)) nil)
                              ;_ (println data)
          dataArray     (if haskey (assoc dataArray :datavec data))
          i-dataArrays  (if haskey (assoc i-dataArrays arraykey dataArray))]
      (if haskey (swap! the-window-state assoc :i-dataArrays i-dataArrays))
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

;Floats
(defn set-float [floatKey val]
    (let [{:keys [i-floats]} @cutter.cutter/the-window-state
          haskey        (contains? i-floats floatKey)
          floatVal      (floatKey i-floats)
          floatVal      (if haskey (assoc floatVal :data  val) nil)
          i-floats      (if haskey (assoc i-floats floatKey floatVal))]
      (if haskey (swap! the-window-state assoc :i-floats i-floats))
        nil))

(defn get-float [floatKey ]
    (let [{:keys [i-floats]} @cutter.cutter/the-window-state
          haskey        (contains? i-floats floatKey)
          floatVal      (floatKey i-floats)
          val           (if haskey (:data floatVal) nil)]
        val))


(defn set-texture-by-filename [filename destination-texture-key]
  "Set texture by filename and adds the filename to the list"
  (let [  textures            (:textures @cutter.cutter/the-window-state)
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
          textures            (assoc textures (keyword filename) {:idx filename,
                                                                  :destination destination-texture-key,
                                                                  :source mat,
                                                                  :running true})]
          (swap! cutter.cutter/the-window-state assoc :textures textures)
          (swap! cutter.cutter/the-window-state assoc :i-textures i-textures)
          (async/offer! queue (matInfo mat)))
          nil)

;Text
(defn write-text
  " (cutter.cutter_helper/write-text \"cutter\" 0 220 10 100 0.2 0.4 20 10 true) "
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

;Add Texture from file to texture-array
(defn add-to-buffer [filename buffername]
  (let [  texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
          buffername-key           (keyword buffername)
          texture-array            (buffername-key texture-arrays)
          running?                 false
          idx                      buffername
          maximum-buffer-length    (:maximum-buffer-length @cutter.cutter/the-window-state)
          bufdestination           (:destination texture-array)
          bufdestination           (if (nil? bufdestination) :iChannelNull bufdestination)
          running?                 (:running texture-array)
          running?                 (if (nil? running?) false running?)
          mode                     (:mode texture-array)
          mode                     (if (nil? mode) :fw mode)
          fps                      (:fps texture-array)
          fps                      (if (nil? fps) 30 fps)
          start-index              (:start-index texture-array)
          start-index              (if (nil? start-index) 0 start-index)
          stop-index               (:stop-index texture-array)
          stop-index               (if (nil? stop-index) maximum-buffer-length stop-index)
          source                   (:source texture-array)
          source                   (if (nil? source) [] source)
          mat                      (cutter.opencv/oc_load_image filename)
          source                   (if (< (count source) maximum-buffer-length) (conj source (matInfo mat)) source )
          newcount                 (count source)]
      (swap! cutter.cutter/the-window-state assoc :texture-arrays
        (assoc texture-arrays buffername-key (assoc texture-array :idx buffername
                                                                  :destination bufdestination
                                                                  :source source
                                                                  :running running?
                                                                  :fps fps
                                                                  :index 0
                                                                  :mode :fw
                                                                  :start-index start-index
                                                                  :stop-index (min maximum-buffer-length newcount))))) nil)

(defn add-from-dir [dir buffername]
  (let [dir    (clojure.java.io/file dir)
       files   (file-seq dir)
       files   (filter #(.isFile %) files)
       files   (mapv str files)]
       (doseq [x files ](add-to-buffer x buffername)   )))


;; (defn rfs []  (overtone.osc/osc-close (:osc-server @cutter.cutter/the-window-state))
;;               (overtone.osc/osc-close (:osc-client @cutter.cutter/the-window-state))
;;               (stop-cutter-local)
;;               (cutter.texturearray/stop-all-buffers)
;;               (cutter.camera/stop-all-cameras)
;;               (cutter.video/stop-all-videos)
;;               (refresh))
