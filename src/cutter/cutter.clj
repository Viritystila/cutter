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
    :textures                   {} ;{:filename, }
    :texture-arrays             {}
    :cameras                    {}
    :videos                     {}
    ;Data Arrays
    :dataArray1                  (vec (make-array Float/TYPE 256))
    :dataArray1Buffer            (-> (BufferUtils/createFloatBuffer 256)
                                      (.put (float-array
                                      (vec (make-array Float/TYPE 256))))
                                      (.flip))
    :dataArray2                (vec (make-array Float/TYPE 256))
    :dataArray2Buffer            (-> (BufferUtils/createFloatBuffer 256)
                                  (.put (float-array
                                  (vec (make-array Float/TYPE 256))))
                                  (.flip))
    :dataArray3                  (vec (make-array Float/TYPE 256))
    :dataArray3Buffer            (-> (BufferUtils/createFloatBuffer 256)
                                  (.put (float-array
                                  (vec (make-array Float/TYPE 256))))
                                  (.flip))
    :dataArray4                  (vec (make-array Float/TYPE 256))
    :dataArray4Buffer            (-> (BufferUtils/createFloatBuffer 256)
                                  (.put (float-array
                                  (vec (make-array Float/TYPE 256))))
                                  (.flip))
    ;v4l2
    :save-frames                (atom false)
    :deviceName                 (atom "/dev/video3")
    :deviceId                   (atom 0)
    :minsize                    (atom 0)
    :bff                        (atom 0)
    :isInitialized              (atom false)
    ;; shader uniforms
    :i-uniforms                 {:iResolution   {:type "vec3",      :loc 0, :gltype (fn [id x y z] (GL20/glUniform3f id x y z)),  :extra "", :layout ""},
                                :iGlobalTime    {:type "float",     :loc 0, :gltype (fn [id x] (GL20/glUniform1f id x)),          :extra "", :layout ""},
                                :iPreviousFrame {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "layout (binding=0) "},
                                :iText          {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "layout (binding=1) "},
                                :iChannel1      {:type "sampler2D", :loc 0, :gltype (fn [id x] (GL20/glUniform1i id x)),          :extra "", :layout "layout (binding=2) "},
                                :iDataArray1    {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout ""},
                                :iDataArray2    {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout ""},
                                :iDataArray3    {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout ""},
                                :iDataArray4    {:type "float",     :loc 0, :gltype (fn [id data buf](.flip (.put ^FloatBuffer buf  (float-array data))) (GL20/glUniform1fv  ^Integer id ^FloatBuffer buf)), :extra "[256]", :layout ""}}
     ;textures
     :i-textures     {:iPreviousFrame {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0},
                      :iText          {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0}
                      :iChannel1      {:tex-id 0, :target 0, :height 1, :width 1, :mat 0, :buffer 0,  :internal-format -1, :format -1, :channels 3, :init-opengl true, :queue 0}}
     })
;; GLOBAL STATE ATOMS iPreviousFrame
(defonce the-window-state (atom default-state-values))

;Opencv Java related
(org.bytedeco.javacpp.Loader/load org.bytedeco.javacpp.opencv_java)

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
                i-textures          (assoc i-textures :iText texture)]
                (async/offer! queue (matInfo mat))
                (swap! the-window-state assoc :i-textures i-textures))

                nil)
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
        video-filenames     (cutter.general/remove-inexistent video-filenames (::maximum-videos @locals))
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
      ;(println (:i-textures @locals))
      ;(println :i-textures @locals)
      ;(init-textures locals)
      ;(init-cams locals)
      ;(init-videos locals)
      (init-shaders locals)
      ;(swap! locals assoc :tex-id-fftwave (GL11/glGenTextures))
      ;(init-text-tex locals)
      ;(init-frame-tex locals)
      ;(when (and (not (nil? user-fn)) (:shader-good @locals))
      ;        (user-fn :init (:pgm-id @locals) (:tex-id-fftwave @locals)))
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
          ;(GL13/glActiveTexture (+ GL13/GL_TEXTURE0 tex-id))
          ;(GL11/glBindTexture target tex-id)
          (if (or init? (not= setnbytes nbytes))
            (do
              (try (GL11/glTexImage2D ^Integer tex-image-target 0 ^Integer internal-format
                ^Integer width  ^Integer height 0
                ^Integer format
                GL11/GL_UNSIGNED_BYTE
                buffer))
                (let [texture     (init-texture width height tex-id)
                      texture     (assoc texture :init-opengl false) ;(init-texture width height);
                      i-textures  (assoc i-textures texture-key texture)]
                      (swap! locals assoc :i-textures i-textures)))
            (do
              (if (< 0 (nth image 0))
                (try (GL11/glTexSubImage2D ^Integer tex-image-target 0 0 0
                    ^Integer width  ^Integer height
                    ^Integer format
                    GL11/GL_UNSIGNED_BYTE
                    buffer)))))
          ;(GL11/glBindTexture target 0)
          (except-gl-errors "@ end of load-texture if-stmt")))
