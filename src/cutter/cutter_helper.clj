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

(defn list-cameras [] (println (:cameras @the-window-state)))

(defn list-videos [] (println (:videos @the-window-state)))

(defn list-textures [] (println (:textures @the-window-state)))

(defn list-texture-arrays [] (println (:texture-arrays @the-window-state)))

(defn list-camera-devices [] (println (:camera-devices @the-window-state)))

(defn list-video-filenames [] (println (:video-filenames @the-window-state)))

(defn list-texture-filenames [] (println (:texture-filenames @the-window-state)))

(defn list-texture-folders [] (println (:texture-folders @the-window-state)))

(defn set-texture_by_filename [filename destination] (oc_load_image filename))

(defn set-texture_by_idx [idx destination-texture-key]
  (let [  filenames           (:texture-filenames @cutter.cutter/the-window-state)
          filename            (nth filenames (mod idx (count filenames)))
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
          ]
          (async/offer! queue (matInfo mat))
          (swap! cutter.cutter/the-window-state assoc :i-textures i-textures)
          )
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