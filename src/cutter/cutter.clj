(ns #^{:author "Mikael Reponen"
       :doc " Core library derived from Shadertone (Roger Allen https://github.com/overtone/shadertone)."}
  cutter.cutter
  (:use [overtone.osc])
  (:require
   [while-let.core :as while-let]
   [cutter.shader :refer :all]
   [cutter.general :refer :all]
   [cutter.gl_init :refer :all]
   [cutter.opencv :refer :all]
   [clojure.edn :as edn]
   [overtone.core]
   [clojure.core.async
    :as async
    :refer [>! <! >!! <!! go go-loop chan sliding-buffer dropping-buffer close! thread
            alts! alts!! timeout]]
   [clojure.math.numeric-tower :as math])
  (:import
   [java.io File FileInputStream]
   [java.nio IntBuffer ByteBuffer FloatBuffer ByteOrder]
   [org.lwjgl BufferUtils]
   [org.lwjgl.system MemoryUtil]
   [org.lwjgl.assimp Assimp]
   [org.lwjgl.glfw GLFW GLFWErrorCallback GLFWKeyCallback]
   [org.lwjgl.opengl GL GL11 GL12 GL13 GL15 GL20 GL21 GL30 GL40 GL44 GL45]
   ;;[org.freedesktop.gstreamer Gst Pipeline Caps Version Buffer BufferPool ElementFactory elements.AppSrc]
   ))

;;(Gst/init Version/BASELINE "Cutter-output" (into-array [""]) )

;(def pipeline2 (Gst/parseLaunch "videotestsrc is-live=1 ! video/x-raw,format=YUY2,framerate=30/1 ! videoconvert ! autovideosink" ))

;;(def pipeline (Gst/parseLaunch "appsrc name=src ! video/x-raw,format=RGB,framerate=30/1,width=1280, height=800 !  capsfilter ! videoconvert ! glimagesink"))


;; (def pipeline (Gst/parseLaunch "appsrc name=src ! video/x-raw,format=RGB,framerate=30/1,width=1280, height=800 !  capsfilter !  pipewiresink mode=provide stream-properties=props,media.class=Video/Source,node.description=Cutter_output,node.name=Cutter"))


;; (def appsrc (.getElementByName pipeline "src"))

;; (.set appsrc "emit-signals" true)

;; (.setStreamType appsrc org.freedesktop.gstreamer.elements.AppSrc$StreamType/STREAM)

;; (def data (byte-array (* 1280 800 3)) )

;; (java.util.Arrays/fill data (byte 112))


;; (defn nd
;;   []
;;   (reify org.freedesktop.gstreamer.elements.AppSrc$NEED_DATA
;;     (needData [this elem size]
;;       ;(println elem)
;;       ;(println size)
;;       ;;(def data (byte-array (* 1280 800 3)))
;;       ;;(java.util.Arrays/fill data (byte 122))
;;       ;(def data (byte-array (* 1280 800 3)) )
;;       (java.util.Arrays/fill data (byte (+ 100 (rand-int 12))))
;;       (def gsbuf (new Buffer (* 1280 800 3)))
;;       (.put (.map gsbuf true) data)
;;                                         ;(Thread/sleep 10)
;;       (.unmap gsbuf)
;;       (.pushBuffer appsrc gsbuf)
;;       ;;(.unmap buf)
;;       )
;;     )
;;   )

;; (def nnd (nd))

;; (defn ed
;;   []
;;   (reify org.freedesktop.gstreamer.elements.AppSrc$ENOUGH_DATA
;;     (enoughData [this elem]
;;        ;(println "ei asiaa")
;;       )))


;; (def ned (ed))

;; (.connect appsrc nnd)

;; (.connect appsrc ned)

;;(.play pipeline)

;;(.stop pipeline)

;;(.intValue (.getState pipeline))