;
(defn- get-textures
    [locals texture-key i-uniforms]
    (let [i-textures          (:i-textures @locals)
          texture             (texture-key i-textures)
          queue               (:queue texture)
          tex-id              (:tex-id texture)
          target              (:target texture)
          target              (:target texture)
          image               (if (= nil queue) nil (async/poll! queue))]
          (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 tex-id))
          (GL11/glBindTexture target tex-id)
          (if  (not (nil? image))
                (do (set-opengl-texture locals texture-key image)
                )
                nil)
          (GL11/glBindTexture target 0)
          ))
(defn- draw
  [locals]
  (let [{:keys [width height ;i-resolution-loc
                start-time last-time i-global-time-loc
                i-date-loc
                pgm-id vbo-id vao-id vboi-id vboc-id
                vertices-count
                ;i-channel-loc i-fftwave-loc i-cam-loc i-video-loc
                ;i-channel-res-loc
                i-uniforms
                i-textures
                dataArray1 dataArray2 dataArray3 dataArray4
                dataArray1Buffer dataArray2Buffer dataArray3Buffer dataArray4Buffer
                ;i-dataArray-loc i-previous-frame-loc i-text-loc
                save-frames ;channel-res-buffer   buffer-channel dataArrayBuffer dataArray
                old-pgm-id old-fs-id
                ;tex-ids cams text-id-cam videos text-id-video tex-types tex-id-previous-frame tex-id-text-texture
                ;user-fn
                ;pixel-read-enable
                ;pixel-read-pos-x pixel-read-pos-y
                ;pixel-read-data
                ;save-frames
                ]} @locals
        cur-time    (/ (- last-time start-time) 1000.0)
        ;_           (.flip (.put ^FloatBuffer dataArrayBuffer  (float-array dataArray)))
        ]

    (except-gl-errors "@ draw before clear")

    ;(reset! (:frameCount @locals) (+ @(:frameCount @locals) 1))

    (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_DEPTH_BUFFER_BIT))

    ; ;; activate textures
    ; (dotimes [i (count tex-ids)]
    ;   (when (nth tex-ids i)
    ;     (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 (nth tex-ids i)))
    ;     (cond
    ;      (= :cubemap (nth tex-types i))
    ;      (GL11/glBindTexture GL13/GL_TEXTURE_CUBE_MAP (nth tex-ids i))
    ;      :default
    ;      (GL11/glBindTexture GL11/GL_TEXTURE_2D (nth tex-ids i)))))

    (except-gl-errors "@ draw after activate textures")
     ;(loop-get-cam-textures locals cams)
     ;(loop-get-video-textures locals videos)
     ;(set-text-opengl-texture locals)
;;
    (get-textures locals :iText i-uniforms)

;;     ;; setup our uniform
    ;(:loc (:iResolution i-uniforms))
    ;(:loc (:iGlobalTime i-uniforms))
    ;:gltype
    ;(@(resolve (symbol "squared")) 2)
    ;(println (:gltype (:iResolution i-uniforms)))



    ((:gltype (:iResolution i-uniforms)) (:loc (:iResolution i-uniforms)) width height 1.0)
    ((:gltype (:iGlobalTime i-uniforms)) (:loc (:iGlobalTime i-uniforms)) cur-time)
    ((:gltype (:iDataArray1 i-uniforms)) (:loc (:iDataArray1 i-uniforms)) dataArray1 dataArray1Buffer)
    ((:gltype (:iDataArray2 i-uniforms)) (:loc (:iDataArray2 i-uniforms)) dataArray2 dataArray2Buffer)
    ((:gltype (:iDataArray3 i-uniforms)) (:loc (:iDataArray3 i-uniforms)) dataArray4 dataArray3Buffer)
    ((:gltype (:iDataArray4 i-uniforms)) (:loc (:iDataArray4 i-uniforms)) dataArray1 dataArray4Buffer)

    ((:gltype (:iText i-uniforms)) (:loc (:iText i-uniforms)) 0)



