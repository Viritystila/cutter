(ns #^{:author "(Shadertone), Mikael Reponen (Viritystone)"
       :doc " Core library derived from Shadertone (Roger Allen https://github.com/overtone/shadertone)."}
  cutter.cutter
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [watchtower.core :as watcher]
            [clojure.java.io :as io]
            [while-let.core :as while-let]
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
  { :active                  :no  ;; :yes/:stopping/:no
    :width                   0
    :height                  0
    :title                   ""
    :display-sync-hz         30
    :start-time              0
    :last-time               0
    :window                  nil
    :keyCallback             nil
    ;; geom ids
    :vbo-id                  0
    :vertices-count          0
    :vao-id                  0
    :vboc-id                 0
    :vboi-id                 0
    :indices-count           0
    ;; shader program
    :shader-good             true ;; false in error condition
    :shader-filename         nil
    :shader-str-atom         (atom nil)
    :shader-str              ""
    :vs-id                   0
    :fs-id                   0
    :pgm-id                  0})

;; GLOBAL STATE ATOMS
(defonce the-window-state (atom default-state-values))
;; The reload-shader atom communicates across the gl & watcher threads
(defonce reload-shader (atom false))
(defonce reload-shader-str (atom ""))
;; Atom for the directory watcher future
(defonce watcher-future (atom (future (fn [] nil))))
;; Flag to help avoid reloading shader right after loading it for the
;; first time.
(defonce watcher-just-started (atom true))
(defonce throw-on-gl-error (atom true))

(defn- slurp-fs
  "do whatever it takes to modify shadertoy fragment shader source to
  be useable"
  [locals filename]
  (let [{:keys [tex-types]} @locals
        ;;file-str (slurp filename)
        file-str (str "#version 150\n"
                      "uniform vec3      iResolution;\n"
                      "uniform float     iGlobalTime;\n"
                      "uniform float     iChannelTime[4];\n"
                      "uniform vec3      iChannelResolution[4];\n"
;;                       "uniform vec4      iMouse; \n"
                      ; (uniform-sampler-type-str tex-types 0)
                      ; (uniform-sampler-type-str tex-types 1)
                      ; (uniform-sampler-type-str tex-types 2)
                      ; (uniform-sampler-type-str tex-types 3)
                      ; "uniform sampler2D iCam0; \n"
                      ; "uniform sampler2D iCam1; \n"
                      ; "uniform sampler2D iCam2; \n"
                      ; "uniform sampler2D iCam3; \n"
                      ; "uniform sampler2D iCam4; \n"
                      ; "uniform sampler2D iVideo0; \n"
                      ; "uniform sampler2D iVideo1; \n"
                      ; "uniform sampler2D iVideo2; \n"
                      ; "uniform sampler2D iVideo3; \n"
                      ; "uniform sampler2D iVideo4; \n"
                      ; "uniform vec4      iDate;\n"
                      ; "uniform sampler2D iFftWave; \n"
                      ; "uniform float iDataArray[256]; \n"
                      ; "uniform sampler2D iPreviousFrame; \n"
                      ; "uniform sampler2D iText; \n"
                      "\n"
                      (slurp filename))]
    file-str))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;Init window and opengl;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; ======================================================================
;; code modified from
;; https://github.com/ztellman/penumbra/blob/master/src/penumbra/opengl/core.clj
(defn- get-fields [#^Class static-class]
  (. static-class getFields))
(defn- gl-enum-name
  "Takes the numeric value of a gl constant (i.e. GL_LINEAR), and gives the name"
  [enum-value]
  (if (= 0 enum-value)
    "NONE"
    (.getName #^Field (some
                       #(if (= enum-value (.get #^Field % nil)) % nil)
                       (mapcat get-fields [GL11 GL12 GL13 GL15 GL20])))))
(defn except-gl-errors
  [msg]
  (let [error (GL11/glGetError)
        error-string (str "OpenGL Error(" error "):"
                          (gl-enum-name error) ": " msg)]
    (if (and (not (zero? error)) @throw-on-gl-error)
      (throw (Exception. error-string)))))