(defonce default-state-values
  {:gst_init                   "";;(Gst/init Version/BASELINE "Cutter-output" (into-array [""]) )
   :gst_output                 ""
   :active                     :no  ;; :yes/:stopping/:no
   :width                      0
   :height                     0
   :title                      ""
   :display-sync-hz            30
   :start-time                 0
   :last-time                  0
   :window                     nil
   :keyCallback                nil
   :current-frame              (atom 1)
                                        ;OSC
   :osc-server                 (overtone.osc/osc-server 44110 "cutter-osc")
   :osc-client                 (overtone.osc/osc-client "localhost" 44110)
   :osc-port                   44110
   ;; geom ids
   :vbo-id                     0
   :vertices-count             0
   :vao-id                     0
   :vboc-id                    0
   :vboi-id                    0
   :indices-count              0
   :vbot-id                    0
   :vbon-id                    0

   :buffer-objects             {}
                                        ;{:id {:vao 0, :verticex-count 0, :indices-count 0,  :vbo-id, 0 :vbo-buffer 0, :vboc-id 0, :color-buffer 0, :vboi-id 0, :index-buffer 0, :vbot-id 0, :uv-buffer 0, :vbon-id 0, :normal-buffer 0 }
   ;; shader program
   ;; Pixel buffers
   :outputPBOs                 0
   :shader-ver                 "#version 460 core"
   :shader-good                true ;; false in error condition
   :shader-filename            nil
   :shader-str-atom            (atom nil)
   :shader-str                 ""
   :vs-shader-good             true ;; false in error condition
   :vs-shader-filename         nil
   :vs-shader-str-atom         (atom nil)
   :vs-shader-str               ""
   :gs-shader-good             true ;; false in error condition
   :gs-shader-filename         nil
   :gs-shader-str-atom         (atom nil)
   :gs-shader-str               ""
   :vs-id                      0
   :fs-id                      0
   :gs-id                      0
   :ts-id                      0
   :pgm-id                     0
   :temp-fs-string             ""
   :temp-vs-string             ""
   :temp-gs-string             ""
   :temp-fs-filename           ""
   :temp-vs-filename           ""
   :temp-gs-filename           ""
   ;; Textures, cameras and video paths
   :maximum-textures           1000
   :maximum-texture-folders    1000
   :maximum-cameras            1000
   :maximum-videos             1000
   :maximum-buffer-length      25000  ;Frames
   :default-buffer-length      250
   :request-buffers            (atom false)
   :requested-buffer           (atom {})
   :request-reset              (atom "")
   :request-queue              (async/chan (async/buffer 100))
   :textures                   {} ;{:filename, {:idx :destination :source "mat" :running false :queue -1}}
   :texture-arrays             {} ;{:name, {:idx :destination :source "buf array" :running false, :fps 30, index: 0, :mode :fw, :loop true, :start-index 0, :stop-index 0, pbo_ids 0, :queue -1}
   :cameras                    {} ;{:device, {:idx :destination :source "capture" :running false, :fps 30, index: 0, :start-index 0, :stop-index 0, :queue -1}}
   :videos                     {} ;{:filename, {:idx :destination :source "capture" :running false, :fps 30, :queue -1, :mode :fw, :pos 0 }}
                                        ;Data Arrays
   :maxDataArrays              16
   :maxDataArraysLength        256
                                        ;v4l2
   :save-frames                (atom false)
   :deviceName                 (atom "/dev/video5")
   :deviceId                   (atom 0)
   :minsize                    (atom 0)
   :bff                        (atom 0)
   :isInitialized              (atom false)
   :v4l2_buffer                0
   ;; shader uniforms
   :no-i-channels              16
   :i-channels                 (mapv (fn [x] (keyword (str "iChannel" x))) (range 1 16 1))
   :i-extra-texture-names      [:iPreviousFrame :iText :iChannelNull]
   :i-extra-uniform-names      [:iMeshID :iResolution :iGlobalTime :iRandom :iPreviousFrame :iText]
   :i-extra-uniform-types      {:iMeshID           {:type "int",     :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)), :extra "", :layout "", :unit -1}
                                :iResolution       {:type "vec3",      :loc 0, :gltype (fn [id x y z] (GL20/glUniform3f id x y z)),  :extra "", :layout "", :unit -1},
                                :iGlobalTime       {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)), :extra "", :layout "", :unit -1}
                                :iRandom           {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)), :extra "", :layout "", :unit -1},
                                :iPreviousFrame    {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)), :extra "", :layout "", :unit 1},
                                :iText             {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)), :extra "", :layout "", :unit 2},
                                :iChannelNull      {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)), :extra "", :layout "", :unit -1},
                                :iChannelX         {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)), :extra "", :layout "", :unit 3},
                                :iDataArrayX       {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                 :iFloatX          {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),:extra "", :layout "", :unit -1}}
   :i-dataArrays               (into {} (mapv (fn [x] {(keyword (str "iDataArray" x)) {:datavec (vec (make-array Float/TYPE 256)), :buffer (-> (BufferUtils/createFloatBuffer 256)
                                                                                                                                              (.put (float-array
                                                                                                                                                     (vec (make-array Float/TYPE 256))))
                                                                                                                                              (.flip))} } ) (range 1 16 1)))
   :i-floats                   (into {} (mapv (fn [x] {(keyword (str "iFloat" x)) {:data 0 }}) (range 1 17 1)))
})
;; GLOBAL STATE ATOMS

(defonce the-window-state (atom default-state-values))

(defn set-default-state [state & {:keys  [maxDataArraysLength]
                                  :or    {maxDataArraysLength 265}
                                  :as    all-specified}]
                                        (println all-specified)
  (let [texture_map    {:tex-id 0,
                        :target 0,
                        :height 1,
                        :width 1,
                        :mat 0,
                        :buffer 0,
                        :internal-format -1,
                        :format -1,
                        :channels 3,
                        :init-opengl true,
                        :queue 0,
                        :mult 0,
                        :out1 0,
                        :req  0,
                        :pbo -1,
                        :gl_buffer -1}
        unit_no        (atom 2)]
    (doseq [x (keys all-specified)]
      (case x
        :maxDataArraysLength       (swap! state assoc x
                                          (x all-specified))
        :i-channels                (swap! state assoc x
                                          (mapv
                                           (fn [x] (keyword (str "iChannel" x)))
                                           (range 1 (+ 1 (x all-specified)) 1)))
        :i-dataArrays              (swap! state assoc x
                                          (into {}
                                                (mapv (fn [x] {(keyword (str "iDataArray" x))
                                                              {:datavec (vec (make-array Float/TYPE (:maxDataArraysLength @state) )), :buffer (-> (BufferUtils/createFloatBuffer  (:maxDataArraysLength @state)) (.put (float-array  (vec (make-array Float/TYPE  (:maxDataArraysLength @state)))))(.flip))} } ) (range 1  (+ 1 (x all-specified)) 1))))
        :i-floats                  (swap! state assoc x (into {} (mapv (fn [x] {(keyword (str "iFloat" x)) {:data 0 }}) (range 1 (+ 1 (x all-specified)) 1))))
        "default"))
    (swap! state assoc :i-uniforms
           (merge
            (into {}
                  (map (fn [x] [x (x (:i-extra-uniform-types @state))]) (:i-extra-uniform-names @state)))
            (into {}
                  (map (fn [x] (swap! unit_no inc) [x (assoc (:iChannelX (:i-extra-uniform-types @state)) :unit @unit_no)]) (:i-channels @state)))
            (into {}
                  (map (fn [x] [(first x) (:iDataArrayX (:i-extra-uniform-types @state))]) (:i-dataArrays @state)))
            (into {}
                  (map (fn [x] [(first x) (:iFloatX (:i-extra-uniform-types @state))]) (:i-floats @state)))))

    (swap! state assoc :i-textures
           (merge
            (into {} (map (fn [x] [x  texture_map]) (:i-extra-texture-names @state) ))
            (into {} (map (fn [x] [x  texture_map]) (:i-channels @state)))))
    )
  nil
  )

 (cutter.cutter/set-default-state cutter.cutter/the-window-state :maxDataArraysLength 256 :i-channels 16 :i-dataArrays 16 :i-floats 16)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;Init window and opengl;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn undecorate-display!
  "Borderless window"
  []
  (org.lwjgl.glfw.GLFW/glfwDefaultWindowHints)
  (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_VISIBLE                org.lwjgl.glfw.GLFW/GLFW_FALSE)
  (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_RESIZABLE              org.lwjgl.glfw.GLFW/GLFW_FALSE)
  (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_DECORATED              org.lwjgl.glfw.GLFW/GLFW_FALSE))

