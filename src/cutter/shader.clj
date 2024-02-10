(ns #^{:author "Mikael Reponen (Viritystone)"
       :doc "Core library derived from Shadertone (Roger Allen https://github.com/overtone/shadertone)."}
  cutter.shader
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [watchtower.core :as watcher]
            [clojure.java.io :as io]
            [cutter.general :refer :all]
            clojure.string)
  (:import (java.nio IntBuffer ByteBuffer FloatBuffer ByteOrder)
           (org.lwjgl BufferUtils)
           (java.io File FileInputStream)
           (java.lang.reflect Field)
           (org.lwjgl.glfw GLFW GLFWErrorCallback GLFWKeyCallback)
           (org.lwjgl.opengl GL GL11 GL12 GL13 GL15 GL20 GL30 GL32 GL40)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Fragment shader watching;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The reload-shader atom communicates across the gl & watcher threads
(defonce reload-shader (atom false))
(defonce reload-shader-str (atom ""))
;; Atom for the directory watcher future
(defonce watcher-future (atom (future (fn [] nil))))
;;(defonce vs-watcher-future (atom (future (fn [] nil))))
;;(defonce gs-watcher-future (atom (future (fn [] nil))))

;; Flag to help avoid reloading shader right after loading it for the
;; first time.
(defonce watcher-just-started (atom true))
;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Vertex shader watching;;
;;;;;;;;;;;;;;;;;;;;;;;;;;
(defonce vs-reload-shader (atom false))
(defonce vs-reload-shader-str (atom ""))
;; Atom for the directory watcher future
(defonce vs-watcher-future (atom (future (fn [] nil))))
;; Flag to help avoid reloading shader right after loading it for the
;; first time.
(defonce vs-watcher-just-started (atom true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Geometry shader watching;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defonce gs-reload-shader (atom false))
(defonce gs-reload-shader-str (atom ""))
;; Atom for the directory watcher future
(defonce gs-watcher-future (atom (future (fn [] nil))))
;; Flag to help avoid reloading shader right after loading it for the
;; first time.
(defonce gs-watcher-just-started (atom true))

;;;;;
(defonce throw-on-gl-error (atom true))

;(GL20/glGetUniformLocation pgm-id (name x))
(defn generate-uniform-locs [locals pgm-id]
  (let [{:keys [i-uniforms]} @locals
        loc-keys (keys i-uniforms)
        i-uniforms (into {} (map (fn [x] {x (assoc (x i-uniforms) :loc (GL20/glGetUniformLocation pgm-id (name x))  )} ) loc-keys))]
               i-uniforms))

(defn uniforms-to-string [locals]
  (let [{:keys [i-uniforms]} @locals] :layout
  (clojure.string/join (mapv (fn [x](str (str (:layout (x i-uniforms))) " " "uniform " (str (:type (x i-uniforms)) (:extra (x i-uniforms))) " " (name x) "; \n" ) ) (keys i-uniforms)  ))))



(def inputs
  (str "layout(location = 0) in vec4 vertexPosition_modelspace;\n"
       "layout(location = 1) in vec3 colors_modelspace;\n"
       "layout(location = 2) in vec3 index_modelspace;\n"
       "layout(location = 3) in vec2 uv_modelspace;\n"
       "layout(location = 4) in vec3 normals_modelspace;\n"
       "layout(location = 5) in vec4 modelScale;\n"
       "layout(location = 6) in vec4 modelPosition;\n"
       "layout(location = 7) in mat4 modelRotation;\n"
))


(defn slurp-fs
  [locals filename type]
  (let [{:keys [tex-types shader-ver]} @locals
        ;;file-str (slurp filename)
        file-str (str shader-ver
                      "\n"
                      (if (not (= :gs type)) inputs "")
                      "\n"
                      (uniforms-to-string locals)
                      "\n"
                      (slurp filename))]
    ;(println file-str)
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

;;
(defn- load-shader
  [^String shader-str ^Integer shader-type]
  ;;(println shader-str)
  (let [;;shader-str        (str inputs shader-str)
        ;;_ (println shader-str)
        shader-id         (GL20/glCreateShader shader-type)
        _                 (except-gl-errors "@ load-shader glCreateShader ")
        _                 (GL20/glShaderSource shader-id shader-str)
        _                 (except-gl-errors "@ load-shader glShaderSource ")
        _                 (GL20/glCompileShader shader-id)
        _                 (except-gl-errors "@ load-shader glCompileShader ")
        gl-compile-status (GL20/glGetShaderi shader-id GL20/GL_COMPILE_STATUS)
        _                 (except-gl-errors "@ end of let load-shader")]
    (when (== gl-compile-status GL11/GL_FALSE)
      (println "ERROR: Loading a Shader:")
      (println (GL20/glGetShaderInfoLog shader-id 10000)))
    [gl-compile-status shader-id]))


(defn init-shaders
  [locals]
  (print "init-shaders")
  ;;(println (:vs-shader-str @locals))
  ;;(println (:shader-str @locals))
  (println )
  (let [_           (if (nil? (:vs-shader-filename @locals))
                      (println "Loading vertex shader from string")
                      (println "Loading vertex shader from file:" (:vs-shader-filename @locals)))
        [ok? vs-id] (load-shader (:vs-shader-str @locals) GL20/GL_VERTEX_SHADER)
        _           (assert (== ok? GL11/GL_TRUE))
        _           (if (nil? (:gs-shader-filename @locals))
                      (println "Loading geometry shader from string")
                      (println "Loading geometry shader from file:" (:gs-shader-filename @locals)))
        [ok? gs-id] (load-shader (:gs-shader-str @locals) GL32/GL_GEOMETRY_SHADER)
        _           (assert (== ok? GL11/GL_TRUE))
        _           (if (nil? (:shader-filename @locals))
                      (println "Loading fragment shader from string")
                      (println "Loading fragment shader from file:" (:shader-filename @locals)))
        [ok? fs-id] (load-shader (:shader-str @locals) GL20/GL_FRAGMENT_SHADER) ]
    (if (== ok? GL11/GL_TRUE)
      (let [pgm-id                (GL20/glCreateProgram)
            _                     (except-gl-errors "@ let init-shaders glCreateProgram")
            _                     (GL20/glAttachShader pgm-id vs-id)
            _                     (except-gl-errors "@ let init-shaders glAttachShader VS")
            _                     (GL20/glAttachShader pgm-id gs-id)
            _                     (except-gl-errors "@ let init-shaders glAttachShader GS")
            _                     (GL20/glAttachShader pgm-id fs-id)
            _                     (except-gl-errors "@ let init-shaders glAttachShader FS")
            _                     (GL20/glLinkProgram pgm-id)
            _                     (except-gl-errors "@ let init-shaders glLinkProgram")
            gl-link-status        (GL20/glGetProgrami pgm-id GL20/GL_LINK_STATUS)
            _                     (except-gl-errors "@ let init-shaders glGetProgram link status")
            _                     (when (== gl-link-status GL11/GL_FALSE)
                                    (println "ERROR: Linking Shaders:")
                                    (println (GL20/glGetProgramInfoLog pgm-id 10000)))
            _                     (except-gl-errors "@ let before GetUniformLocation")
            i-uniforms            (generate-uniform-locs locals pgm-id)
            _ (except-gl-errors "@ end of let init-shaders")]
        (swap! locals
               assoc
               :shader-good true
               :vs-shader-good true
               :gs-shader-good true
               :vs-id vs-id
               :fs-id fs-id
               :gs-id gs-id
               :pgm-id pgm-id
               :i-uniforms i-uniforms
               ))
      ;; we didn't load the shader, don't be drawing
      (swap! locals assoc :shader-good false :vs-shader-good false :gs-shader-good false))))

(defn try-reload-shader
  [locals]
  (let [{:keys [vs-id
                fs-id
                gs-id
                pgm-id
                shader-filename
                vs-shader-filename
                gs-shader-filename
                user-fn]} @locals
        ; vs-id (if (= vs-id 0)
        ;         (let [[ok? vs-id] (load-shader vs-shader GL20/GL_VERTEX_SHADER)
        ;               _ (assert (== ok? GL11/GL_TRUE))]
        ;           vs-id)
        ;         vs-id)
        vs-shader       (if (nil? vs-shader-filename)
                          @vs-reload-shader-str
                          (slurp-fs locals vs-shader-filename :vs))
        [ok? new-vs-id] (load-shader vs-shader GL20/GL_VERTEX_SHADER)
        _               (reset! vs-reload-shader false)
        gs-shader       (if (nil? gs-shader-filename)
                          @gs-reload-shader-str
                          (slurp-fs locals gs-shader-filename :gs))
        [ok? new-gs-id] (load-shader gs-shader GL32/GL_GEOMETRY_SHADER)
        _               (reset! gs-reload-shader false)
        fs-shader       (if (nil? shader-filename)
                          @reload-shader-str
                          (slurp-fs locals shader-filename :fs))
        [ok? new-fs-id] (load-shader fs-shader GL20/GL_FRAGMENT_SHADER)
        _               (reset! reload-shader false)]
    ;(println gs-shader)
    (if (== ok? GL11/GL_FALSE)
      ;; we didn't reload a good shader. Go back to the old one if possible
      (when (and
             (:shader-good @locals)
             (:vs-shader-good @locals)
             (:gs-shader-good @locals))
        (GL20/glUseProgram pgm-id)
        (except-gl-errors "@ try-reload-shader useProgram1"))
      ;; the load shader went well, keep going...
      (let [new-pgm-id     (GL20/glCreateProgram)
            _ (except-gl-errors "@ try-reload-shader glCreateProgram")
            _              (GL20/glAttachShader new-pgm-id new-vs-id)
            _ (except-gl-errors "@ try-reload-shader glAttachShader VS")
            _              (GL20/glAttachShader new-pgm-id new-gs-id)
            _ (except-gl-errors "@ try-reload-shader glAttachShader GS")
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
            (except-gl-errors "@ try-reload-shader"))
          (let [_ (println "Reloading fragment shader:" shader-filename "vertex shader:" vs-shader-filename "and geometry shader" gs-shader-filename)
                i-uniforms                     (generate-uniform-locs locals new-pgm-id ) ]
            (GL20/glUseProgram new-pgm-id)
            (except-gl-errors "@ try-reload-shader useProgram")
            ; (when user-fn
            ;   (user-fn :init new-pgm-id (:tex-id-fftwave @locals)))
            ;; cleanup the old program
            (when (not= pgm-id 0)
              (GL20/glDetachShader pgm-id vs-id)
              (GL20/glDetachShader pgm-id gs-id)
              (GL20/glDetachShader pgm-id fs-id)
              (GL20/glDeleteShader fs-id)
              (GL20/glDeleteShader gs-id)
              (GL20/glDeleteShader vs-id))
            (except-gl-errors "@ try-reload-shader detach/delete")
            (swap! locals
                   assoc
                   :shader-good true
                   :vs-shader-good true
                   :fs-id new-fs-id
                   :gs-id new-gs-id
                   :vs-id new-vs-id
                   :pgm-id new-pgm-id
                   :i-uniforms i-uniforms
                   :shader-str fs-shader
                   :vs-shader-str vs-shader)))))))


;; watch the shader-str-atom to reload on a change
(defn watch-shader-str-atom
  [key identity old new]
  (when (not= old new)
    ;; if already reloading, wait for that to finish
    (while @reload-shader
      ;; FIXME this can hang.  We should timeout instead
      (Thread/sleep 100 0))
    (reset! reload-shader-str new)
    (reset! reload-shader true)))


(defn vs-watch-shader-str-atom
  [key identity old new]
  ;(println "VSVSVSVSVSV")
  (when (not= old new)
    ;; if already reloading, wait for that to finish
    (while @vs-reload-shader
      ;; FIXME this can hang.  We should timeout instead
      (Thread/sleep 100 0))
    (reset! vs-reload-shader-str new)
    (reset! vs-reload-shader true)))

(defn gs-watch-shader-str-atom
  [key identity old new]
  ;(print "GSGSGSGSGSG")
  (when (not= old new)
    ;; if already reloading, wait for that to finish
    (while @gs-reload-shader
      ;; FIXME this can hang.  We should timeout instead
      (Thread/sleep 100 0))
    (reset! gs-reload-shader-str new)
    (reset! gs-reload-shader true)))

;; watch the shader directory & reload the current shader if it changes.
(defn- if-match-reload-shader
  [shader-filename files]
  (println "FS reload")
  (if @watcher-just-started
    ;; allow first, automatic call to pass unnoticed
    (reset! watcher-just-started false)
    ;; otherwise do the reload check
    (doseq [f files]
      (when (= (.getPath ^File f) shader-filename)
        ;; set a flag that the opengl thread will use
        (reset! reload-shader true)))))

(defn- vs-if-match-reload-shader
  [shader-filename files]
  (println "VS reload")
  (if @vs-watcher-just-started
    ;; allow first, automatic call to pass unnoticed
    (reset! vs-watcher-just-started false)
    ;; otherwise do the reload check
    (doseq [f files]
      ;(println f)
      (when (= (.getPath ^File f) shader-filename)
        ;; set a flag that the opengl thread will use
        (reset! vs-reload-shader true)))))

;
(defn- gs-if-match-reload-shader
  [shader-filename files]
  (println "GS reload")
  (if @gs-watcher-just-started
    ;; allow first, automatic call to pass unnoticed
    (reset! gs-watcher-just-started false)
    ;; otherwise do the reload check
    (doseq [f files]
      ;(println f)
      (when (= (.getPath ^File f) shader-filename)
        ;; set a flag that the opengl thread will use
        (reset! gs-reload-shader true)))))


(defn start-watcher
  "create a watch for glsl shaders in the directory and return the global
  future atom for that watcher"
  [shader-filename]
  (let [dir (.getParent (File. ^String shader-filename))
        _   (println "FS dir" dir)]
    (reset! watcher-just-started true)
    (println "FS" shader-filename)
    (watcher/watcher
      [shader-filename]
      (watcher/rate 200)
      (watcher/file-filter watcher/ignore-dotfiles)
      (watcher/file-filter (watcher/extensions :fs))
      (watcher/on-change (partial if-match-reload-shader shader-filename)))))

(defn vs-start-watcher
  "create a watch for glsl shaders in the directory and return the global
  future atom for that watcher"
  [shader-filename]
  (let [dir (.getParent (File. ^String shader-filename))
        _   (println "VS dir" dir)]
    (reset! vs-watcher-just-started true)
    (println "VS" shader-filename)
    (watcher/watcher
      [shader-filename]
      (watcher/rate 200)
      (watcher/file-filter watcher/ignore-dotfiles)
      (watcher/file-filter (watcher/extensions :vs))
      (watcher/on-change (partial vs-if-match-reload-shader shader-filename)))))
;;(start-cutter :vs "resources/default.vs" :fs "resources/default.fs" :gs "resources/default.gs")
(defn gs-start-watcher
  "create a watch for glsl shaders in the directory and return the global
  future atom for that watcher"
  [shader-filename]
  (let [dir (.getParent (File. ^String shader-filename))
        _   (println "GS dir" dir)]
    (reset! gs-watcher-just-started true)
    (println "GS" shader-filename)
    (watcher/watcher
      [shader-filename]
      (watcher/rate 200)
      (watcher/file-filter watcher/ignore-dotfiles)
      (watcher/file-filter (watcher/extensions :gs))
      (watcher/on-change (partial gs-if-match-reload-shader shader-filename)))))


(defn stop-watcher
  "given a watcher-future f, put a stop to it"
  [f]
  (when-not (or (future-done? f) (future-cancelled? f))
    (if (not (future-cancel f))
      (println "ERROR: unable to stop-watcher!"))))
