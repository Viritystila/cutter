  ;LWJGL3 from from https://github.com/rogerallen/hello_lwjgl/blob/master/project.clj
  (require 'leiningen.core.eval)

  ;; per-os jvm-opts code cribbed from Overtone
  (def JVM-OPTS
    {:any
     ["-Xms512m" "-Xmx1g"                 ; Minimum and maximum sizes of the heap
      "-XX:MaxGCPauseMillis=20"           ; Specify a target of 20ms for max gc pauses
      "-XX:MaxNewSize=257m"               ; Specify the max and min size of the new
      "-XX:NewSize=256m"                  ;  generation to be small
      "-XX:+UseTLAB"                      ; Uses thread-local object allocation blocks. This
                                             ;  improves concurrency by reducing contention on
                                             ;  the shared heap lock.
      "-XX:MaxTenuringThreshold=0"]       ; Makes the full NewSize available to every NewGC
                                             ;  cycle, and reduces the pause time by not
                                             ;  evaluating tenured objects. Technically, this
                                             ;  setting promotes all live objects to the older
                                             ;  generation, rather than copying them.
     :disabled
     ["-XX:ConcGCThreads=2"               ; Use 2 threads with concurrent gc collections
      "-XX:TieredCompilation"             ; JVM7 - combine both client and server compilation
                                             ;  strategies
      "-XX:CompileThreshold=1"            ; JIT each function after one execution
      "-XX:+PrintGC"                      ; Print GC info to stdout
      "-XX:+PrintGCDetails"               ;  - with details
      "-XX:+PrintGCTimeStamps"]
     :common   ["--add-opens=java.base/java.nio=ALL-UNNAMED"]
     :macosx   ["-XstartOnFirstThread" "-Djava.awt.headless=true"]
     :linux    []
     :windows  []})

  (defn jvm-opts
    "Return a complete vector of jvm-opts for the current os."
    [] (let [os (leiningen.core.eval/get-os)]
         (vec (set (concat (get JVM-OPTS :any)
                    (get JVM-OPTS :common)
                    (get JVM-OPTS os))))))
  (def LWJGL_NS "org.lwjgl")
  (def LWJGL_VERSION "3.3.3")
  ;; Edit this to add/remove packages.
  (def LWJGL_MODULES ["lwjgl"
                      "lwjgl-glfw"
                      "lwjgl-opengl"
                      "lwjgl-assimp"
  ])

  (def LWJGL_PLATFORMS ["linux" "macos" "windows"])
  ;; These packages don't have any associated native ones.
  (def no-natives? #{"lwjgl-egl" "lwjgl-jawt" "lwjgl-odbc"
  "lwjgl-opencl" "lwjgl-vulkan"})
  (defn lwjgl-deps-with-natives []
    (apply concat
           (for [m LWJGL_MODULES]
             (let [prefix [(symbol LWJGL_NS m) LWJGL_VERSION]]
               (into [prefix]
                     (if (no-natives? m)
                       []
                       (for [p LWJGL_PLATFORMS]
                         (into prefix [:classifier (str "natives-" p) :native-prefix ""]))))))))

  (def all-dependencies
    (into ;; Add your non-LWJGL dependencies here
     '[           [org.clojure/clojure "1.10.1"]
                  [org.clojure/tools.namespace "0.2.11"]
                  [org.clojure/core.async "0.4.490"]
                  [while-let "0.2.0"]
                  [org.clojure/math.numeric-tower "0.0.4"]
                  [watchtower          "0.1.1"]
                  [org.viritystila/opencv "4.8.0-linux"]
                  [org.viritystila/opencv-native "4.8.0-linux"]
                  [org.bytedeco/javacpp "1.5.9"]
                  [org.viritystila/v4l2 "1.0-linux"]
                  [org.viritystila/v4l2-platform "1.0-linux"]
                  [org.viritystila/v4l2-native "1.0-linux"]
                  ;[overtone/osc-clj "0.7.1"]
                  ;;[org.freedesktop.gstreamer/gst1-java-core "1.4.0"]
                  ;;[net.java.dev.jna/jna-platform "5.12.1"]
                  [overtone-javacpp            "0.11.0"]
                  ;;[overtone "0.10.6"]
                  ]
  (lwjgl-deps-with-natives)))


  (defproject org.viritystila/cutter "0.0.1-SNAPSHOT"
    :description "A evolution of the Shadertone, a clojure library designed to mix musical synthesis via Overtone and dynamic visuals a la www.shadertoy.com"
    :url "https://github.com/Viritystila/cutter"
    :license {:name "MIT License"
             :url "https://github.com/Viritystila/cutter/blob/master/LICENSE"}
    :injections [(clojure.lang.RT/loadLibrary org.opencv.core.Core/NATIVE_LIBRARY_NAME)]
    :repositories [["Viritystila OpenCV" "https://github.com/Viritystila/OpenCV/raw/master"]
                  ["Viritystila v4l2javacpp" "https://github.com/Viritystila/v4l2javacpp/raw/master"]]
    :dependencies ~all-dependencies
    ;:java-source-paths ["src/java"]
    :native-path "native"
    :resource-paths ["resources"]
    :main ^{:skip-aot true} cutter.core
    :jvm-opts ^:replace ~(jvm-opts)
    )
