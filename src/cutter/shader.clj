(ns #^{:author "(Shadertone), Mikael Reponen (Viritystone)"
       :doc " Core library derived from Shadertone (Roger Allen https://github.com/overtone/shadertone)."}
  cutter.shader
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [watchtower.core :as watcher]
            [clojure.java.io :as io]
            clojure.string)
  (:import (java.nio IntBuffer ByteBuffer FloatBuffer ByteOrder)
           (org.lwjgl BufferUtils)
           (java.io File FileInputStream)
           (java.lang.reflect Field)
           (org.lwjgl.glfw GLFW GLFWErrorCallback GLFWKeyCallback)
           (org.lwjgl.opengl GL GL11 GL12 GL13 GL15 GL20 GL30 GL40)))


;; The reload-shader atom communicates across the gl & watcher threads
(defonce reload-shader (atom false))
(defonce reload-shader-str (atom ""))
;; Atom for the directory watcher future
(defonce watcher-future (atom (future (fn [] nil))))
;; Flag to help avoid reloading shader right after loading it for the
;; first time.
(defonce watcher-just-started (atom true))
(defonce throw-on-gl-error (atom true))

(defn slurp-fs
  "do whatever it takes to modify shadertoy fragment shader source to
  be useable"
  [locals filename]
  (let [{:keys [tex-types]} @locals
        ;;file-str (slurp filename)
        file-str (str "#version 330\n"
                      "uniform vec3      iResolution;\n"
                      "uniform float     iGlobalTime;\n"
                      "uniform float     iChannelTime[4];\n"
                      ;"uniform vec3      iChannelResolution[4];\n"
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
;; ======================================================================

(def vs-shader
  (str "#version 330\n"
       "layout(location = 0) in vec3 vertexPosition_modelspace;"
       "\n"
       "in vec4 in_Position;\n"
       "in vec4 in_Color;\n"
       "uniform float in_Angle;\n"
       "\n"
       "out vec4 pass_Color;\n"
       "\n"
       "void main(void) {\n"
       "    gl_Position = in_Position;\n"
       "}\n"))

(defn- load-shader
  [^String shader-str ^Integer shader-type]
  (let [shader-id         (GL20/glCreateShader shader-type)
        _ (except-gl-errors "@ load-shader glCreateShader ")
        _                 (GL20/glShaderSource shader-id shader-str)
        _ (except-gl-errors "@ load-shader glShaderSource ")
        _                 (GL20/glCompileShader shader-id)
        _ (except-gl-errors "@ load-shader glCompileShader ")
        gl-compile-status (GL20/glGetShaderi shader-id GL20/GL_COMPILE_STATUS)
        _ (except-gl-errors "@ end of let load-shader")]
    (when (== gl-compile-status GL11/GL_FALSE)
      (println "ERROR: Loading a Shader:")
      (println (GL20/glGetShaderInfoLog shader-id 10000)))
    [gl-compile-status shader-id]))


(defn init-shaders
  [locals]
  (let [[ok? vs-id] (load-shader vs-shader GL20/GL_VERTEX_SHADER)
        _           (assert (== ok? GL11/GL_TRUE)) ;; something is really wrong if our vs is bad
        _           (if (nil? (:shader-filename @locals))
                      (println "Loading shader from string")
                      (println "Loading shader from file:" (:shader-filename @locals)))
        [ok? fs-id] (load-shader (:shader-str @locals) GL20/GL_FRAGMENT_SHADER)]
    (if (== ok? GL11/GL_TRUE)
      (let [pgm-id                (GL20/glCreateProgram)
            _ (except-gl-errors "@ let init-shaders glCreateProgram")
            _                     (GL20/glAttachShader pgm-id vs-id)
            _ (except-gl-errors "@ let init-shaders glAttachShader VS")
            _                     (GL20/glAttachShader pgm-id fs-id)
            _ (except-gl-errors "@ let init-shaders glAttachShader FS")
            _                     (GL20/glLinkProgram pgm-id)
            _ (except-gl-errors "@ let init-shaders glLinkProgram")
            gl-link-status        (GL20/glGetProgrami pgm-id GL20/GL_LINK_STATUS)
            _ (except-gl-errors "@ let init-shaders glGetProgram link status")
            _                     (when (== gl-link-status GL11/GL_FALSE)
                                    (println "ERROR: Linking Shaders:")
                                    (println (GL20/glGetProgramInfoLog pgm-id 10000)))
            _ (except-gl-errors "@ let before GetUniformLocation")
            i-resolution-loc        (GL20/glGetUniformLocation pgm-id "iResolution")
            i-global-time-loc       (GL20/glGetUniformLocation pgm-id "iGlobalTime")
            i-channel-time-loc      (GL20/glGetUniformLocation pgm-id "iChannelTime")
;;             i-mouse-loc             (GL20/glGetUniformLocation pgm-id "iMouse")
            ;
            ; i-channel0-loc          (GL20/glGetUniformLocation pgm-id "iChannel0")
            ; i-channel1-loc          (GL20/glGetUniformLocation pgm-id "iChannel1")
            ; i-channel2-loc          (GL20/glGetUniformLocation pgm-id "iChannel2")
            ; i-channel3-loc          (GL20/glGetUniformLocation pgm-id "iChannel3")
            ;
            ; i-cam0-loc              (GL20/glGetUniformLocation pgm-id "iCam0")
            ; i-cam1-loc              (GL20/glGetUniformLocation pgm-id "iCam1")
            ; i-cam2-loc              (GL20/glGetUniformLocation pgm-id "iCam2")
            ; i-cam3-loc              (GL20/glGetUniformLocation pgm-id "iCam3")
            ; i-cam4-loc              (GL20/glGetUniformLocation pgm-id "iCam4")
            ;
            ; i-video0-loc            (GL20/glGetUniformLocation pgm-id "iVideo0")
            ; i-video1-loc            (GL20/glGetUniformLocation pgm-id "iVideo1")
            ; i-video2-loc            (GL20/glGetUniformLocation pgm-id "iVideo2")
            ; i-video3-loc            (GL20/glGetUniformLocation pgm-id "iVideo3")
            ; i-video4-loc            (GL20/glGetUniformLocation pgm-id "iVideo4")
            ;
            ; i-channel-res-loc       (GL20/glGetUniformLocation pgm-id "iChannelResolution")
            ; i-date-loc              (GL20/glGetUniformLocation pgm-id "iDate")
            ;
            ; i-fftwave-loc           (GL20/glGetUniformLocation pgm-id "iFftWave")
            ;
            ; i-dataArray-loc         (GL20/glGetUniformLocation pgm-id "iDataArray")
            ;
            ; i-text-loc              (GL20/glGetUniformLocation pgm-id "iText")
            ;
            ; i-previous-frame-loc    (GL20/glGetUniformLocation pgm-id "iPreviousFrame")
            _ (except-gl-errors "@ end of let init-shaders")
            ]

        (swap! locals
               assoc
               :shader-good true
               :vs-id vs-id
               :fs-id fs-id
               :pgm-id pgm-id
               :i-resolution-loc i-resolution-loc
               :i-global-time-loc i-global-time-loc
               :i-channel-time-loc i-channel-time-loc
;;                :i-mouse-loc i-mouse-loc
               ; :i-channel-loc [i-channel0-loc i-channel1-loc i-channel2-loc i-channel3-loc]
               ; :i-fftwave-loc [i-fftwave-loc]
               ; :i-dataArray-loc i-dataArray-loc
               ; :i-previous-frame-loc [i-previous-frame-loc]
               ; :i-text-loc           [i-text-loc]
               ; :i-cam-loc [i-cam0-loc i-cam1-loc i-cam2-loc i-cam3-loc i-cam4-loc]
               ; :i-video-loc [i-video0-loc i-video1-loc i-video2-loc i-video3-loc i-video4-loc]
               ; :i-channel-res-loc i-channel-res-loc
               ; :i-date-loc i-date-loc
               ))
      ;; we didn't load the shader, don't be drawing
      (swap! locals assoc :shader-good false))))

(defn try-reload-shader
  [locals]
  (let [{:keys [vs-id fs-id pgm-id shader-filename user-fn]} @locals
        vs-id (if (= vs-id 0)
                (let [[ok? vs-id] (load-shader vs-shader GL20/GL_VERTEX_SHADER)
                      _ (assert (== ok? GL11/GL_TRUE))]
                  vs-id)
                vs-id)
        fs-shader       (if (nil? shader-filename)
                          @reload-shader-str
                          (slurp-fs locals shader-filename))
        [ok? new-fs-id] (load-shader fs-shader GL20/GL_FRAGMENT_SHADER)
        _               (reset! reload-shader false)]
    (if (== ok? GL11/GL_FALSE)
      ;; we didn't reload a good shader. Go back to the old one if possible
      (when (:shader-good @locals)
        (GL20/glUseProgram pgm-id)
        (except-gl-errors "@ try-reload-shader useProgram1"))
      ;; the load shader went well, keep going...
      (let [new-pgm-id     (GL20/glCreateProgram)
            _ (except-gl-errors "@ try-reload-shader glCreateProgram")
            _              (GL20/glAttachShader new-pgm-id vs-id)
            _ (except-gl-errors "@ try-reload-shader glAttachShader VS")
            _              (GL20/glAttachShader new-pgm-id new-fs-id)
            _ (except-gl-errors "@ try-reload-shader glAttachShader FS")
            _              (GL20/glLinkProgram new-pgm-id)
            _ (except-gl-errors "@ try-reload-shader glLinkProgram")
            gl-link-status (GL20/glGetProgrami new-pgm-id GL20/GL_LINK_STATUS)
            _ (except-gl-errors "@ end of let try-reload-shader")]
        (if (== gl-link-status GL11/GL_FALSE)
          (do
            (println "ERROR: Linking Shaders: (reloading previous program)")
            (println (GL20/glGetProgramInfoLog new-pgm-id 10000))
            (GL20/glUseProgram pgm-id)
            (except-gl-errors "@ try-reload-shader useProgram2"))
          (let [_ (println "Reloading shader:" shader-filename)
                i-resolution-loc    (GL20/glGetUniformLocation new-pgm-id "iResolution")
                i-global-time-loc   (GL20/glGetUniformLocation new-pgm-id "iGlobalTime")
                i-channel-time-loc  (GL20/glGetUniformLocation new-pgm-id "iChannelTime")
;;                 i-mouse-loc         (GL20/glGetUniformLocation new-pgm-id "iMouse")
                ; i-channel0-loc      (GL20/glGetUniformLocation new-pgm-id "iChannel0")
                ; i-channel1-loc      (GL20/glGetUniformLocation new-pgm-id "iChannel1")
                ; i-channel2-loc      (GL20/glGetUniformLocation new-pgm-id "iChannel2")
                ; i-channel3-loc      (GL20/glGetUniformLocation new-pgm-id "iChannel3")
                ;
                ; i-cam0-loc          (GL20/glGetUniformLocation new-pgm-id "iCam0")
                ; i-cam1-loc          (GL20/glGetUniformLocation new-pgm-id "iCam1")
                ; i-cam2-loc          (GL20/glGetUniformLocation new-pgm-id "iCam2")
                ; i-cam3-loc          (GL20/glGetUniformLocation new-pgm-id "iCam3")
                ; i-cam4-loc          (GL20/glGetUniformLocation new-pgm-id "iCam4")
                ;
                ; i-video0-loc        (GL20/glGetUniformLocation new-pgm-id "iVideo0")
                ; i-video1-loc        (GL20/glGetUniformLocation new-pgm-id "iVideo1")
                ; i-video2-loc        (GL20/glGetUniformLocation new-pgm-id "iVideo2")
                ; i-video3-loc        (GL20/glGetUniformLocation new-pgm-id "iVideo3")
                ; i-video4-loc        (GL20/glGetUniformLocation new-pgm-id "iVideo4")
                ;
                ; i-channel-res-loc   (GL20/glGetUniformLocation new-pgm-id "iChannelResolution")
                ; i-date-loc          (GL20/glGetUniformLocation new-pgm-id "iDate")
                ; i-fftwave-loc       (GL20/glGetUniformLocation new-pgm-id "iFftWave")
                ; i-dataArray-loc     (GL20/glGetUniformLocation new-pgm-id "iDataArray")
                ;
                ; i-text-loc              (GL20/glGetUniformLocation new-pgm-id "iText")
                ;
                ; i-previous-frame-loc    (GL20/glGetUniformLocation new-pgm-id "iPreviousFrame")
                ]
            (GL20/glUseProgram new-pgm-id)
            (except-gl-errors "@ try-reload-shader useProgram")
            (when user-fn
              (user-fn :init new-pgm-id (:tex-id-fftwave @locals)))
            ;; cleanup the old program
            (when (not= pgm-id 0)
              (GL20/glDetachShader pgm-id vs-id)
              (GL20/glDetachShader pgm-id fs-id)
              (GL20/glDeleteShader fs-id))
            (except-gl-errors "@ try-reload-shader detach/delete")
            (swap! locals
                   assoc
                   :shader-good true
                   :fs-id new-fs-id
                   :pgm-id new-pgm-id
                   :i-resolution-loc i-resolution-loc
                   :i-global-time-loc i-global-time-loc
                   :i-channel-time-loc i-channel-time-loc
;;                    :i-mouse-loc i-mouse-loc
                   ;i-channel-loc [i-channel0-loc i-channel1-loc i-channel2-loc i-channel3-loc]
                   ;;:i-fftwave-loc [i-fftwave-loc]
                   ;;:i-previous-frame-loc [i-previous-frame-loc]
                   ;;:i-text-loc           [i-text-loc]
                   ;;:i-dataArray-loc i-dataArray-loc
                   ;;:i-cam-loc [i-cam0-loc i-cam1-loc i-cam2-loc i-cam3-loc i-cam4-loc]
                   ;;:i-video-loc [i-video0-loc i-video1-loc i-video2-loc i-video3-loc i-video4-loc]
                   ;:i-channel-res-loc i-channel-res-loc
                   ;:i-date-loc i-date-loc
                   :shader-str fs-shader)))))))
;

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
