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
