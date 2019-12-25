(ns #^{:author "Mikael Reponen"}
  cutter.opencv
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [watchtower.core :as watcher]
            [clojure.java.io :as io]
            [while-let.core :as while-let]
            [cutter.general :refel :all]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer sliding-buffer dropping-buffer close! thread
                     alts! alts!! timeout]]
            clojure.string)
  (:import
    [org.bytedeco.javacpp Pointer]
    [org.bytedeco.javacpp BytePointer]
    [org.bytedeco.javacpp v4l2]
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


(defn oc-mat-to-bytebuffer [mat] (let [height      (.height mat)
                                       width       (.width mat)
                                       channels    (.channels mat)
                                       size        (* height width channels)
                                       data        (byte-array size)
                                       _           (.get mat 0 0 data)]
                                       ^ByteBuffer (-> (BufferUtils/createByteBuffer size)
                                              (.put data)
                                              (.flip))))

(defn matInfo [mat] [(oc-mat-to-bytebuffer mat) ;(.dataAddr mat)
                     (.rows mat)
                     (.step1 mat)
                     (.elemSize1 mat)
                     (.height mat)
                     (.width mat)
                     (.channels mat)])



(defn oc-new-mat
([int_0 int_1 int_2 org_opencv_core_scalar_3 ]
  (new org.opencv.core.Mat int_0 int_1 int_2 org_opencv_core_scalar_3 ))
([org_opencv_core_size_0 int_1 org_opencv_core_scalar_2 ]
  (new org.opencv.core.Mat org_opencv_core_size_0 int_1 org_opencv_core_scalar_2 ))
([org_opencv_core_mat_0 org_opencv_core_range_1 ]
  (new org.opencv.core.Mat org_opencv_core_mat_0 org_opencv_core_range_1 ))
([long_0 ]
  (new org.opencv.core.Mat long_0 ))
([]
  (new org.opencv.core.Mat )))

(defn oc-tex-internal-format
 "return the internal-format for the glTexImage2D call for this image"
 ^Integer
 [image]
 (let [image-type      (.type image)
       internal-format (cond
                        (= image-type org.opencv.core.CvType/CV_8UC3)       GL11/GL_RGB8
                        (= image-type org.opencv.core.CvType/CV_8UC3)       GL11/GL_RGB8
                        (= image-type org.opencv.core.CvType/CV_8UC4)       GL11/GL_RGBA8
                        (= image-type org.opencv.core.CvType/CV_8UC4)       GL11/GL_RGBA8
                        :else GL11/GL_RGB8)]
   internal-format))

(defn oc-tex-format
  "return the format for the glTexImage2D call for this image"
  ^Integer
  [image]
  (let [image-type (.type image)
        format     (cond
                    (= image-type org.opencv.core.CvType/CV_8UC3)       GL12/GL_BGR
                    (= image-type org.opencv.core.CvType/CV_8UC3)       GL11/GL_RGB
                    (= image-type org.opencv.core.CvType/CV_8UC4)       GL12/GL_BGRA
                    (= image-type org.opencv.core.CvType/CV_8UC4)       GL11/GL_RGBA
                    :else GL12/GL_BGR)]
    format))

(defn oc-mat-to-bytebuffer [mat] (let [height      (.height mat)
                                       width       (.width mat)
                                       channels    (.channels mat)
                                       size        (* height width channels)
                                       data        (byte-array size)
                                       _           (.get mat 0 0 data)]
                                       ^ByteBuffer (-> (BufferUtils/createByteBuffer size)
                                              (.put data)
                                              (.flip))))


(defn oc_load_image [filename]
  (let [mat   (org.opencv.imgcodecs.Imgcodecs/imread filename org.opencv.imgcodecs.Imgcodecs/IMREAD_COLOR)]
  mat))


;;camera
(defn oc-set-capture-property [dispatch capture val]
  (let []
    (case dispatch
      :pos-msec           (.set capture org.opencv.videoio.Videoio/CAP_PROP_POS_MSEC  val)
      :pos-frames         (.set capture org.opencv.videoio.Videoio/CAP_PROP_POS_FRAMES   val)
      :pos-avi-ratio      (.set capture org.opencv.videoio.Videoio/CAP_PROP_POS_AVI_RATIO  val)
      :frame-width        (.set capture org.opencv.videoio.Videoio/CAP_PROP_FRAME_WIDTH  val)
      :frame-height       (.set capture org.opencv.videoio.Videoio/CAP_PROP_FRAME_HEIGHT  val)
      :fps                (.set capture org.opencv.videoio.Videoio/CAP_PROP_FPS  val)
      :fourcc             (.set capture org.opencv.videoio.Videoio/CAP_PROP_FOURCC   val)
      :frame-count        (.set capture org.opencv.videoio.Videoio/CAP_PROP_FRAME_COUNT  val)
      :brightness         (.set capture org.opencv.videoio.Videoio/CAP_PROP_BRIGHTNESS   val)
      :contrast           (.set capture org.opencv.videoio.Videoio/CAP_PROP_CONTRAST   val)
      :saturation         (.set capture org.opencv.videoio.Videoio/CAP_PROP_SATURATION   val)
      :hue                (.set capture org.opencv.videoio.Videoio/CAP_PROP_HUE   val)
      :default            (throw (Exception. "Unknown Property.")))))

(defn oc-get-capture-property [dispatch capture]
  (let []
    (case dispatch
      :pos-msec               (.get capture org.opencv.videoio.Videoio/CAP_PROP_POS_MSEC)
      :pos-frames             (.get capture org.opencv.videoio.Videoio/CAP_PROP_POS_FRAMES)
      :pos-avi-ratio          (.get capture org.opencv.videoio.Videoio/CAP_PROP_POS_AVI_RATIO)
      :frame-width            (.get capture org.opencv.videoio.Videoio/CAP_PROP_FRAME_WIDTH)
      :frame-height           (.get capture org.opencv.videoio.Videoio/CAP_PROP_FRAME_HEIGHT)
      :fps                    (.get capture org.opencv.videoio.Videoio/CAP_PROP_FPS)
      :fourcc                 (.get capture org.opencv.videoio.Videoio/CAP_PROP_FOURCC)
      :frame-count            (.get capture org.opencv.videoio.Videoio/CAP_PROP_FRAME_COUNT)
      :brightness             (.get capture org.opencv.videoio.Videoio/CAP_PROP_BRIGHTNESS)
      :contrast               (.get capture org.opencv.videoio.Videoio/CAP_PROP_CONTRAST)
      :saturation             (.get capture org.opencv.videoio.Videoio/CAP_PROP_SATURATION)
      :hue                    (.get capture org.opencv.videoio.Videoio/CAP_PROP_HUE)
      :default (throw (Exception. "Unknown Property.")))))

(defn oc-open-camera-device [camera-device]
  (let [vc  (new org.opencv.videoio.VideoCapture)
        vco (.open vc camera-device)]
        vc))

(defn oc-release [capture]
  (if (= nil capture) (println "nil") (do (println "release" capture) (.release capture))))

(defn oc-query-frame [capture buffer]
  (let [flag (.read capture buffer)]
    (if (= flag true) nil (Thread/sleep 100))
    buffer))

;(read-string (str (last "/dev/video0")))
