(ns #^{:author "Mikael Reponen"
       :doc " Core library derived from Shadertone (Roger Allen https://github.com/overtone/shadertone)."}
  cutter.cutter
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [watchtower.core :as watcher]
            [clojure.java.io :as io]
            [while-let.core :as while-let]
            [cutter.shader :refer :all]
            [cutter.general :refer :all]
            [cutter.gl_init :refer :all]
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

(defonce default-state-values
  { :active                     :no  ;; :yes/:stopping/:no
    :width                      0
    :height                     0
    :title                      ""
    :display-sync-hz            30
    :start-time                 0
    :last-time                  0
    :window                     nil
    :keyCallback                nil
    ;; geom ids
    :vbo-id                     0
    :vertices-count             0
    :vao-id                     0
    :vboc-id                    0
    :vboi-id                    0
    :indices-count              0
    ;; shader program
    :shader-ver                 "#version 460 core"
    :shader-good                true ;; false in error condition
    :shader-filename            nil
    :shader-str-atom            (atom nil)
    :shader-str                 ""
    :vs-id                      0
    :fs-id                      0
    :pgm-id                     0
    ;; Textures, cameras and video paths
    :maximum-textures           1000
    :maximum-texture-folders    1000
    :maximum-cameras            1000
    :maximum-videos             1000
    :maximum-running-cameras    10
    :maximum-running-videos     10
    :maximum-running-buffer     10
    :maximum-buffer-length      250  ;Frames
    ;:maximum-live-uniforms      10
    ;:maximum-buffered-uniforms  10
    ;:maximum-texture-uniforms   50
    :texture-filenames          []
    :texture-folders            []
    :camera-devices             []
    :video-filenames            []
    :textures                   {} ;{:filename, {:idx :destination :source "mat" :running false}}
    :texture-arrays             {} ;{:dir, {:idx :destination :source "mat array" :running false}}
    :cameras                    {} ;{:device, {:idx :destination :source "capture" :running false, :fps 30}}
    :videos                     {} ;{:filename, {:idx :destination :source "capture" :running false, :fps 30}}
    ;Data Arrays
    :maxDataArrays              16
    :maxDataArraysLength        256

    ;v4l2
    :save-frames                (atom false)
    :deviceName                 (atom "/dev/video3")
    :deviceId                   (atom 0)
    :minsize                    (atom 0)
    :bff                        (atom 0)
    :isInitialized              (atom false)
    ;; shader uniforms
    :i-channels                 (mapv (fn [x] (keyword (str "iChannel" x))) (range 1 16 1))
    :i-dataArrays               (into {} (mapv (fn [x] {(keyword (str "iDataArray" x)) {:datavec (vec (make-array Float/TYPE 256)), :buffer (-> (BufferUtils/createFloatBuffer 256)
                                  (.put (float-array
                                      (vec (make-array Float/TYPE 256))))
                                        (.flip))} } ) (range 1 16 1)))
    :i-uniforms                 {:iResolution   {:type "vec3",      :loc 0, :gltype (fn [id x y z] (GL20/glUniform3f id x y z)),  :extra "", :layout "", :unit -1},
                                :iGlobalTime    {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iPreviousFrame {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 0},
                                :iText          {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 1},
                                :iChannel1      {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 2},
                                :iChannel2      {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 3},
                                :iChannel3      {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 4},
                                :iChannel4      {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 5},
                                :iChannel5      {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 6},
                                :iChannel6      {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 7},
                                :iChannel7      {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 8},
                                :iChannel8      {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 9},
                                :iChannel9      {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 10},
                                :iChannel10     {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 11},
                                :iChannel11     {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 12},
                                :iChannel12     {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 13},
                                :iChannel13     {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 14},
                                :iChannel14     {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 15},
                                :iChannel15     {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 16},
                                :iChannel16     {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "", :unit 17},
                                :iDataArray1    {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iDataArray2    {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iDataArray3    {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iDataArray4    {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iDataArray5    {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iDataArray6    {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iDataArray7    {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iDataArray8    {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iDataArray9    {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iDataArray10   {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iDataArray11   {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iDataArray12   {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iDataArray13   {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iDataArray14   {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iDataArray15   {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iDataArray16   {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1}}
     ;textures
     :i-textures     {:iPreviousFrame {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iText          {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel1      {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel2      {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel3      {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel4      {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel5      {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel6      {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel7      {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel8      {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel9      {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel10     {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel11     {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel12     {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel13     {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel14     {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel15     {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannel16     {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0}
                    }
     })
;; GLOBAL STATE ATOMS iPreviousFrame
(defonce the-window-state (atom default-state-values))

;Opencv Java related
(org.bytedeco.javacpp.Loader/load org.bytedeco.javacpp.opencv_java)

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
   texture-filenames
   texture-folders
   camera-devices
   video-filenames
   true-fullscreen?
   display-sync-hz
   window-idx]
    (when-not (org.lwjgl.glfw.GLFW/glfwInit)
    (throw (IllegalStateException. "Unable to initialize GLFW")))
    (let [
        primaryMonitor      (org.lwjgl.glfw.GLFW/glfwGetPrimaryMonitor)
        currentMonitor      (getMonitor window-idx true-fullscreen?)
        ;mode                (if (= 0 currentMonitor) primaryMonitor (org.lwjgl.glfw.GLFW/glfwGetVideoMode currentMonitor))
        current-time-millis (System/currentTimeMillis)
        width               (nth display-mode 0)
        height              (nth display-mode 1)
        texture-filenames   (cutter.general/remove-inexistent texture-filenames (:maximum-textures @locals))
        texture-folders     (cutter.general/remove-inexistent texture-folders (:maximum-texture-folders @locals))
        camera-devices      (cutter.general/remove-inexistent camera-devices (:maximum-cameras @locals))
        video-filenames     (cutter.general/remove-inexistent video-filenames (:maximum-videos @locals))
        ]
        (swap! locals
           assoc
           :active            :yes
           :width             width
           :height            height
           :title             title
           :display-sync-hz   display-sync-hz
           :start-time        current-time-millis
           :last-time         current-time-millis
           :shader-filename   shader-filename
           :shader-str-atom   shader-str-atom
           :texture-filenames texture-filenames
           :texture-folders   texture-folders
           :camera-devices    camera-devices
           :video-filenames   video-filenames
           )
        (println "Begin shader slurping.")
        (let [shader-str (if (nil? shader-filename)
                       @shader-str-atom
                       (slurp-fs locals (:shader-filename @locals)))])
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


(defn- init-buffers
  [locals]
  (let [vertices  (float-array  [-1.0 -1.0 0.0 1.0
                                 1.0 -1.0 0.0 1.0
                                -1.0  1.0 0.0 1.0
                                -1.0  1.0 0.0 1.0
                                 1.0 -1.0 0.0 1.0
                                 1.0  1.0 0.0 1.0])
          vertices-buffer     (-> (BufferUtils/createFloatBuffer (count vertices))
                                (.put vertices)
                                (.flip))
          vertices-count      (count vertices)
          colors (float-array
                [1.0 0.0 0.0
                0.0 1.0 0.0
                0.0 0.0 1.0])
          colors-buffer (-> (BufferUtils/createFloatBuffer (count colors))
              (.put colors)
              (.flip))
          indices (byte-array
            (map byte
            [0 1 2])) ;; otherwise it whines about longs
          indices-count (count indices)
          indices-buffer (-> (BufferUtils/createByteBuffer indices-count)
                        (.put indices)
                        (.flip))
          ;; create & bind Vertex Array Object
          vao-id              (GL30/glGenVertexArrays)
          _                   (GL30/glBindVertexArray vao-id)
          ;; create & bind Vertex Buffer Object for vertices
          vbo-id              (GL15/glGenBuffers)
          _                   (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER vbo-id)
          _                   (GL15/glBufferData GL15/GL_ARRAY_BUFFER
                                          ^FloatBuffer vertices-buffer
                                          GL15/GL_STATIC_DRAW)
          _                   (GL20/glVertexAttribPointer 0 4 GL11/GL_FLOAT false 0 0)
          _                   (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER 0)
          ;; create & bind VBO for colors
          vboc-id             (GL15/glGenBuffers)
          _                   (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER vboc-id)
          _                   (GL15/glBufferData GL15/GL_ARRAY_BUFFER colors-buffer GL15/GL_STATIC_DRAW)
          _                   (GL20/glVertexAttribPointer 1 3 GL11/GL_FLOAT false 0 0)
          _                   (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER 0)
          ;; deselect the VAO
          _                   (GL30/glBindVertexArray 0)
          ;; create & bind VBO for indices
          vboi-id             (GL15/glGenBuffers)
          _                   (GL15/glBindBuffer GL15/GL_ELEMENT_ARRAY_BUFFER vboi-id)
          _                   (GL15/glBufferData GL15/GL_ELEMENT_ARRAY_BUFFER indices-buffer GL15/GL_STATIC_DRAW)
          _                   (GL15/glBindBuffer GL15/GL_ELEMENT_ARRAY_BUFFER 0)
          _ (except-gl-errors "@ end of init-buffers")]
            (swap! locals
              assoc
              :vbo-id vbo-id
              :vao-id vao-id
              :vboc-id vboc-id
              :vboi-id vboi-id
              :vertices-count vertices-count)))

  (defn- init-gl
    [locals]
    (let [{:keys [width height user-fn]} @locals]
      (GL/createCapabilities)
      (println "OpenGL version:" (GL11/glGetString GL11/GL_VERSION))
      (GL11/glClearColor 0.0 0.0 0.0 0.0)
      (GL11/glViewport 0 0 width height)
      (init-buffers locals)
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iPreviousFrame width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iText width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel1 width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel2 width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel3 width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel4 width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel5 width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel6 width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel7 width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel8 width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel9 width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel10 width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel11 width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel12 width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel13 width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel14 width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel15 width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannel16 width height))

      ;(println (:i-textures @locals))

      ;(println (:i-textures @locals))
      ;(println :i-textures @locals)
      ;(init-textures locals)
      ;(init-cams locals)
      ;(init-videos locals)
      (init-shaders locals)
              ))
              ;

; ;
(defn- set-opengl-texture [locals texture-key image]
   (let[  i-textures          (:i-textures @locals )
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
          height              (nth image 4)
          width               (nth image 5)
          image-bytes         (nth image 6)
          setnbytes           (* wset hset bset)
          tex-image-target    ^Integer (+ 0 target)
          nbytes              (* width height image-bytes)
          buffer              (.convertFromAddr matConverter (long (nth image 0))  (int (nth image 1)) (long (nth image 2)) (long (nth image 3)))]
          (if (or init? (not= setnbytes nbytes))
            (do (try (GL11/glTexImage2D ^Integer tex-image-target 0 ^Integer internal-format
              ^Integer width  ^Integer height 0
              ^Integer format
              GL11/GL_UNSIGNED_BYTE
              buffer))
                (let [texture     (init-texture width height target tex-id queue)
                      texture     (assoc texture :init-opengl false)
                      i-textures  (assoc i-textures texture-key texture)]
                      (swap! locals assoc :i-textures i-textures)))
            (do
              (if (< 0 (nth image 0))
                (try (GL11/glTexSubImage2D ^Integer tex-image-target 0 0 0
                    ^Integer width  ^Integer height
                    ^Integer format
                    GL11/GL_UNSIGNED_BYTE
                    buffer)))))
          (except-gl-errors "@ end of load-texture if-stmt")))
;
(defn- get-textures
    [locals texture-key i-uniforms]
    (let [i-textures          (:i-textures @locals)
          texture             (texture-key i-textures)
          queue               (:queue texture)
          ;out1                (:out1 texture)
          unit                (:unit (texture-key i-uniforms))
          tex-id              (:tex-id texture)
          target              (:target texture)
          image               (if (= nil queue) nil (async/poll! queue))]
          (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 unit))
          (GL11/glBindTexture target tex-id)
          (if  (not (nil? image))
                (do ;(println "texture-key image tex-id" texture-key image tex-id)
                (set-opengl-texture locals texture-key image)
                )
                nil)))
(defn- draw
  [locals]
  (let [{:keys [width height
                start-time last-time i-global-time-loc
                i-date-loc
                pgm-id vbo-id vao-id vboi-id vboc-id
                vertices-count
                i-uniforms
                i-textures
                i-channels
                i-dataArrays
                dataArray1 dataArray2 dataArray3 dataArray4
                dataArray1Buffer dataArray2Buffer dataArray3Buffer dataArray4Buffer
                save-frames
                old-pgm-id old-fs-id
                ]} @locals
        cur-time    (/ (- last-time start-time) 1000.0)]

    (except-gl-errors "@ draw before clear")


    (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_DEPTH_BUFFER_BIT))
    (except-gl-errors "@ draw after activate textures")

    ((:gltype (:iResolution i-uniforms)) (:loc (:iResolution i-uniforms)) width height 1.0)
    ((:gltype (:iGlobalTime i-uniforms)) (:loc (:iGlobalTime i-uniforms)) cur-time)

    (doseq [x (keys i-dataArrays)]
    ((:gltype (x i-uniforms)) (:loc (x i-uniforms)) (:datavec (x i-dataArrays)) (:buffer (x i-dataArrays))))

    ((:gltype (:iText i-uniforms)) (:loc (:iText i-uniforms)) (:unit (:iText i-uniforms)))
    (get-textures locals :iText i-uniforms)

    (doseq [x i-channels]
      ((:gltype (x i-uniforms)) (:loc (x i-uniforms)) (:unit (x i-uniforms)))
      (get-textures locals x i-uniforms))

    ;; get vertex array ready
     (GL30/glBindVertexArray vao-id)
     (GL20/glEnableVertexAttribArray 0)

     ;Inidices
     (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER vbo-id)
     (GL20/glVertexAttribPointer 0 4 GL11/GL_FLOAT false 0 0);
     ;Colors
     (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER vboc-id)
     (GL20/glVertexAttribPointer 1 3 GL11/GL_FLOAT false 0 0);
    ;// attribute 0. No particular reason for 0, but must match the layout in the shader.
    ;// size
    ;// type
    ; // normalized?
    ; // stride
    ;// array buffer offset
     (except-gl-errors "@ draw prior to DrawArrays")

     ;; Draw the vertices
     (GL11/glDrawArrays GL11/GL_TRIANGLES 0 vertices-count)

     ;(except-gl-errors "@ draw after DrawArrays")
     ;; Put everything back to default (deselect)
     ;Copying the previous image to its own texture

     (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 (:tex-id (:iPreviousFrame i-textures))))
     (GL11/glBindTexture GL11/GL_TEXTURE_2D (:tex-id (:iPreviousFrame i-textures)))
     (GL11/glCopyTexImage2D GL11/GL_TEXTURE_2D 0 GL11/GL_RGB 0 0 width height 0)
     ;(GL11/glBindTexture GL11/GL_TEXTURE_2D 0)
     (if @save-frames
       (do ; download it and copy the previous image to its own texture
         (GL11/glGetTexImage GL11/GL_TEXTURE_2D 0 GL11/GL_RGB GL11/GL_UNSIGNED_BYTE  ^ByteBuffer (:buffer (:iPreviousFrame i-textures)))
         (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)
         (org.bytedeco.javacpp.v4l2/v4l2_write @(:deviceId @the-window-state) (new org.bytedeco.javacpp.BytePointer (:buffer (:iPreviousFrame i-textures))) (long  @(:minsize @the-window-state))))
         nil)
     (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)

     (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER 0)
     (GL20/glDisableVertexAttribArray 0)))


(defn- update-and-draw
  [locals]
  (let [{:keys [width height last-time pgm-id
                ]} @locals
                cur-time (System/currentTimeMillis)]
    (swap! locals
           assoc
           :last-time cur-time
           )
    (if (:shader-good @locals)
      (do
        (if @reload-shader
          (try-reload-shader locals)  ; this must call glUseProgram
          (GL20/glUseProgram pgm-id)) ; else, normal path...
        (draw locals))
      ;; else clear to prevent strobing awfulness
      (do
        (GL11/glClear GL11/GL_COLOR_BUFFER_BIT)
        (except-gl-errors "@ bad-draw glClear ")
        (if @reload-shader
          (try-reload-shader locals))))))

(defn- destroy-gl
  [locals]
  (let [{:keys [pgm-id vs-id fs-id vbo-id vao-id user-fn cams]} @locals]
    ;; Delete the shaders
    (GL20/glUseProgram 0)
    (GL20/glDetachShader pgm-id vs-id)
    (GL20/glDetachShader pgm-id fs-id)
    (GL20/glDeleteShader vs-id)
    (GL20/glDeleteShader fs-id)
    (GL20/glDeleteProgram pgm-id)
    ;; Select the VAO
    (GL30/glBindVertexArray vao-id)
    (GL20/glDisableVertexAttribArray 0)
    (GL20/glDisableVertexAttribArray 1)

    ;; Delete the vertex VBO
    (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER 0)
    (GL15/glDeleteBuffers ^Integer vbo-id)
        ;; Delete the VAO
    (GL30/glBindVertexArray 0)
    (GL30/glDeleteVertexArrays vao-id)))

;
(defn stop-cam [device locals]
  (let [device-id                (read-string (str (last device)))
        cameras                  (:cameras @locals)
        camera-key               (keyword device)
        camera                   (camera-key cameras)
        camera                   (assoc camera :running false)
        cameras                  (assoc cameras camera-key camera)]
        (swap! locals assoc :cameras cameras))
  nil)


(defn stop-all-cameras [locals]
   (mapv (fn [x] (stop-cam (str "/" (name x)) locals)) (vec (keys (:cameras @locals)))))

(defn- run-thread
  [locals mode shader-filename shader-str-atom tex-filenames texture-folders cams videos title true-fullscreen? display-sync-hz window-idx]
  (println "init-window")
  (init-window locals mode title shader-filename shader-str-atom tex-filenames texture-folders cams videos true-fullscreen? display-sync-hz window-idx)
  (println "init-gl")
  (init-gl locals)
  (try-reload-shader locals)
  (let [startTime               (atom (System/nanoTime))]
     (println "Start thread")
     (while (and (= :yes (:active @locals))
               (not (org.lwjgl.glfw.GLFW/glfwWindowShouldClose (:window @locals))))
        (reset! startTime (System/nanoTime))
        (update-and-draw locals)
        (org.lwjgl.glfw.GLFW/glfwSwapBuffers (:window @locals))
        (org.lwjgl.glfw.GLFW/glfwPollEvents)
      (Thread/sleep  (cutter.general/sleepTime @startTime (System/nanoTime) display-sync-hz)))
     (println "Stop cameras")
     (stop-all-cameras locals)
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


(defn stop
  "Stop and destroy the shader display. Blocks until completed."
  []
  (when (active?)
  (swap! the-window-state assoc :active :stopping)
  (while (not (inactive?))
  (Thread/sleep 200)))
  (remove-watch (:shader-str-atom @the-window-state) :shader-str-watch)
  (stop-watcher @watcher-future))

(defn start-shader-display
  "Start a new shader display with the specified mode. Prefer start or
   start-fullscreen for simpler usage."
  [mode shader-filename-or-str-atom texture-filenames texture-folders cam-devices video-filenames title true-fullscreen? display-sync-hz window-idx]
  (let [is-filename     (not (instance? clojure.lang.Atom shader-filename-or-str-atom))
        shader-filename (if is-filename
                          shader-filename-or-str-atom)
        ;; Fix for issue 15.  Normalize the given shader-filename to the
        ;; path separators that the system will use.  If user gives path/to/shader.glsl
        ;; and windows returns this as path\to\shader.glsl from .getPath, this
        ;; change should make comparison to path\to\shader.glsl work.
        shader-filename (if (and is-filename (not (nil? shader-filename)))
                          (.getPath (File. ^String shader-filename)))
        shader-str-atom (if-not is-filename
                          shader-filename-or-str-atom
                          (atom nil))
        shader-str      (if-not is-filename
                          @shader-str-atom)]
    (when (cutter.general/sane-user-inputs shader-filename shader-str)
      ;; stop the current shader
      (stop)
      ;; start the watchers
      (if is-filename
        (when-not (nil? shader-filename)
          (swap! watcher-future
                 (fn [x] (start-watcher shader-filename))))
        (add-watch shader-str-atom :shader-str-watch watch-shader-str-atom))

      ;; set a global window-state instead of creating a new one
      (reset! the-window-state default-state-values)
      ;; start the requested shader
      (.start (Thread.
               (fn [] (run-thread the-window-state
                                 mode
                                 shader-filename
                                 shader-str-atom
                                 texture-filenames
                                 texture-folders
                                 cam-devices
                                 video-filenames
                                 title
                                 true-fullscreen?
                                 display-sync-hz
                                 window-idx)))))))

(defn start
  "Start a new shader display."
  [shader-filename-or-str-atom
   &{:keys [width height title display-sync-hz
            texture-filenames texture-folders cam-devices video-filenames fullscreen? window-idx]
     :or {width             1280
          height            800
          title             "cutter"
          display-sync-hz   30
          texture-filenames []
          texture-folders   []
          camera-devices    []
          video-filenames   []
          fullscreen?       false
          window-idx        0}}]
   (let [mode  [width height]]
    (start-shader-display mode shader-filename-or-str-atom texture-filenames texture-folders cam-devices video-filenames title false display-sync-hz window-idx)))

(defn start-fullscreen
  "Start a new shader display."
  [shader-filename-or-str-atom
   &{:keys [width height title display-sync-hz
            texture-filenames texture-folders cam-devices video-filenames fullscreen? window-idx]
     :or {width           1280
          height          800
          title           "cutter"
          display-sync-hz 30
          texture-filenames []
          texture-folders   []
          camera-devices    []
          video-filenames   []
          fullscreen?     true
          window-idx      0}}]
   (let [mode  [width height]]
    (start-shader-display mode shader-filename-or-str-atom texture-filenames texture-folders cam-devices video-filenames title true display-sync-hz window-idx)))