(defn- init-window
  "Initialise a shader-powered window with the specified
   display-mode. If true-fullscreen? is true, fullscreen mode is
   attempted if the display-mode is compatible. See display-modes for a
   list of available modes and fullscreen-display-modes for a list of
   fullscreen compatible modes.."
  [locals display-mode title shader-filename shader-str-atom tex-filenames cams videos true-fullscreen? display-sync-hz]
    (when-not (org.lwjgl.glfw.GLFW/glfwInit)
    (throw (IllegalStateException. "Unable to initialize GLFW")))

    (let [
        width               (nth display-mode 0) ;(:width @locals)
        height              (nth display-mode 1);(:height @locals)
        monitor             (org.lwjgl.glfw.GLFW/glfwGetPrimaryMonitor)
        mode                (org.lwjgl.glfw.GLFW/glfwGetVideoMode monitor)
        current-time-millis (System/currentTimeMillis)
        ;tex-filenames       (fill-filenames tex-filenames no-textures)
        ;videos              (fill-filenames videos no-videos)
        ;cams                (sort-cams cams)
        ;tttt                (sort-videos locals videos)
        ;tex-types           (map get-texture-type tex-filenames)
        ]
        (swap! locals
           assoc
           :active          :yes
           :width           width
           :height          height
           :title           title
           :display-sync-hz display-sync-hz
           :start-time      current-time-millis
           :last-time       current-time-millis
           :shader-filename shader-filename
           :shader-str-atom shader-str-atom
           :tex-filenames   tex-filenames
           :cams            cams
           :videos          videos
           ;:tex-types       tex-types
           )
        (println "begin shader slurping")
        (let [shader-str (if (nil? shader-filename)
                       @shader-str-atom
                       (slurp-fs locals (:shader-filename @locals)))])
        (org.lwjgl.glfw.GLFW/glfwDefaultWindowHints)
        (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_VISIBLE                org.lwjgl.glfw.GLFW/GLFW_FALSE)
        (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_RESIZABLE              org.lwjgl.glfw.GLFW/GLFW_FALSE)
        (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_DECORATED              org.lwjgl.glfw.GLFW/GLFW_FALSE)
        (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_OPENGL_PROFILE         org.lwjgl.glfw.GLFW/GLFW_OPENGL_CORE_PROFILE)
        (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_OPENGL_FORWARD_COMPAT  org.lwjgl.glfw.GLFW/GLFW_FALSE)
        (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_CONTEXT_VERSION_MAJOR  4)
        (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_CONTEXT_VERSION_MINOR  6)

        (swap! locals assoc
           :window (org.lwjgl.glfw.GLFW/glfwCreateWindow width height title 0 0))
            (when (= (:window @locals) nil)
            (throw (RuntimeException. "Failed to create the GLFW window")))
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
      ;(init-textures locals)
      ;(init-cams locals)
      ;(init-videos locals)
      ;(init-shaders locals)
      ;(swap! locals assoc :tex-id-fftwave (GL11/glGenTextures))
      ;(init-text-tex locals)
      ;(init-frame-tex locals)
      ;(when (and (not (nil? user-fn)) (:shader-good @locals))
      ;        (user-fn :init (:pgm-id @locals) (:tex-id-fftwave @locals)))
              ))


(defn- run-thread
  [locals mode shader-filename shader-str-atom tex-filenames cams videos title true-fullscreen? display-sync-hz]
  (println "init-window")
  (init-window locals mode title shader-filename shader-str-atom tex-filenames cams videos true-fullscreen? display-sync-hz)
  (println "init-gl")
  (init-gl locals)
  ; (reset! (:frameCount @locals) 0)
  ; (try-reload-shader locals)
  ; (let [startTime               (atom (System/nanoTime))]
  ;   (println "start thread")
  ; (while (and (= :yes (:active @locals))
  ;             (not (org.lwjgl.glfw.GLFW/glfwWindowShouldClose (:window @locals))))
  ;
  ;   ;(time (do
  ;   (reset! startTime (System/nanoTime))
  ;   (update-and-draw locals)
  ;   (org.lwjgl.glfw.GLFW/glfwSwapBuffers (:window @locals))
  ;   (org.lwjgl.glfw.GLFW/glfwPollEvents)
  ;   ;(write-text (str (- (System/nanoTime) @startTime) ) 300 800 10 100 100 0 50 1 true)
  ;   (Thread/sleep  (sleepTime @startTime (System/nanoTime) display-sync-hz))
  ;   ;(write-text (str (- (System/nanoTime) @startTime) ) 300 800 10 100 100 0 50 1 true)
  ;   ;))
  ;   )
  ;   (destroy-gl locals)
     (.free (:keyCallback @locals))
     (org.lwjgl.glfw.GLFW/glfwPollEvents)
     (org.lwjgl.glfw.GLFW/glfwDestroyWindow (:window @locals))
     (org.lwjgl.glfw.GLFW/glfwPollEvents)
     (swap! locals assoc :active :no)
     ;)
  )