(defn fullscreen-display!
  "Fullscreen"
  []
  (org.lwjgl.glfw.GLFW/glfwDefaultWindowHints)
  (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_VISIBLE                org.lwjgl.glfw.GLFW/GLFW_TRUE)
  (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_RESIZABLE              org.lwjgl.glfw.GLFW/GLFW_FALSE)
  (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_DECORATED              org.lwjgl.glfw.GLFW/GLFW_FALSE)
  (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_AUTO_ICONIFY           org.lwjgl.glfw.GLFW/GLFW_FALSE))

(defn getMonitor [input true-fullscreen?]
  (let [monitors            (org.lwjgl.glfw.GLFW/glfwGetMonitors)
        number_of_monitors  (.capacity monitors)
        monitor-id          (.get (org.lwjgl.glfw.GLFW/glfwGetMonitors) (mod input number_of_monitors))]
    (if true-fullscreen?  monitor-id 0)))

(defn- init-window
  "Initialise a shader-powered window with the specified
   display-mode. If true-fullscreen? is true, fullscreen mode is
   attempted."
  [locals
   display-mode
   title
   shader-filename
   shader-str-atom
   vs-shader-filename
   vs-shader-str-atom
   gs-shader-filename
   gs-shader-str-atom
   true-fullscreen?
   display-sync-hz
   window-idx]
  (when-not (org.lwjgl.glfw.GLFW/glfwInit)
    (throw (IllegalStateException. "Unable to initialize GLFW")))
  (let [primaryMonitor      (org.lwjgl.glfw.GLFW/glfwGetPrimaryMonitor)
        currentMonitor      (getMonitor window-idx true-fullscreen?)
        current-time-millis (System/currentTimeMillis)
        width               (nth display-mode 0)
        height              (nth display-mode 1)
        shader-str          (if (nil? shader-filename)
                              @shader-str-atom
                              (slurp-fs locals shader-filename :fs))
        vs-shader-str       (if (nil? vs-shader-filename)
                              @vs-shader-str-atom
                              (slurp-fs locals  vs-shader-filename :vs))
        gs-shader-str       (if (nil? gs-shader-filename)
                              @gs-shader-str-atom
                              (slurp-fs locals  gs-shader-filename :gs))]
    (swap! locals
           assoc
           :active                :yes
           :width                 width
           :height                height
           :title                 title
           :display-sync-hz       display-sync-hz
           :start-time            current-time-millis
           :last-time             current-time-millis
           :shader-filename       shader-filename
           :shader-str-atom       shader-str-atom
           :vs-shader-filename    vs-shader-filename
           :vs-shader-str-atom    vs-shader-str-atom
           :gs-shader-filename    gs-shader-filename
           :gs-shader-str-atom    gs-shader-str-atom
           :shader-str            shader-str
           :vs-shader-str         vs-shader-str
           :gs-shader-str         gs-shader-str)
    (if true-fullscreen? (fullscreen-display!) (undecorate-display!) )
    (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_OPENGL_CORE_PROFILE    org.lwjgl.glfw.GLFW/GLFW_OPENGL_CORE_PROFILE)
    (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_OPENGL_FORWARD_COMPAT  org.lwjgl.glfw.GLFW/GLFW_FALSE)
    (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_CONTEXT_VERSION_MAJOR  4)
    (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_CONTEXT_VERSION_MINOR  6)
    (swap! locals assoc
           :window (org.lwjgl.glfw.GLFW/glfwCreateWindow width height title currentMonitor 0))
    (when (= (:window @locals) nil)
      (throw (RuntimeException. "Failed to create the GLFW window.")))
    (swap! locals assoc
           :keyCallback
           (proxy [GLFWKeyCallback] []
             (invoke [window key scancode action mods]
               (when (and (= key org.lwjgl.glfw.GLFW/GLFW_KEY_ESCAPE)
                          (= action org.lwjgl.glfw.GLFW/GLFW_RELEASE))
                 (org.lwjgl.glfw.GLFW/glfwSetWindowShouldClose (:window @locals) true)))))
    (org.lwjgl.glfw.GLFW/glfwSetKeyCallback       (:window @locals) (:keyCallback @locals))
    (org.lwjgl.glfw.GLFW/glfwMakeContextCurrent   (:window @locals))
    (org.lwjgl.glfw.GLFW/glfwSwapInterval         2)
    (org.lwjgl.glfw.GLFW/glfwShowWindow           (:window @locals))))


(defn add-mesh [path locals]
  (println path)
  (let [buffer-objects            (:buffer-objects @the-window-state)
        md                        (cutter.gl_init/init-mesh path)
        mdk                       (keyword (str (:vao-id md)))
        buffer-objects            (assoc buffer-objects mdk md)]
    (swap! locals assoc :buffer-objects buffer-objects)))

(defn- init-buffers
  [locals]
  ;(println "max veretx atrribs"  (org.lwjgl.opengl.GL11/glGetInteger org.lwjgl.opengl.GL20/GL_MAX_VERTEX_ATTRIBS))
  (let [buffer-objects            (:buffer-objects @locals)
        ;vertices_and_indices     (cutter.cutter/load-plane)
        meshpaths                 [ (cutter.general/resource-to-temp "plane.dae")]
        buffer-objects            (into {} (mapv (fn [x]
                                                   (let [md    (cutter.gl_init/init-mesh x)
                                                         mdk   (keyword (str (:vao-id md)))]
                                                     {mdk md})) meshpaths ))
        pbo_size            (* 3 (:width @locals) (:height @locals) )
        pboi_1_id           (GL15/glGenBuffers)
        ;pboi_2_id           (GL15/glGenBuffers)
        v4l2_buffer         1
        flags               (bit-or GL30/GL_MAP_READ_BIT GL45/GL_MAP_PERSISTENT_BIT GL44/GL_MAP_COHERENT_BIT )
        _                   (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER pboi_1_id)
        _                   (GL44/glBufferStorage  GL21/GL_PIXEL_PACK_BUFFER (long pbo_size) flags)
        v4l2_buffer         (GL44/glMapBufferRange GL21/GL_PIXEL_PACK_BUFFER 0 pbo_size flags)
        _                   (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER 0)
        _ (except-gl-errors "@ end of init-buffers")]
    (swap! locals
           assoc
           :buffer-objects  buffer-objects
           :vbo-id 0;vbo-id
           :vao-id 0;vao-id
           :vboc-id 0;vboc-id
           :vboi-id 0;vboi-id
           :vbot-id 0;vbot-id
           :vbon-id 0;vbon-id
           :vertices-count 0;vertices-count
           :indices-count 0;indices-count
           :outputPBOs    [pboi_1_id pboi_1_id]
           :v4l2_buffer   v4l2_buffer)))

