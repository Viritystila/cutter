(ns #^{:author "Mikael Reponen"}
  cutter.gl_init
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [watchtower.core :as watcher]
            [clojure.java.io :as io]
            [while-let.core :as while-let]
            [cutter.general :refer :all]
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


;Single texture OpenGL initialize
(defn init-texture
   [locals filename input_type]
   (let [target             (GL11/GL_TEXTURE_2D)
        tex-id              (GL11/glGenTextures)
        height              1
        width               1
        mat                 (org.opencv.core.Mat/zeros height width org.opencv.core.CvType/CV_8UC3)
        internal-format     (oc-tex-internal-format mat)
        format              (oc-tex-format mat)
        texture             {:filename filename,
                              :tex-id tex-id,
                              :height height,
                              :width, width
                              :mat mat
                              :internal-format internal-format,
                              :format format}
        texture-container   (input_type locals)]
        (swap! locals assoc input_type (assoc texture-container (key tex-id) texture))
        ; (reset! (nth (:target-cam @locals) cam-id) target)
        ; (reset! (nth (:text-id-cam @locals) cam-id) tex-id)
        ; (reset! (nth (:internal-format-cam @locals) cam-id) internal-format)
        ; (reset! (nth (:format-cam @locals) cam-id) format)
        ; (reset! (nth (:fps-cam @locals) cam-id) 1)
        ; (reset! (nth (:width-cam @locals) cam-id) width)
        ; (reset! (nth (:height-cam @locals) cam-id) height)
        (GL11/glBindTexture target tex-id)
        (GL11/glTexParameteri target GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
        (GL11/glTexParameteri target GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
        (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_S GL11/GL_REPEAT)
        (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_T GL11/GL_REPEAT)))