(defn- files-exist
  "check to see that the filenames actually exist.  One tweak is to
  allow nil or keyword 'filenames'.  Those are important placeholders.
  Another tweak is to expand names for cubemap textures."
  [filenames]
  (let [full-filenames (flatten filenames)]
    (reduce #(and %1 %2) ; kibit keep
            (for [fn full-filenames]
              (if (or (nil? fn)
                      (and (keyword? fn) (= fn :previous-frame))
                      (.exists (File. ^String fn)))
                true
                (do
                  (println "ERROR:" fn "does not exist.")
                  false))))))


(defn- sane-user-inputs
  [shader-filename shader-str textures title true-fullscreen?]
  (and (files-exist (flatten [shader-filename]))
       (not (and (nil? shader-filename) (nil? shader-str)))))

;; watch the shader-str-atom to reload on a change
(defn- watch-shader-str-atom
  [key identity old new]
  (when (not= old new)
    ;; if already reloading, wait for that to finish
    (while @reload-shader
      ;; FIXME this can hang.  We should timeout instead
      (Thread/sleep 100))
    (reset! reload-shader-str new)
    (reset! reload-shader true)))

;; watch the shader directory & reload the current shader if it changes.
(defn- if-match-reload-shader
  [shader-filename files]
  (if @watcher-just-started
    ;; allow first, automatic call to pass unnoticed
    (reset! watcher-just-started false)
    ;; otherwise do the reload check
    (doseq [f files]
      (when (= (.getPath ^File f) shader-filename)
        ;; set a flag that the opengl thread will use
        (reset! reload-shader true)))))

(defn- start-watcher
  "create a watch for glsl shaders in the directory and return the global
  future atom for that watcher"
  [shader-filename]
  (let [dir (.getParent (File. ^String shader-filename))
        _   (println "dir" dir)]
    (reset! watcher-just-started true)
    (watcher/watcher
     [dir]
     (watcher/rate 100)
     (watcher/file-filter watcher/ignore-dotfiles)
     (watcher/file-filter (watcher/extensions :glsl))
     (watcher/on-change (partial if-match-reload-shader shader-filename)))))

(defn- stop-watcher
  "given a watcher-future f, put a stop to it"
  [f]
  (when-not (or (future-done? f) (future-cancelled? f))
    (if (not (future-cancel f))
      (println "ERROR: unable to stop-watcher!"))))

(defn active?
  "Returns true if the shader display is currently running"
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
  [mode shader-filename-or-str-atom textures cams videos title true-fullscreen? display-sync-hz]
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
    (when (sane-user-inputs shader-filename shader-str textures title true-fullscreen?)
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
                                 textures
                                 cams
                                 videos
                                 title
                                 true-fullscreen?
                                 display-sync-hz)))))))


(defn start
  "Start a new shader display."
  [shader-filename-or-str-atom
   &{:keys [width height title display-sync-hz
            textures cams videos user-data]
     :or {width           1920
          height          1080
          title           "cutter"
          display-sync-hz 60
          textures        []
          cams            []
          videos          []}}]
   (let [mode  [width height]]
    ;(decorate-display!)
    ;(undecorate-display!)
    (start-shader-display mode shader-filename-or-str-atom textures cams videos title false display-sync-hz)))
