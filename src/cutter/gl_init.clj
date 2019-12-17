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
   [width height target tex-id queue]
   (let [target             target
        tex-id              tex-id
        mat                 (org.opencv.core.Mat/zeros height width org.opencv.core.CvType/CV_8UC3)
        internal-format     (oc-tex-internal-format mat)
        format              (oc-tex-format mat)
        buffer              (oc-mat-to-bytebuffer mat)
        channels            (.channels mat)
        queue               queue
        mlt                 (clojure.core.async/mult queue)
        out1                (clojure.core.async/chan (async/buffer 1))
        _                   (clojure.core.async/tap mlt out1)
        texture             { :tex-id           tex-id,
                              :target           target,
                              :height           height,
                              :width            width
                              :mat              mat,
                              :buffer           buffer,
                              :internal-format  internal-format,
                              :format           format
                              :channels         channels,
                              :init-opengl      true
                              :queue            queue
                              :mult             mlt
                              :out1             out1}]
        ;(async/offer! queue (matInfo mat))
        (GL11/glBindTexture target tex-id)
        (GL11/glTexParameteri target GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
        (GL11/glTexParameteri target GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
        (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_S GL11/GL_REPEAT)
        (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_T GL11/GL_REPEAT)
        texture))


(defn initialize-texture [locals uniform-key width height]
  (let [{:keys [i-textures]} @locals
        i-textures (assoc i-textures uniform-key (init-texture width height (GL11/GL_TEXTURE_2D) (GL11/glGenTextures) (async/chan (async/buffer 1))))
        ]
               i-textures))
