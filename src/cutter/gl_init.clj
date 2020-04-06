(ns #^{:author "Mikael Reponen"}
  cutter.gl_init
  (:require
   [cutter.general :refer :all]
   [cutter.opencv :refer :all]
   [clojure.core.async
    :as async
    :refer [>! <! >!! <!! go go-loop chan sliding-buffer dropping-buffer close! thread
            alts! alts!! timeout]] )
  (:import
   [org.opencv.core Mat Core CvType]
   (org.lwjgl.opengl GL GL11 GL12 GL13 GL15 GL20 GL21 GL30 GL40 GL44 GL45)
   ))


                                        ;Single texture OpenGL initialize
(defn init-texture
  [width height target tex-id queue out1 mlt req pbo]
  (let [target              target
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
        gl_buffer           1
        flags               (bit-or GL30/GL_MAP_WRITE_BIT GL45/GL_MAP_PERSISTENT_BIT GL44/GL_MAP_COHERENT_BIT )
        _                   (GL15/glBindBuffer GL21/GL_PIXEL_UNPACK_BUFFER pbo)
        _                   (GL44/glBufferStorage GL21/GL_PIXEL_UNPACK_BUFFER (long mat_size) flags)
        gl_buffer           (GL44/glMapBufferRange GL21/GL_PIXEL_UNPACK_BUFFER 0 mat_size flags)
        mat                 (new org.opencv.core.Mat height width  org.opencv.core.CvType/CV_8UC3 gl_buffer 0)
        _                   (GL15/glBindBuffer GL21/GL_PIXEL_UNPACK_BUFFER 0)
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
                             :req              req
                             :pbo              pbo
                             :gl_buffer        gl_buffer}]
    (GL11/glBindTexture target tex-id)
    (GL11/glTexParameteri target GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
    (GL11/glTexParameteri target GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
    (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_S GL11/GL_REPEAT)
    (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_T GL11/GL_REPEAT)
    (GL11/glBindTexture target 0)
    texture))

(defn initialize-texture [locals uniform-key width height]
  (let [{:keys [i-textures]} @locals
        target  (GL11/GL_TEXTURE_2D)
        tex-id  (GL11/glGenTextures)
        queue   (async/chan (async/buffer 1))
        out1    (async/chan (async/buffer 1))
        mlt     (clojure.core.async/mult queue)
        req     (async/chan (async/buffer 1))
        _       (clojure.core.async/tap mlt out1)
        pbo     (GL15/glGenBuffers)
        i-textures (assoc i-textures uniform-key (init-texture width height
                                                               target
                                                               tex-id
                                                               queue
                                                               out1
                                                               mlt
                                                               req
                                                               pbo))]
    i-textures))
