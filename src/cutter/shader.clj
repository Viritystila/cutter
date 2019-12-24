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

;;Fragment shader watching
;; The reload-shader atom communicates across the gl & watcher threads
(defonce reload-shader (atom false))
(defonce reload-shader-str (atom ""))
;; Atom for the directory watcher future
(defonce watcher-future (atom (future (fn [] nil))))
;; Flag to help avoid reloading shader right after loading it for the
;; first time.
(defonce watcher-just-started (atom true))

;Vertex shader watching
(defonce vs-reload-shader (atom false))
(defonce vs-reload-shader-str (atom ""))
;; Atom for the directory watcher future
(defonce vs-watcher-future (atom (future (fn [] nil))))
;; Flag to help avoid reloading shader right after loading it for the
;; first time.
(defonce vs-watcher-just-started (atom true))

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

(defn slurp-fs
  [locals filename]
  (let [{:keys [tex-types shader-ver]} @locals
        ;;file-str (slurp filename)
        file-str (str shader-ver
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
;; ======================================================================

(def vs-shader
  (str "#version 460 core\n"
       "vec3 iResolution;\n"
       "float iGlobalTime;\n"
       "float iRandom;\n"
       "layout(location = 0) in vec4 vertexPosition_modelspace;"
       "\n"
       "layout(location = 1) in vec3 colors_modelspace;"
       "\n"
       "void main(void) {\n"
       "    gl_Position = vertexPosition_modelspace;\n"
       "}\n"))
;* vec4(0.5*iRandom, 0.1, 0.0, 1.0)
(defn- load-shader
  [^String shader-str ^Integer shader-type]
  (let [shader-id         (GL20/glCreateShader shader-type)
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
  (let [[ok? vs-id] (load-shader vs-shader GL20/GL_VERTEX_SHADER)
        _           (assert (== ok? GL11/GL_TRUE)) ;; something is really wrong if our vs is bad
        _           (if (nil? (:shader-filename @locals))
                      (println "Loading shader from string")
                      (println "Loading shader from file:" (:shader-filename @locals)))
        [ok? fs-id] (load-shader (:shader-str @locals) GL20/GL_FRAGMENT_SHADER)]
    (if (== ok? GL11/GL_TRUE)
      (let [pgm-id                (GL20/glCreateProgram)
            _                     (except-gl-errors "@ let init-shaders glCreateProgram")
            _                     (GL20/glAttachShader pgm-id vs-id)
            _                     (except-gl-errors "@ let init-shaders glAttachShader VS")
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
               :vs-id vs-id
               :fs-id fs-id
               :pgm-id pgm-id
               :i-uniforms i-uniforms
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
            (except-gl-errors "@ try-reload-shader"))
          (let [_ (println "Reloading shader:" shader-filename)
                i-uniforms                     (generate-uniform-locs locals new-pgm-id ) ]
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
                   :i-uniforms i-uniforms
                   :shader-str fs-shader)))))))
;

;; watch the shader-str-atom to reload on a change
(defn watch-shader-str-atom
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

(defn start-watcher
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

(defn stop-watcher
  "given a watcher-future f, put a stop to it"
  [f]
  (when-not (or (future-done? f) (future-cancelled? f))
    (if (not (future-cancel f))
      (println "ERROR: unable to stop-watcher!"))))