(defn- init-gl
  [locals]
  (let [{:keys [width height i-channels]} @locals]
    (GL/createCapabilities)
    (println "OpenGL version:" (GL11/glGetString GL11/GL_VERSION))
    (GL11/glClearColor 0.0 0.0 0.0 0.0)
    (GL11/glViewport 0 0 width height)
    (GL11/glEnable GL11/GL_DEPTH_TEST)
    (GL11/glDepthFunc GL11/GL_LESS)
    (GL11/glEnable GL11/GL_BLEND)
    (GL11/glBlendFunc GL11/GL_SRC_ALPHA GL11/GL_ONE_MINUS_SRC_ALPHA)
    (GL11/glEnable GL11/GL_CULL_FACE)
    (init-buffers locals)
    (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iPreviousFrame width height))
    (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iText width height))
    (doseq [x i-channels]
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals x width height)))
    (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannelNull width height))
    (init-shaders locals)))

(defn- set-texture [tex-image-target internal-format width height format buffer]
  (try (GL11/glTexImage2D ^Integer tex-image-target 0 ^Integer internal-format
                          ^Integer width  ^Integer height 0
                          ^Integer format
                          GL11/GL_UNSIGNED_BYTE
                          buffer)))

(defn- set-texture-pbo [tex-image-target width height format pbo]
  (try
    (if (GL15/glIsBuffer pbo)
      (do
        (GL15/glBindBuffer GL21/GL_PIXEL_UNPACK_BUFFER pbo)
                                        ;(println pbo)
        (GL11/glTexSubImage2D ^Integer tex-image-target 0 0 0
                              ^Integer width  ^Integer height
                              ^Integer format
                              GL11/GL_UNSIGNED_BYTE
                              0)
        (GL15/glBindBuffer GL21/GL_PIXEL_UNPACK_BUFFER 0)))))


(defn- set-opengl-texture [locals texture-key buffer width height image-bytes img-addr pbo_id]
  (let[i-textures          (:i-textures @locals )
       texture             (texture-key i-textures)
       target              (:target texture)
       internal-format     (:internal-format texture)
       format              (:format texture)
       wset                (:width texture)
       hset                (:height texture)
       bset                (:channels texture)
       init?               (:init-opengl texture)
       tex-id              (:tex-id texture)
       queue               (:queue texture)
       pbo                 (:pbo texture)
       gl_buffer           (:gl_buffer texture)
       height              height ;(nth image 4)
       width               width ;(nth image 5)
       image-bytes         image-bytes; (nth image 6)
       setnbytes           (* wset hset bset)
       tex-image-target    ^Integer (+ 0 target)
       nbytes              (* width height image-bytes)
       buffer              buffer]
    ;;(println "before choice" width height image-bytes)
    (if (or init? (not= setnbytes nbytes))
      (do
        (set-texture tex-image-target internal-format width height format buffer)
        (let [queue               (:queue texture)
              out1                (:out1 texture)
              mlt                 (:mult texture)
              req                 (:req texture)
              pbo                 (:pbo texture)
              gl_buffer           (:gl_buffer texture)
              _                   (GL15/glBindBuffer GL21/GL_PIXEL_UNPACK_BUFFER pbo)
              _                   (GL15/glUnmapBuffer  GL21/GL_PIXEL_UNPACK_BUFFER)
              _                   (GL15/glBindBuffer GL21/GL_PIXEL_UNPACK_BUFFER 0)
              _                   (GL30/glDeleteBuffers pbo)
              pbo                 (GL15/glGenBuffers)
              texture     (init-texture width height target tex-id queue out1 mlt req pbo)
              texture     (assoc texture :init-opengl false)
              i-textures  (assoc i-textures texture-key texture)]
          (swap! locals assoc :i-textures i-textures)))
      (do
        (if (< 0 img-addr)
           ;;(set-texture tex-image-target internal-format width height format buffer)
          (cutter.cutter/set-texture-pbo tex-image-target width height format pbo_id)
          )))
    (except-gl-errors "@ end of load-texture if-stmt")))

                                        ;
(defn- get-textures
  [locals texture-key i-uniforms]
  (let [i-textures          (:i-textures @locals)
        texture             (texture-key i-textures)
        queue               (:queue texture)
        out1                (:out1 texture)
        unit                (:unit (texture-key i-uniforms))
        tex-id              (:tex-id texture)
        target              (:target texture)
        image               (if (= nil queue) nil (async/poll! out1))]
    (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 unit))
    (GL11/glBindTexture target tex-id)
    (if  (not (nil? image))
      (do
        ;;(println image)
        (if
            (instance? java.lang.Long (nth image 0)) (do (set-opengl-texture
                                                          locals
                                                          texture-key
                                                          0
                                                          (nth image 5)
                                                          (nth image 4)
                                                          (nth image 6)
                                                          (nth image 0)
                                                          (last image))
                                                         )
            (do (set-opengl-texture
                 locals
                 texture-key
                 (nth image 0)
                 (nth image 5)
                 (nth image 4)
                 (nth image 6)
                 1
                 (last image))
                )))  nil)))