;     (GL20/glUniform1i (nth i-channel-loc 0) 1)
;     (GL20/glUniform1i (nth i-channel-loc 1) 2)
;     (GL20/glUniform1i (nth i-channel-loc 2) 3)
;     (GL20/glUniform1i (nth i-channel-loc 3) 4)
;     (GL20/glUniform1i (nth i-cam-loc 0) 5)
;     (GL20/glUniform1i (nth i-cam-loc 1) 6)
;     (GL20/glUniform1i (nth i-cam-loc 2) 7)
;     (GL20/glUniform1i (nth i-cam-loc 3) 8)
;     (GL20/glUniform1i (nth i-cam-loc 4) 9)
;     (GL20/glUniform1i (nth i-video-loc 0) 10)
;     (GL20/glUniform1i (nth i-video-loc 1) 11)
;     (GL20/glUniform1i (nth i-video-loc 2) 12)
;     (GL20/glUniform1i (nth i-video-loc 3) 13)
;     (GL20/glUniform1i (nth i-video-loc 4) 14)
;     (GL20/glUniform1i (nth i-fftwave-loc 0) 15)
;     (GL20/glUniform1i (nth i-text-loc 0) 16)
     ;
     ; (GL20/glUniform3fv  ^Integer i-channel-res-loc ^FloatBuffer channel-res-buffer)
     ; (GL20/glUniform4f i-date-loc cur-year cur-month cur-day cur-seconds)
     ; (GL20/glUniform1fv  ^Integer i-dataArray-loc ^FloatBuffer dataArrayBuffer)

    ;; get vertex array ready
     (GL30/glBindVertexArray vao-id)
     (GL20/glEnableVertexAttribArray 0)
     ;(GL20/glEnableVertexAttribArray 1)

     ;(GL11/glEnableClientState GL11/GL_VERTEX_ARRAY)
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
         ;(GL13/glActiveTexture (+ GL13/GL_TEXTURE0 (:tex-id (:iPreviousFrame i-textures))))
         ;(GL11/glBindTexture GL11/GL_TEXTURE_2D (:tex-id (:iPreviousFrame i-textures)))
         (GL11/glGetTexImage GL11/GL_TEXTURE_2D 0 GL11/GL_RGB GL11/GL_UNSIGNED_BYTE  ^ByteBuffer (:buffer (:iPreviousFrame i-textures)))
         (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)
         (org.bytedeco.javacpp.v4l2/v4l2_write @(:deviceId @the-window-state) (new org.bytedeco.javacpp.BytePointer (:buffer (:iPreviousFrame i-textures))) (long  @(:minsize @the-window-state))))
         nil)
     (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)

     (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER 0)
     (GL20/glDisableVertexAttribArray 0)
     ;(GL20/glDisableVertexAttribArray 1)
     ;(GL30/glBindVertexArray 0)
     ;(GL11/glDisableClientState GL11/GL_VERTEX_ARRAY)
    ; ;; unbind textures
    ; (doseq [i (remove nil? tex-ids)]
    ;     (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 i))
    ;     (GL11/glBindTexture GL13/GL_TEXTURE_CUBE_MAP 0)
    ;     (GL11/glBindTexture GL11/GL_TEXTURE_2D 0))
    ;
    ; ;cams
    ; (dotimes [i (count text-id-cam)]
    ;     (when (nth text-id-cam i)
    ;     (if (= nil @(nth (:frame-set-cam @locals) i))
    ;         (do (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 i))
    ;         (GL11/glBindTexture GL11/GL_TEXTURE_2D 0))
    ;         nil )))
    ; ;videos
    ; (dotimes [i (count text-id-video)]
    ;     (when (nth text-id-video i)
    ;         (if (= nil @(nth (:frame-set-video @locals) i))
    ;             (do (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 @(nth text-id-video i)))
    ;                 (GL11/glBindTexture GL11/GL_TEXTURE_2D 0))
    ;             nil)))
    ; (do
    ;     (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 tex-id-text-texture))
    ;     (GL11/glBindTexture GL11/GL_TEXTURE_2D 0))
    ;
    ; (except-gl-errors "@ draw prior to post-draw")
    ;
    ; (GL20/glUseProgram 0)
    ; (except-gl-errors "@ draw after post-draw")
    ;             ;Copying the previous image to its own texture
    ;(GL13/glActiveTexture (+ GL13/GL_TEXTURE0 tex-id-previous-frame))
    ; (GL11/glBindTexture GL11/GL_TEXTURE_2D tex-id-previous-frame)
    ; (GL11/glCopyTexImage2D GL11/GL_TEXTURE_2D 0 GL11/GL_RGB 0 0 width height 0)
    ; (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)

    ; (if @save-frames
    ;   (do ; download it and copy the previous image to its own texture
    ;     (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 tex-id-previous-frame))
    ;     (GL11/glBindTexture GL11/GL_TEXTURE_2D tex-id-previous-frame)
    ;     (GL11/glGetTexImage GL11/GL_TEXTURE_2D 0 GL11/GL_RGB GL11/GL_UNSIGNED_BYTE  ^ByteBuffer @bytebuffer-frame)
    ;     (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)
    ;     (org.bytedeco.javacpp.v4l2/v4l2_write @(:deviceId @the-window-state) (new org.bytedeco.javacpp.BytePointer @bytebuffer-frame) (long  @(:minsize @the-window-state))))
    ;     nil)
    ;(except-gl-errors "@ draw after copy")


      ))


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
     ;;Stop and release cams
    ;(println " Cams tbd" (:cams @the-window-state))
    ;(doseq [i (remove nil? (:cams @the-window-state))](println "release cam " i)(release-cam-textures i))
    ;(swap! locals assoc :cams (vec (replicate no-cams nil)))
    ;Stop and release video release-cam-textures
    ;(println " Videos tbd" (:videos @the-window-state))
    ;(doseq [i (:video-no-id @the-window-state)]
  ;      (if (= @i nil) (println "no video")  (do (release-video-textures @i))))
  ;  (swap! locals assoc :videos (vec (replicate no-videos nil)))
    ;stop recording
    ;(closeV4L2output)
    ;; Delete any user state
    ;(when user-fn
  ;    (user-fn :destroy pgm-id (:tex-id-fftwave @locals)))
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
