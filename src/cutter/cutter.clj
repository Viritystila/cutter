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
      ; (.start (Thread.
      ;          (fn [] (run-thread the-window-state
      ;                            mode
      ;                            shader-filename
      ;                            shader-str-atom
      ;                            textures
      ;                            cams
      ;                            videos
      ;                            title
      ;                            true-fullscreen?
      ;                            user-fn
      ;                            display-sync-hz))))

                                 )))


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