(defn- draw [locals]
  (let [{:keys [width height
                start-time last-time i-global-time-loc
                pgm-id vbo-id vao-id vboi-id vboc-id vbot-id vbon-id outputPBOs
                vertices-count
                indices-count
                i-uniforms
                i-textures
                i-channels
                i-dataArrays
                i-floats
                save-frames
                old-pgm-id old-fs-id
                buffer-objects
                ]} @locals
        cur-time    (/ (- last-time start-time) 1000.0)
        mesh-index                              (atom 0)]
    (except-gl-errors "@ draw before clear")
    (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_DEPTH_BUFFER_BIT))
    (except-gl-errors "@ draw after activate textures")
    ((:gltype (:iResolution i-uniforms)) (:loc (:iResolution i-uniforms)) width height 1.0)
    ((:gltype (:iGlobalTime i-uniforms)) (:loc (:iGlobalTime i-uniforms)) cur-time)
    ((:gltype (:iRandom i-uniforms)) (:loc (:iRandom i-uniforms)) (rand))
    ((:gltype (:iPreviousFrame i-uniforms)) (:loc (:iPreviousFrame i-uniforms)) (:unit (:iPreviousFrame i-uniforms)))
    (get-textures locals :iPreviousFrame i-uniforms)
    ((:gltype (:iText i-uniforms)) (:loc (:iText i-uniforms)) (:unit (:iText i-uniforms)))
    (get-textures locals :iText i-uniforms)
    (doseq [x (keys i-dataArrays)]
      ((:gltype (x i-uniforms)) (:loc (x i-uniforms)) (:datavec (x i-dataArrays)) (:buffer (x i-dataArrays))))
    (doseq [x (keys i-floats)]
      ((:gltype (x i-uniforms)) (:loc (x i-uniforms)) (:data (x i-floats)) ))
    (doseq [x i-channels]
      ((:gltype (x i-uniforms)) (:loc (x i-uniforms)) (:unit (x i-uniforms)))
      (get-textures locals x i-uniforms))
    (doseq [mesh-data (vals buffer-objects)]
      (let  [mesh-vao-id         (:vao-id mesh-data)
             mesh-vbo-id         (:vbo-id mesh-data)
             mesh-vboc-id        (:vboc-id mesh-data)
             mesh-vboi-id        (:vboi-id mesh-data)
             mesh-vbot-id        (:vbot-id mesh-data)
             mesh-vbon-id        (:vbon-id mesh-data)
             mesh-indices-count  (:indices-count mesh-data)]
        ((:gltype (:iMeshID i-uniforms)) (:loc (:iMeshID i-uniforms)) @mesh-index)
        ;(println mesh-vao-id)
        (GL30/glBindVertexArray mesh-vao-id)
        (GL20/glEnableVertexAttribArray 0)
        (GL20/glEnableVertexAttribArray 1)
        (GL20/glEnableVertexAttribArray 2)
        (GL20/glEnableVertexAttribArray 3)
        (GL20/glEnableVertexAttribArray 4)
        (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER mesh-vbo-id)
        (GL20/glVertexAttribPointer 0 3 GL11/GL_FLOAT false 0 0);
        (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER mesh-vboc-id)
        (GL20/glVertexAttribPointer 1 3 GL11/GL_FLOAT false 0 0);
        (GL15/glBindBuffer GL15/GL_ELEMENT_ARRAY_BUFFER mesh-vboi-id)
        (GL20/glVertexAttribPointer 2 1 GL11/GL_INT false 0 0);
        (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER mesh-vbot-id)
        (GL20/glVertexAttribPointer 3 2 GL11/GL_FLOAT false 0 0);
        (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER mesh-vbon-id)
        (GL20/glVertexAttribPointer 4 3 GL11/GL_FLOAT false 0 0);
        (GL11/glDrawElements  GL11/GL_TRIANGLES mesh-indices-count GL11/GL_UNSIGNED_INT 0)
        )
      (swap! mesh-index inc))
    (except-gl-errors "@ draw after DrawArrays")
    ;;Copying the previous image to its own texture
    (GL11/glBindTexture GL11/GL_TEXTURE_2D (:tex-id (:iPreviousFrame i-textures)))
    (GL11/glCopyTexImage2D GL11/GL_TEXTURE_2D 0 GL11/GL_RGB8 0 0 width height 0)
    (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)
    (if @save-frames
      (do ; download it and copy the previous image to its own texture
        (GL11/glReadBuffer GL11/GL_FRONT)
        (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER (first outputPBOs))
        (GL11/glBindTexture GL11/GL_TEXTURE_2D (:tex-id (:iPreviousFrame i-textures)))
        (GL11/glReadPixels 0 0 width height GL11/GL_RGB GL11/GL_UNSIGNED_BYTE  0)
        (let [v4l2_buffer (:v4l2_buffer @the-window-state)
              bb          (new org.bytedeco.javacpp.BytePointer v4l2_buffer)
              minsize     (long  @(:minsize @the-window-state))]
          (org.bytedeco.javacpp.v4l2/v4l2_write @(:deviceId @the-window-state) bb minsize )
          (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER 0)
          (.deallocate bb)
          )
        (GL11/glBindTexture GL11/GL_TEXTURE_2D 0) )
      nil)
    ))

(defn- update-and-draw
  [locals]
  (let [{:keys [width height last-time pgm-id
                ]} @locals
        cur-time (System/currentTimeMillis)]
    (swap! locals
           assoc
           :last-time cur-time)
    (if (and
         (:shader-good @locals)
         (:vs-shader-good @locals)
         (:gs-shader-good @locals))
      (do
        (if (or @reload-shader @vs-reload-shader @gs-reload-shader)
          (try-reload-shader locals)  ; this must call glUseProgram
          (GL20/glUseProgram pgm-id)) ; else, normal path...
        (draw locals))
      ;; else clear to prevent strobing awfulness
      (do
        (GL11/glClear GL11/GL_COLOR_BUFFER_BIT)
        (except-gl-errors "@ bad-draw glClear ")
        (if (or @reload-shader @vs-reload-shader)
          (try-reload-shader locals))))))


(defn delete-PBO-buf [id]
  ;(println id)
  (GL15/glBindBuffer GL21/GL_PIXEL_UNPACK_BUFFER id)
  (GL15/glUnmapBuffer  GL21/GL_PIXEL_UNPACK_BUFFER)
  (GL30/glDeleteBuffers  id)
  (GL15/glBindBuffer GL21/GL_PIXEL_UNPACK_BUFFER 0))


