(ns #^{:author "Mikael Reponen"}
  cutter.gl_init
  (:require ;[clojure.tools.namespace.repl :refer [refresh]]
                                        ;[watchtower.core :as watcher]
                                        ;[clojure.java.io :as io]
                                        ;[while-let.core :as while-let]
   [cutter.general :refer :all]
   [cutter.opencv :refer :all]
   [clojure.core.async
    :as async
    :refer [>! <! >!! <!! go go-loop chan sliding-buffer dropping-buffer close! thread
            alts! alts!! timeout]]
                                        ;clojure.string
   )
  (:import
                                        ;[org.bytedeco.javacpp Pointer]
                                        ;[org.bytedeco.javacpp BytePointer]
                                        ;[org.bytedeco.javacpp v4l2]
   [org.opencv.core Mat Core CvType]
                                        ;[org.opencv.videoio Videoio VideoCapture]
                                        ;[org.opencv.video Video]
                                        ;[org.opencv.imgproc Imgproc]
                                        ;[org.opencv.imgcodecs Imgcodecs]
                                        ; (java.awt.image BufferedImage DataBuffer DataBufferByte WritableRaster)
                                        ; (java.io File FileInputStream)
                                        ; (java.nio IntBuffer ByteBuffer FloatBuffer ByteOrder)
                                        ; (java.util Calendar)
                                        ; (java.util List)
                                        ; (javax.imageio ImageIO)
                                        ; (java.lang.reflect Field)
                                        ;(org.lwjgl BufferUtils)
                                        ;(org.lwjgl.glfw GLFW GLFWErrorCallback GLFWKeyCallback)
   (org.lwjgl.opengl GL GL11 GL12 GL13 GL15 GL20 GL21 GL30 GL40)
   )
  )


                                        ;Single texture OpenGL initialize
(defn init-texture
  [width height target tex-id queue out1 mlt pbo]
  (let [target             target
        tex-id              tex-id
        mat                 (org.opencv.core.Mat/zeros height width org.opencv.core.CvType/CV_8UC3)
        internal-format     (oc-tex-internal-format mat)
        format              (oc-tex-format mat)
        buffer              (oc-mat-to-bytebuffer mat)
        channels            (.channels mat)
        queue               queue
        mlt                 mlt
        out1                out1
        pbo                 pbo
        mat_step            (.step1 mat)
        mat_rows            (.rows mat)
        mat_size            (* mat_step mat_rows)
        ;_                   (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER pbo)
        ;_                   (GL15/glBufferData GL21/GL_PIXEL_PACK_BUFFER buffer GL30/GL_STREAM_DRAW)
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
                             :out1             out1
                             :pbo              pbo}]
    (GL11/glBindTexture target tex-id)
    (GL11/glTexParameteri target GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
    (GL11/glTexParameteri target GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
    (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_S GL11/GL_REPEAT)
    (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_T GL11/GL_REPEAT)
    (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER pbo)
    (GL15/glBufferData GL21/GL_PIXEL_PACK_BUFFER mat_size GL30/GL_STREAM_DRAW)
    (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER 0)
    texture))

(defn initialize-texture [locals uniform-key width height]
  (let [{:keys [i-textures]} @locals
        target  (GL11/GL_TEXTURE_2D)
        tex-id  (GL11/glGenTextures)
        queue   (async/chan (async/buffer 1))
        out1    (async/chan (async/buffer 1))
        mlt     (clojure.core.async/mult queue)
        _       (clojure.core.async/tap mlt out1)
        pbo     (GL15/glGenBuffers)
        i-textures (assoc i-textures uniform-key (init-texture width height
                                                               target
                                                               tex-id
                                                               queue
                                                               out1
                                                               mlt
                                                               pbo))]
    i-textures))
