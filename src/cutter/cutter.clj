(ns #^{:author "Mikael Reponen"
       :doc " Core library derived from Shadertone (Roger Allen https://github.com/overtone/shadertone)."}
  cutter.cutter
  (:use [overtone.osc])
  (:require ;[clojure.tools.namespace.repl :refer [refresh]]
            ;[watchtower.core :as watcher]
            ;[clojure.java.io :as io]
            ;[while-let.core :as while-let]
            [cutter.shader :refer :all]
            [cutter.general :refer :all]
            [cutter.gl_init :refer :all]
            [cutter.opencv :refer :all]
            ;[clojure.java.io :as io]
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
    ;[org.opencv.core Mat Core CvType]
    ;[org.opencv.videoio Videoio VideoCapture]
    ;[org.opencv.video Video]
    ;[org.opencv.imgproc Imgproc]
    ;[org.opencv.imgcodecs Imgcodecs]
    ;[java.awt.image BufferedImage DataBuffer DataBufferByte WritableRaster]
           [java.io File FileInputStream]
           [java.nio IntBuffer ByteBuffer FloatBuffer ByteOrder]
    ;       [java.util Calendar]
  ;         [java.util List]
;           [javax.imageio ImageIO]
      ;     [java.lang.reflect Field]
           [org.lwjgl BufferUtils]
           [org.lwjgl.glfw GLFW GLFWErrorCallback GLFWKeyCallback]
           [org.lwjgl.opengl GL GL11 GL12 GL13 GL15 GL20 GL30 GL40]))

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
    ;OSC
    :osc-server                (overtone.osc/osc-server 44100 "cutter-osc")
    :osc-client                (overtone.osc/osc-client "localhost" 44100)
    :osc-port                   44100
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
    :vs-shader-good             true ;; false in error condition
    :vs-shader-filename         nil
    :vs-shader-str-atom         (atom nil)
    :vs-shader-str               ""
    :vs-id                      0
    :fs-id                      0
    :pgm-id                     0
    :temp-fs-string             ""
    :temp-vs-string             ""
    :temp-fs-filename           ""
    :temp-vs-filename           ""
    ;; Textures, cameras and video paths
    :maximum-textures           1000
    :maximum-texture-folders    1000
    :maximum-cameras            1000
    :maximum-videos             1000
    :maximum-buffer-length      250  ;Frames
    :textures                   {} ;{:filename, {:idx :destination :source "mat" :running false}}
    :texture-arrays             {} ;{:name, {:idx :destination :source "buf array" :running false, :fps 30, index: 0, :mode :fw, :loop true, :start-index 0, :stop-index 0}
    :cameras                    {} ;{:device, {:idx :destination :source "capture" :running false, :fps 30, index: 0, :start-index 0, :stop-index 0}}
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
    :i-floats                   (into {} (mapv (fn [x] {(keyword (str "iFloat" x)) {:data 0 }}) (range 1 16 1)))
    :i-uniforms                 {:iResolution   {:type "vec3",      :loc 0, :gltype (fn [id x y z] (GL20/glUniform3f id x y z)),  :extra "", :layout "", :unit -1},
                                :iGlobalTime    {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iRandom        {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
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
                                :iDataArray16   {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout "", :unit -1},
                                :iFloat1        {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iFloat2        {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iFloat3        {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iFloat4        {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iFloat5        {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iFloat6        {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iFloat7        {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iFloat8        {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iFloat9        {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iFloat10       {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iFloat11       {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iFloat12       {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iFloat13       {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iFloat14       {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iFloat15       {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1},
                                :iFloat16       {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout "", :unit -1}}
     ;textures
     :i-textures     {:iPreviousFrame {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0}, ;
                      :iText          {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
                      :iChannelNull   {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0, :mult 0, :out1 0},
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
;; GLOBAL STATE ATOMS
(defonce the-window-state (atom default-state-values))


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
   true-fullscreen?
   display-sync-hz
   window-idx]
    (when-not (org.lwjgl.glfw.GLFW/glfwInit)
    (throw (IllegalStateException. "Unable to initialize GLFW")))
    (let [primaryMonitor      (org.lwjgl.glfw.GLFW/glfwGetPrimaryMonitor)
          currentMonitor      (getMonitor window-idx true-fullscreen?)
          ;mode                (if (= 0 currentMonitor) primaryMonitor (org.lwjgl.glfw.GLFW/glfwGetVideoMode currentMonitor))
          current-time-millis (System/currentTimeMillis)
          width               (nth display-mode 0)
          height              (nth display-mode 1)
          shader-str          (if (nil? shader-filename)
                                @shader-str-atom
                                (slurp-fs locals shader-filename))
          vs-shader-str       (if (nil? vs-shader-filename)
                                @vs-shader-str-atom
                                (slurp-fs locals  vs-shader-filename))]
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
            :shader-str            shader-str
            :vs-shader-str         vs-shader-str)
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
    (let [{:keys [width height i-channels]} @locals]
      (GL/createCapabilities)
      (println "OpenGL version:" (GL11/glGetString GL11/GL_VERSION))
      (GL11/glClearColor 0.0 0.0 0.0 0.0)
      (GL11/glViewport 0 0 width height)
      (init-buffers locals)
      (doseq [x i-channels]
        (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals x width height)))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iChannelNull width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iPreviousFrame width height))
      (swap! locals assoc :i-textures (cutter.gl_init/initialize-texture locals :iText width height))
      (init-shaders locals)))

(defn- set-texture [tex-image-target internal-format width height format buffer]
  (try (GL11/glTexImage2D ^Integer tex-image-target 0 ^Integer internal-format
    ^Integer width  ^Integer height 0
    ^Integer format
    GL11/GL_UNSIGNED_BYTE
    buffer)))

(defn- set-opengl-texture [locals texture-key buffer width height image-bytes img-addr]
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
          height              height ;(nth image 4)
          width               width ;(nth image 5)
          image-bytes         image-bytes; (nth image 6)
          setnbytes           (* wset hset bset)
          tex-image-target    ^Integer (+ 0 target)
          nbytes              (* width height image-bytes)
          buffer              buffer]
          (if (or init? (not= setnbytes nbytes))
            (do
              (set-texture tex-image-target internal-format width height format buffer)
                (let [queue               (:queue texture)
                      out1                (:out1 texture)
                      mlt                 (:mult texture)
                      texture     (init-texture width height target tex-id queue out1 mlt)
                      texture     (assoc texture :init-opengl false)
                      i-textures  (assoc i-textures texture-key texture)]
                      (swap! locals assoc :i-textures i-textures)))
            (do
              (if (< 0 img-addr)
                (set-texture tex-image-target internal-format width height format buffer))))
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
                (do ;

                (if
                  (instance? java.lang.Long (nth image 0)) (set-opengl-texture
                                                                    locals
                                                                    texture-key
                                                                    0 ;(.convertFromAddr matConverter (long (nth image 0))  (int (nth image 1)) (long (nth image 2)) (long (nth image 3)))
                                                                    (nth image 5)
                                                                    (nth image 4)
                                                                    (nth image 6)
                                                                    (nth image 0))
                  (do (set-opengl-texture
                        locals
                        texture-key
                        (nth image 0)
                        (nth image 5)
                        (nth image 4)
                        (nth image 6)
                        1)))
                )
                nil)))
(defn- draw [locals]
  (let [{:keys [width height
                start-time last-time i-global-time-loc
                i-date-loc
                pgm-id vbo-id vao-id vboi-id vboc-id
                vertices-count
                i-uniforms
                i-textures
                i-channels
                i-dataArrays
                i-floats
                save-frames
                old-pgm-id old-fs-id
                ]} @locals
        cur-time    (/ (- last-time start-time) 1000.0)]

    (except-gl-errors "@ draw before clear")

    (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_DEPTH_BUFFER_BIT))
    (except-gl-errors "@ draw after activate textures")

    ((:gltype (:iResolution i-uniforms)) (:loc (:iResolution i-uniforms)) width height 1.0)
    ((:gltype (:iGlobalTime i-uniforms)) (:loc (:iGlobalTime i-uniforms)) cur-time)
    ((:gltype (:iRandom i-uniforms)) (:loc (:iRandom i-uniforms)) (rand))

    (doseq [x (keys i-dataArrays)]
      ((:gltype (x i-uniforms)) (:loc (x i-uniforms)) (:datavec (x i-dataArrays)) (:buffer (x i-dataArrays))))

    (doseq [x (keys i-floats)]
      ((:gltype (x i-uniforms)) (:loc (x i-uniforms)) (:data (x i-floats)) ))

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

     (except-gl-errors "@ draw after DrawArrays")
     ;; Put everything back to default (deselect)
     ;Copying the previous image to its own texture
     (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 (:tex-id (:iPreviousFrame i-textures))))
     (GL11/glBindTexture GL11/GL_TEXTURE_2D (:tex-id (:iPreviousFrame i-textures)))
     (GL11/glCopyTexImage2D GL11/GL_TEXTURE_2D 0 GL11/GL_RGB 0 0 width height 0)
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
           :last-time cur-time)
    (if (and (:shader-good @locals) (:vs-shader-good @locals))
      (do
        (if (or @reload-shader @vs-reload-shader)
          (try-reload-shader locals)  ; this must call glUseProgram
          (GL20/glUseProgram pgm-id)) ; else, normal path...
        (draw locals))
      ;; else clear to prevent strobing awfulness
      (do
        (GL11/glClear GL11/GL_COLOR_BUFFER_BIT)
        (except-gl-errors "@ bad-draw glClear ")
        (if (or @reload-shader @vs-reload-shader)
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

(defn- run-thread
  [locals mode shader-filename shader-str-atom vs-shader-filename vs-shader-str-atom title true-fullscreen? display-sync-hz window-idx]
  (println "init-window")
  (init-window locals mode title shader-filename shader-str-atom vs-shader-filename vs-shader-str-atom true-fullscreen? display-sync-hz window-idx)
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

(defn stop-cutter
  "Stop and destroy the shader display. Blocks until completed."
  []
  (when (active?)
  (swap! the-window-state assoc :active :stopping)
  (while (not (inactive?))
  (Thread/sleep 200)))
  (remove-watch (:shader-str-atom @the-window-state) :shader-str-watch)
  (remove-watch (:vs-shader-str-atom @the-window-state) :vs-shader-str-watch)
  (stop-watcher @vs-watcher-future)
  (stop-watcher @watcher-future))

(defn start-shader-display
  "Start a new shader display with the specified mode. Prefer start or
   start-fullscreen for simpler usage."
  [mode shader-filename-or-str-atom  vs-shader-filename-or-str-atom title true-fullscreen? display-sync-hz window-idx]
  (let [is-filename         (not (instance? clojure.lang.Atom shader-filename-or-str-atom))
        vs-is-filename      (not (instance? clojure.lang.Atom vs-shader-filename-or-str-atom))
        shader-filename     (if is-filename
                              shader-filename-or-str-atom)
        vs-shader-filename  (if is-filename
                              vs-shader-filename-or-str-atom)
        ;; Fix for issue 15.  Normalize the given shader-filename to the
        ;; path separators that the system will use.  If user gives path/to/shader.glsl
        ;; and windows returns this as path\to\shader.glsl from .getPath, this
        ;; change should make comparison to path\to\shader.glsl work.
        shader-filename     (if (and is-filename (not (nil? shader-filename)))
                              (.getPath (File. ^String shader-filename)))
        vs-shader-filename  (if (and vs-is-filename (not (nil? vs-shader-filename)))
                              (.getPath (File. ^String vs-shader-filename)))
        shader-str-atom     (if-not is-filename
                              shader-filename-or-str-atom
                              (atom nil))
        vs-shader-str-atom  (if-not vs-is-filename
                              vs-shader-filename-or-str-atom
                              (atom nil))
        shader-str          (if-not is-filename
                              @shader-str-atom)
        vs-shader-str       (if-not vs-is-filename
                              @vs-shader-str-atom)]
    (when (and (cutter.general/sane-user-inputs shader-filename shader-str)
               (cutter.general/sane-user-inputs vs-shader-filename vs-shader-str))
      ;; stop the current shader
      (stop-cutter)
      ;; start the watchers
      (if is-filename
        (when-not (nil? shader-filename)
          (swap! watcher-future
                 (fn [x] (start-watcher shader-filename))))
        (add-watch shader-str-atom :shader-str-watch watch-shader-str-atom))
      (if vs-is-filename
        (when-not (nil? vs-shader-filename)
          (swap! vs-watcher-future
                 (fn [x] (vs-start-watcher vs-shader-filename))))
        (add-watch vs-shader-str-atom :vs-shader-str-watch vs-watch-shader-str-atom))

      ;; set a global window-state instead of creating a new one
      ;(reset! the-window-state default-state-values)
      ;; start the requested shader
      (.start (Thread.
               (fn [] (run-thread the-window-state
                                 mode
                                 shader-filename
                                 shader-str-atom
                                 vs-shader-filename
                                 vs-shader-str-atom
                                 title
                                 true-fullscreen?
                                 display-sync-hz
                                 window-idx)))))))

(defn start
  "Start a new shader display."
  [&{:keys [fs vs width height title display-sync-hz fullscreen? window-idx]
     :or {fs                (.getPath (clojure.java.io/resource "default.fs"))
          vs                (.getPath (clojure.java.io/resource "default.vs"))
          width             1280
          height            800
          title             "cutter"
          display-sync-hz   30
          fullscreen?       false
          window-idx        0}}]
   (let [mode  [width height]
        shader-filename-or-str-atom fs
        vs-shader-filename-or-str-atom vs]
    (start-shader-display mode shader-filename-or-str-atom vs-shader-filename-or-str-atom  title false display-sync-hz window-idx)))

(defn start-fullscreen
  "Start a new shader display."
  [&{:keys [fs vs width height title display-sync-hz  fullscreen? window-idx]
     :or {fs                (.getPath (clojure.java.io/resource "default.fs"))
          vs                (.getPath (clojure.java.io/resource "default.vs"))
          width           1280
          height          800
          title           "cutter"
          display-sync-hz 30
          fullscreen?     true
          window-idx      0}}]
   (let [mode  [width height]
        shader-filename-or-str-atom fs
        vs-shader-filename-or-str-atom vs]
    (start-shader-display mode shader-filename-or-str-atom vs-shader-filename-or-str-atom title true display-sync-hz window-idx)))

;;External shader input handling
(defn set-shader [shader-filename-or-str-atom shader-type]
  (let [watcher-key                   (case shader-type :fs :shader-str-watch :vs :vs-shader-str-watch)
        watcher-future-atom           (case shader-type :fs watcher-future :vs vs-watcher-future)
        shader-str-atom-key           (case shader-type :fs :shader-str-atom :vs :vs-shader-str-atom)
        shader-str-key                (case shader-type :fs :shader-str :vs :vs-shader-str)
        watch-shader-str-atom-fn      (case shader-type :fs watch-shader-str-atom :vs vs-watch-shader-str-atom)
        start-watcher-fn              (case shader-type :fs start-watcher :vs vs-start-watcher)
        shader-filename-key           (case shader-type :fs :shader-filename :vs :vs-shader-filename)
        reload-shader-atom            (case shader-type :fs reload-shader :vs vs-reload-shader)
        is-filename                   (not (instance? clojure.lang.Atom shader-filename-or-str-atom))
        shader-filename               (if is-filename shader-filename-or-str-atom)
        shader-filename               (if (and is-filename (not (nil? shader-filename)))
                                        (.getPath (File. ^String shader-filename)))
        shader-str-atom               (if-not is-filename shader-filename-or-str-atom (atom nil))
        shader-str                    (if-not is-filename @shader-str-atom)
        shader-str                    (if (nil? shader-filename)
                                        @shader-str-atom
                                          (slurp-fs cutter.cutter/the-window-state shader-filename))]
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
  (let [temp-shader-key     (case shader-type :fs :temp-fs-string :vs :temp-vs-string)]
  (swap! cutter.cutter/the-window-state
    assoc
    temp-shader-key "")) nil)

(defn apped-to-temp-string [input shader-type]
  (let [temp-shader-key     (case shader-type :fs :temp-fs-string :vs :temp-vs-string)
        temp-shader-string  (temp-shader-key @cutter.cutter/the-window-state)]
  (swap! cutter.cutter/the-window-state
    assoc
    temp-shader-key (str temp-shader-string input))) nil)

(defn create-temp-shader-file [filename shader-type]
  (let [fd                        (java.io.File/createTempFile filename nil)
        temp-shader-filename-key  (case shader-type :fs :temp-fs-filename :vs :temp-vs-filename)]
    (swap! cutter.cutter/the-window-state
      assoc
      temp-shader-filename-key (.getPath fd))
    (.deleteOnExit fd)
    fd))

(defn write-file [path input]
  (with-open [w (clojure.java.io/writer  path :append true)]
    (.write w input )))

(defn save-temp-shader [filename shader-type]
  (let [fd                  (create-temp-shader-file filename shader-type)
        path                (.getPath fd)
        temp-shader-key     (case shader-type :fs :temp-fs-string :vs :temp-vs-string)
        temp-shader-string  (temp-shader-key @cutter.cutter/the-window-state)]
    (write-file path temp-shader-string)))

(def tmp-str "out vec4 op;
void main(void) {
  vec2 uv = (gl_FragCoord.xy/ iResolution.xy);
  uv.y=1.0-uv.y*1;
  //uv.x = uv.x + 5.5*sin(0.015*iGlobalTime);
  //uv.y = uv.y + 2.5*cos(0.03*iGlobalTime);
  float data1_0=iDataArray1[0];
  float data1_1=iDataArray1[1];
  float data2_0=iDataArray2[0];
  uv=floor(uv * (100+iRandom*iFloat2 )) / ( 100+iRandom*iFloat2 + data1_0);
  //uv=gl_FragCoord.xy*texCoordV/ iResolution.xy;

  vec4 iChannel1_texture=texture2D(iChannel1, uv);
  vec4 iChannel2_texture=texture2D(iChannel2, uv);
  vec4 iChannel3_texture=texture2D(iChannel3, uv);
  vec4 iChannel4_texture=texture2D(iChannel4, uv);
  vec4 iChannel5_texture=texture2D(iChannel5, uv);
  vec4 iChannel6_texture=texture2D(iChannel6, uv);
  vec4 iChannel7_texture=texture2D(iChannel7, uv);

  vec4 ich[6];
  ich[0]=iChannel1_texture;
  ich[1]=iChannel2_texture;
  ich[2]=iChannel3_texture;
  ich[3]=iChannel4_texture;
  ich[4]=iChannel5_texture;
  ich[5]=iChannel6_texture;

  int timefloor=min(int(floor( 6* (1+(sin(iGlobalTime*10.41))))), 5);

  vec4 pf1=texture2D(iPreviousFrame, uv);
  vec4 text=texture2D(iText, uv);
  vec4 ccc=vec4(cos(iGlobalTime*10.41)+data2_0, data1_0, sin(iGlobalTime*3.14+data1_1), 1);
  vec4 ppp=mix(iChannel2_texture, ccc, 0.5);
  float fade_size=2;
  float p1= mix(fade_size, 0.0-fade_size, uv.x-0.125);
  vec4 mixxx =mix(iChannel7_texture, iChannel6_texture, smoothstep(1.0, 0.0+iFloat1, p1));
  op =mixxx;// ich[timefloor];//mixxx;//mix(text, ppp, cos(iGlobalTime*1.41)+data2_0);//ppp;//text;//iChannel1_texture;//iChannel1_texture;
}")

(defn osc-set-fs-shader [input]
  (let [MAX-OSC-SAMPLES   1838
        split-input       (clojure.string/split-lines (clojure.string/trim input))
        split-input       (map (fn [x] (str x "\n")) split-input)
        split-input       (mapv (fn [x] (re-seq #".{1,1838}" x) ) split-input)
        ;_     (println split-input)

        split-input       (flatten split-input)
        ]
    (overtone.osc/osc-send (:osc-client @cutter.cutter/the-window-state) "/cutter/reset-fs-string")
    (doseq [x split-input] (if (not (nil? x)) (overtone.osc/osc-send (:osc-client @cutter.cutter/the-window-state) "/cutter/append-to-fs-string" x )
      (Thread/sleep 200)))
    (overtone.osc/osc-send (:osc-client @cutter.cutter/the-window-state) "/cutter/save-fs-file" )
    (Thread/sleep 200)
    (overtone.osc/osc-send (:osc-client @cutter.cutter/the-window-state) "/cutter/set-fs-shader" )
    ))

;;External shader input osc handlers
(defn set-shader-input-handlers []
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/reset-fs-string"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                      (reset-temp-string :fs))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/reset-vs-string"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                      (reset-temp-string :vs))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/create-fs-file"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                      (create-temp-shader-file "fs-shader" :fs))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/create-vs-file"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                      (create-temp-shader-file "vs-shader" :vs))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/append-to-fs-string"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                      (apped-to-temp-string (str (nth input 0)) :fs))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/append-to-vs-string"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                      (apped-to-temp-string (str (nth input 0)) :vs))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/save-fs-file"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                      (save-temp-shader "fs-shader" :fs))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/save-vs-file"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                      (save-temp-shader "vs-shader" :vs))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/set-fs-shader"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                      (set-shader (:temp-fs-filename @cutter.cutter/the-window-state) :fs))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/set-vs-shader"
    (fn [msg] (let [input                   (:args msg)
                    input                   (vec input)
                    ic                      (count input)]
                      (set-shader (:temp-vs-filename @cutter.cutter/the-window-state) :vs))))
  )

;;Cutter startup osc handlers
;(overtone.osc/osc-send (:osc-client @cutter.cutter/the-window-state) "/cutter/start" "fs" "./test/test.fs" "vs" "./test/test.vs"  "width" 1920 "height" 1080 )
(defn set-start-stop-handler []
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/start"
    (fn [msg] (let [inputmap       (into {} (mapv vec (partition 2 (:args msg))))
                inputkeys       (map keyword (keys inputmap))
                inputvals       (vals inputmap)
                input           (zipmap inputkeys inputvals)
                fs              (if (nil? (:fs input))     (.getPath (clojure.java.io/resource "default.fs")) (:fs input))
                vs              (if (nil? (:vs input))     (.getPath (clojure.java.io/resource "default.vs")) (:vs input))
                width           (if (nil? (:width input))  1280 (:width input))
                height          (if (nil? (:height input)) 800 (:height input))
                title           (if (nil? (:title input))  "cutter" (:title input))
                display-sync-hz (if (nil? (:display-sync-hz input)) 30 (:display-sync-hz input))
                fullscreen?     false]
           (start :fs fs :vs vs :width width :height height :title title :display-sync-hz display-sync-hz))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/start-fullscreen"
    (fn [msg] (let [inputmap       (into {} (mapv vec (partition 2 (:args msg))))
                inputkeys       (map keyword (keys inputmap))
                inputvals       (vals inputmap)
                input           (zipmap inputkeys inputvals)
                fs              (if (nil? (:fs input))     (.getPath (clojure.java.io/resource "default.fs")) (:fs input))
                vs              (if (nil? (:vs input))     (.getPath (clojure.java.io/resource "default.vs")) (:vs input))
                width           (if (nil? (:width input))  1280 (:width input))
                height          (if (nil? (:height input)) 800 (:height input))
                title           (if (nil? (:title input))  "cutter" (:title input))
                display-sync-hz (if (nil? (:display-sync-hz input)) 30 (:display-sync-hz input))
                window-idx      (if (nil? (:window-idx input)) 0 (:window-idx input))
                fullscreen?     true]
           (start-fullscreen :fs fs :vs vs :width width :height height :title title :display-sync-hz display-sync-hz :window-idx window-idx))))
  (osc-handle (:osc-server @cutter.cutter/the-window-state) "/cutter/stop"
    (fn [msg] (stop-cutter))))


(set-start-stop-handler)
(set-shader-input-handlers)