(defn- destroy-gl
  [locals]
  (let [{:keys [pgm-id vs-id fs-id vbo-id vao-id user-fn  outputPBOs i-textures texture-arrays]} @locals]
    ;; Delete the shaders
    (GL20/glUseProgram 0)
    (GL20/glDetachShader pgm-id vs-id)
    (GL20/glDetachShader pgm-id fs-id)
    (GL20/glDeleteShader vs-id)
    (GL20/glDeleteShader fs-id)
    (GL20/glDeleteProgram pgm-id)
    ;; ;; Select the VAO
    ;; (GL30/glBindVertexArray vao-id)
    ;; (GL20/glDisableVertexAttribArray 0)
    ;; (GL20/glDisableVertexAttribArray 1)
    ;; (GL20/glDisableVertexAttribArray 2)
    ;; (GL20/glDisableVertexAttribArray 3)
    ;; (GL20/glDisableVertexAttribArray 4)
    ;; ;; Delete the vertex VBO
    ;; (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER 0)
    ;; (GL15/glDeleteBuffers ^Integer vbo-id)
    ;; ;; Delete the VAO
    ;; (GL30/glBindVertexArray 0)
    ;; (GL30/glDeleteVertexArrays vao-id)
    ;; Delete pbo
    (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER  (first outputPBOs))
    (GL15/glUnmapBuffer  GL21/GL_PIXEL_PACK_BUFFER)
    (GL15/glBindBuffer GL21/GL_PIXEL_PACK_BUFFER  0)
    (GL30/glDeleteBuffers   (first outputPBOs))
    ;;Delete buffers
    (doseq [x (keys texture-arrays)] (let [pbos   (:pbo_ids (x texture-arrays))]
                                       (doseq [y pbos] (delete-PBO-buf y))))
    ;;Delete texture pbos
    (doseq [x (keys i-textures)] (delete-PBO-buf (:pbo (x i-textures))))
    ))

(defn create-PBO-buf ([width height channels]
                      (let[flags     (bit-or GL30/GL_MAP_WRITE_BIT GL45/GL_MAP_PERSISTENT_BIT GL44/GL_MAP_COHERENT_BIT )
                           id        (GL15/glGenBuffers)
                           mat_size  (* width height channels)
                           _         (GL15/glBindBuffer GL21/GL_PIXEL_UNPACK_BUFFER id)
                           _         (GL44/glBufferStorage GL21/GL_PIXEL_UNPACK_BUFFER (long mat_size) flags)
                           gl_buffer (GL44/glMapBufferRange GL21/GL_PIXEL_UNPACK_BUFFER 0 mat_size flags)]
                        (GL15/glBindBuffer GL21/GL_PIXEL_UNPACK_BUFFER 0)
                        [id gl_buffer]))
  ([width height channels old_buf]
   (let[flags     (bit-or GL30/GL_MAP_WRITE_BIT GL45/GL_MAP_PERSISTENT_BIT GL44/GL_MAP_COHERENT_BIT )
        id        (GL15/glGenBuffers)
        mat_size  (* width height channels)
        _         (GL15/glBindBuffer GL21/GL_PIXEL_UNPACK_BUFFER id)
        _         (GL44/glBufferStorage GL21/GL_PIXEL_UNPACK_BUFFER (long mat_size) flags)
        gl_buffer (GL44/glMapBufferRange GL21/GL_PIXEL_UNPACK_BUFFER 0 mat_size flags old_buf)]
     (GL15/glBindBuffer GL21/GL_PIXEL_UNPACK_BUFFER 0)
     [id gl_buffer])))

(defn set-request [buf-name destination width height channels maxl]
   (reset! (:request-buffers @cutter.cutter/the-window-state) true)
  (reset! (:requested-buffer @cutter.cutter/the-window-state)
          {:buf-name       buf-name,
           :destination    destination,
           :width          width,
           :height         height,
           :channels       channels
           :maxl           maxl} )
  nil)


(defn clear-buffer [buffername]
   (println "clear buffer" buffername)
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        texture-array            (assoc texture-array :running false)
        source                   (:source texture-array)
        buffers                  (doall (map (fn [x] (first x)) source))
        pbo_ids                  (:pbo_ids texture-array)
        _                        (doall (map (fn [x] (delete-PBO-buf x)) pbo_ids))
        texture-arrays           (dissoc texture-arrays buffername-key)]
       (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays)
    ) nil)

(defn set-clear [buffername]
  (let [buf-name    (if (not (keyword? buffername)) (keyword buffername))
        destination (:destination (buf-name (:texture-arrays @the-window-state)))]
      (clojure.core.async/offer! (:request-queue @the-window-state) {:type :del-buf :destination destination :buf-name buf-name :data [[-1 -1 -1 -1]]})))

(defn request-handler [req reply-queue locals]
  (let [type          (:type req)
        destination   (:destination req)
        buf-name      (:buf-name req)
        data          (:data req)]
    ;(println data)
    (case type
      :new      (let [widths    (mapv (fn [x] (nth x 0)) data)
                      heights   (mapv (fn [x] (nth x 1)) data)
                      channels  (mapv (fn [x] (nth x 2)) data)
                      amounts   (mapv (fn [x] (nth x 3)) data)
                      pbo-data  (mapv (fn [w h c amount]
                                        (mapv (fn [am]  (create-PBO-buf w h c)) (range amount))) widths heights channels amounts )
                      pbo_ids  (mapv first (partition 2 (flatten pbo-data)))
                      buffers   (mapv last (partition 2 (flatten pbo-data)))]
                  (clojure.core.async/offer! reply-queue [buffers pbo_ids]))
      :del      (let [pbo_ids   data]
                                        ;(println "del data" data)
                  (mapv (fn [x] (delete-PBO-buf x)) pbo_ids)
                  (clojure.core.async/offer! reply-queue true))
      :del-buf  (if  (contains? (:texture-arrays @locals) buf-name)
                  (do (clear-buffer buf-name)
                      (clojure.core.async/offer! reply-queue true))
                  (do (println "No such buffer "  buf-name)
                      (clojure.core.async/offer! reply-queue false)))
      :add-mesh (if (str (first data))
                  (do
                    (println "Adding mesh" (first data))
                    (add-mesh (first data) locals))
                  )
      :del-mesh (if (str (first data))
                  (let [bd               (first data)
                        buffer-objects   (:buffer-objects @locals)
                        bk               (if (keyword? bd) bd (keyword bd))
                        buffer-objects   (dissoc buffer-objects bk)]
                        (swap! locals assoc :buffer-objects buffer-objects)))
      (println "Not a valid request type"))))

(defn request-mesh [path]
  (let [fe   (.exists (clojure.java.io/file path))]
    (if fe
      (clojure.core.async/>!! (:request-queue @the-window-state) {:type :add-mesh :destination :iChannelNull :buf-name "null" :data [path]})
      (println "File does not exist"))
     ))

(defn remove-mesh [mesh-vao-id]
  (let []
     (clojure.core.async/>!! (:request-queue @the-window-state) {:type :del-mesh :destination :iChannelNull :buf-name "null" :data [mesh-vao-id]})))


(defn- run-thread
  [locals
   mode
   shader-filename
   shader-str-atom
   vs-shader-filename
   vs-shader-str-atom
   gs-shader-filename
   gs-shader-str-atom
   title
   true-fullscreen?
   display-sync-hz
   window-idx]
  (println "init-window")
  (init-window
   locals
   mode
   title
   shader-filename
   shader-str-atom
   vs-shader-filename
   vs-shader-str-atom
   gs-shader-filename
   gs-shader-str-atom
   true-fullscreen?
   display-sync-hz
   window-idx)
  (println "init-gl")
  (init-gl locals)
  (try-reload-shader locals)
  (let [startTime               (atom (System/nanoTime))]
    (println "Start thread")
    ;;(GL30/glBindVertexArray (:vao-id @locals))
    ;;(GL20/glEnableVertexAttribArray 0)
    ;;(GL20/glEnableVertexAttribArray 1)
    ;;(GL20/glEnableVertexAttribArray 2)
    ;;(GL20/glEnableVertexAttribArray 3)
    ;;(GL20/glEnableVertexAttribArray 4)
    (while (and (= :yes (:active @locals))
                (not (org.lwjgl.glfw.GLFW/glfwWindowShouldClose (:window @locals))))
      (reset! startTime (System/nanoTime))
      (update-and-draw locals)
      (org.lwjgl.glfw.GLFW/glfwSwapBuffers (:window @locals))
      (org.lwjgl.glfw.GLFW/glfwPollEvents)
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;;Request buffers for texture-array;;
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      (let [req         @(:request-buffers @locals)
            req-type    @(:requested-buffer @locals)
            req-reset   @(:request-reset @locals)
            buf-name     (:buf-name req-type)
            destination  (:destination req-type)
            width        (:width req-type)
            height       (:height req-type)
            channels     (:channels req-type)
            maxl         (:maxl req-type) ]
        (while-let.core/while-let  [r-req (async/poll!  (:request-queue @locals))]
          (let [reply-queue      (:req ((:destination r-req) (:i-textures @locals)))]
            (request-handler r-req reply-queue locals)
            )))
      (swap! (:current-frame @locals) inc)
      )
    (destroy-gl locals)
    (.free (:keyCallback @locals))
    (org.lwjgl.glfw.GLFW/glfwPollEvents)
    (org.lwjgl.glfw.GLFW/glfwDestroyWindow (:window @locals))
    (org.lwjgl.glfw.GLFW/glfwPollEvents)
    (swap! locals assoc :active :no)))

(defn active?
  "Returns true if the shader display is currently running."
  []
  (= :yes (:active @the-window-state)))

(defn inactive?
  "Returns true if the shader display is completely done running."
  []
  (= :no (:active @the-window-state)))

(defn- stop-cutter-local
  "Stop and destroy the shader display. Blocks until completed."
  []
  (when (active?)
    (swap! the-window-state assoc :active :stopping)
    (while (not (inactive?))
      (Thread/sleep 200 0)))
  (remove-watch (:shader-str-atom @the-window-state) :shader-str-watch)
  (remove-watch (:vs-shader-str-atom @the-window-state) :vs-shader-str-watch)
  (remove-watch (:gs-shader-str-atom @the-window-state) :gs-shader-str-watch)
  (stop-watcher @vs-watcher-future)
  (stop-watcher @gs-watcher-future)
  (stop-watcher @watcher-future))

(defn start-shader-display
  "Start a new shader display with the specified mode. Prefer start or
   start-fullscreen for simpler usage."
  [mode
   shader-filename-or-str-atom
   vs-shader-filename-or-str-atom
   gs-shader-filename-or-str-atom
   title
   true-fullscreen?
   display-sync-hz
   window-idx]
  (let [is-filename         (not (instance? clojure.lang.Atom shader-filename-or-str-atom))
        vs-is-filename      (not (instance? clojure.lang.Atom vs-shader-filename-or-str-atom))
        gs-is-filename      (not (instance? clojure.lang.Atom gs-shader-filename-or-str-atom))
        shader-filename     (if is-filename
                              shader-filename-or-str-atom)
        vs-shader-filename  (if is-filename
                              vs-shader-filename-or-str-atom)
        gs-shader-filename  (if is-filename
                              gs-shader-filename-or-str-atom)
        shader-filename     (if (and is-filename (not (nil? shader-filename))) (.getPath (File. ^String shader-filename)))
        vs-shader-filename  (if (and vs-is-filename (not (nil? vs-shader-filename)))(.getPath (File. ^String vs-shader-filename)))
        gs-shader-filename  (if (and gs-is-filename (not (nil? gs-shader-filename)))(.getPath (File. ^String gs-shader-filename)))
        shader-str-atom     (if-not is-filename
                              shader-filename-or-str-atom
                              (atom nil))
        vs-shader-str-atom  (if-not vs-is-filename
                              vs-shader-filename-or-str-atom
                              (atom nil))
        gs-shader-str-atom  (if-not gs-is-filename
                              gs-shader-filename-or-str-atom
                              (atom nil))
        shader-str          (if-not is-filename
                              @shader-str-atom)
        vs-shader-str       (if-not vs-is-filename
                              @vs-shader-str-atom)
        gs-shader-str       (if-not gs-is-filename
                              @gs-shader-str-atom)]
    (when
        (and
         (cutter.general/sane-user-inputs shader-filename shader-str)
         (cutter.general/sane-user-inputs vs-shader-filename vs-shader-str)
         (cutter.general/sane-user-inputs gs-shader-filename gs-shader-str))
      ;; stop the current shader
      (stop-cutter-local)
      ;; start the watchers
      (if is-filename
        (when-not (nil? shader-filename)
          (swap! watcher-future
                 (fn [x] (start-watcher shader-filename))))
        (add-watch shader-str-atom :shader-str-watch watch-shader-str-atom))
      (if vs-is-filename
        (do ;;(println "VSVS")
          (when-not (nil? vs-shader-filename)
              (swap! vs-watcher-future
                     (fn [x] (vs-start-watcher vs-shader-filename)))))
        (add-watch vs-shader-str-atom :vs-shader-str-watch vs-watch-shader-str-atom))
      (if gs-is-filename
        (do ;;(println "GSGS")
          (when-not (nil? gs-shader-filename)
              (swap! gs-watcher-future
                     (fn [x] (gs-start-watcher gs-shader-filename)))))
        (add-watch gs-shader-str-atom :gs-shader-str-watch gs-watch-shader-str-atom))
      ;; start the requested shader
      (.start (Thread.
               (fn [] (run-thread the-window-state
                                 mode
                                 shader-filename
                                 shader-str-atom
                                 vs-shader-filename
                                 vs-shader-str-atom
                                 gs-shader-filename
                                 gs-shader-str-atom
                                 title
                                 true-fullscreen?
                                 display-sync-hz
                                 window-idx)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;External shader input handling
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn set-shader [shader-filename-or-str-atom shader-type]
  (let [watcher-key                   (case shader-type :fs :shader-str-watch :vs :vs-shader-str-watch :gs :gs-shader-str-watch)
        watcher-future-atom           (case shader-type :fs watcher-future :vs vs-watcher-future :gs gs-watcher-future)
        shader-str-atom-key           (case shader-type :fs :shader-str-atom :vs :vs-shader-str-atom :gs :gs-shader-str-atom)
        shader-str-key                (case shader-type :fs :shader-str :vs :vs-shader-str :gs :gs-shader-str)
        watch-shader-str-atom-fn      (case shader-type :fs watch-shader-str-atom :vs vs-watch-shader-str-atom :gs gs-watch-shader-str-atom)
        start-watcher-fn              (case shader-type :fs start-watcher :vs vs-start-watcher :gs gs-start-watcher)
        shader-filename-key           (case shader-type :fs :shader-filename :vs :vs-shader-filename :gs :gs-shader-filename)
        reload-shader-atom            (case shader-type :fs reload-shader :vs vs-reload-shader :gs gs-reload-shader)
        is-filename                   (not (instance? clojure.lang.Atom shader-filename-or-str-atom))
        shader-filename               (if is-filename shader-filename-or-str-atom)
        shader-filename               (if (and is-filename (not (nil? shader-filename)))
                                        (.getPath (File. ^String shader-filename)))
        shader-str-atom               (if-not is-filename shader-filename-or-str-atom (atom nil))
        shader-str                    (if-not is-filename @shader-str-atom)
        shader-str                    (if (nil? shader-filename)
                                        @shader-str-atom
                                        (slurp-fs cutter.cutter/the-window-state shader-filename shader-type))]
    (if is-filename
      (do
        (remove-watch (shader-str-atom-key @cutter.cutter/the-window-state) watcher-key)
        (stop-watcher @watcher-future-atom)
        (swap! cutter.cutter/the-window-state
               assoc
               shader-filename-key       shader-filename
               shader-str-atom-key       shader-str-atom
               shader-str-key            shader-str)
        (if is-filename
          (when-not (nil? shader-filename)
            (swap! watcher-future-atom
                   (fn [x] (start-watcher-fn shader-filename))))
          (add-watch shader-str-atom watcher-key watch-shader-str-atom-fn))
        (reset! reload-shader-atom true)
        (println "Shader" shader-filename-or-str-atom "set"))
      (println "Setting shader failed"))) nil)

(defn reset-temp-string [shader-type]
  (let [temp-shader-key     (case shader-type :fs :temp-fs-string :vs :temp-vs-string :gs :temp-gs-string)]
    (swap! cutter.cutter/the-window-state
           assoc
           temp-shader-key "")) nil)

(defn apped-to-temp-string [input shader-type]
  (let [temp-shader-key     (case shader-type :fs :temp-fs-string :vs :temp-vs-string :gs :temp-gs-string)
        temp-shader-string  (temp-shader-key @cutter.cutter/the-window-state)]
    (swap! cutter.cutter/the-window-state
           assoc
           temp-shader-key (str temp-shader-string input "\n"))) nil)

(defn create-temp-shader-file [filename shader-type]
  (let [fd                        (java.io.File/createTempFile filename nil)
        temp-shader-filename-key  (case shader-type :fs :temp-fs-filename :vs :temp-vs-filename :gs :temp-gs-filename)]
    (swap! cutter.cutter/the-window-state
           assoc
           temp-shader-filename-key (.getPath fd))
    (.deleteOnExit fd)
    fd))

(defn write-file [path input]
  (with-open [w (clojure.java.io/writer  path :append true)]
    (doseq [x input]
      (.write w x )
      (.newLine w))))

(defn save-temp-shader [filename shader-type]
  (let [fd                  (create-temp-shader-file filename shader-type)
        path                (.getPath fd)
        temp-shader-key     (case shader-type :fs :temp-fs-string :vs :temp-vs-string :gs :temp-gs-string)
        temp-shader-string  (temp-shader-key @cutter.cutter/the-window-state)
        split-string        (clojure.string/split-lines temp-shader-string)]
    (write-file path split-string )))
