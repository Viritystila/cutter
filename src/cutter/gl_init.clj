(ns #^{:author "Mikael Reponen"}
  cutter.gl_init
  (:require
   [cutter.general :refer :all]
   [cutter.opencv :refer :all]
   [clojure.core.async
    :as async
    :refer [>! <! >!! <!! go go-loop chan sliding-buffer dropping-buffer close! thread
            alts! alts!! timeout]] )
  (:import
   [org.opencv.core Mat Core CvType]
   [org.lwjgl.assimp Assimp]
   [java.nio IntBuffer ByteBuffer FloatBuffer ByteOrder]
   [org.lwjgl BufferUtils]
   [org.lwjgl.opengl GL GL11 GL12 GL13 GL15 GL20 GL21 GL30 GL40 GL44 GL45]
   ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Single texture OpenGL initialize;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn init-texture
  [width height target tex-id queue out1 mlt req pbo]
  (let [target              target
        tex-id              tex-id
        mat                 (org.opencv.core.Mat/zeros height width org.opencv.core.CvType/CV_8UC3)
        internal-format     (oc-tex-internal-format mat)
        format              (oc-tex-format mat)
        buffer              (oc-mat-to-bytebuffer mat)
        channels            (.channels mat)
        queue               queue
        mlt                 mlt
        out1                out1
        pbo                 pbo
        mat_step            (.step1 mat)
        mat_rows            (.rows mat)
        mat_size            (* mat_step mat_rows)
        gl_buffer           1
        flags               (bit-or GL30/GL_MAP_WRITE_BIT GL45/GL_MAP_PERSISTENT_BIT GL44/GL_MAP_COHERENT_BIT )
        _                   (GL15/glBindBuffer GL21/GL_PIXEL_UNPACK_BUFFER pbo)
        _                   (GL44/glBufferStorage GL21/GL_PIXEL_UNPACK_BUFFER (long mat_size) flags)
        gl_buffer           (GL44/glMapBufferRange GL21/GL_PIXEL_UNPACK_BUFFER 0 mat_size flags)
        mat                 (new org.opencv.core.Mat height width  org.opencv.core.CvType/CV_8UC3 gl_buffer 0)
        _                   (GL15/glBindBuffer GL21/GL_PIXEL_UNPACK_BUFFER 0)
        texture             { :tex-id           tex-id,
                             :target           target,
                             :height           height,
                             :width            width
                             :mat              mat,
                             :buffer           buffer,
                             :internal-format  internal-format,
                             :format           format
                             :channels         channels,
                             :init-opengl      true
                             :queue            queue
                             :mult             mlt
                             :out1             out1
                             :req              req
                             :pbo              pbo
                             :gl_buffer        gl_buffer}]
    (GL11/glBindTexture target tex-id)
    (GL11/glTexParameteri target GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
    (GL11/glTexParameteri target GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
    (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_S GL11/GL_REPEAT)
    (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_T GL11/GL_REPEAT)
    (GL11/glBindTexture target 0)
    texture))

(defn initialize-texture [locals uniform-key width height]
  (let [{:keys [i-textures]} @locals
        target  (GL11/GL_TEXTURE_2D)
        tex-id  (GL11/glGenTextures)
        queue   (async/chan (async/buffer 1))
        out1    (async/chan (async/buffer 1))
        mlt     (clojure.core.async/mult queue)
        req     (async/chan (async/buffer 1))
        _       (clojure.core.async/tap mlt out1)
        pbo     (GL15/glGenBuffers)
        i-textures (assoc i-textures uniform-key (init-texture width height
                                                               target
                                                               tex-id
                                                               queue
                                                               out1
                                                               mlt
                                                               req
                                                               pbo))]
    i-textures))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Mesh loading;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn process-indices [aimesh]
  (let [num-faces        (.mNumFaces aimesh)
        ai-faces         (.mFaces aimesh)
        b                (atom [])]
    ;(println "num faces " num-faces)
    (doseq [i (range num-faces)]
      (let [ai-face (.get ai-faces i)
            buffer  (.mIndices ai-face)]
        (while (< 0 (.remaining buffer))
          (let [indice (.get buffer)]
            (swap! b conj indice) ))))
    @b))

(defn process-normals [aimesh]
  (let [ai-normals    (.mNormals aimesh)
        ;ai-normal     (.get ai-normals)
        b             (atom [])]
    (while (< 0 (.remaining ai-normals))
      (let [ai-normal   (.get ai-normals)
            x           (.x ai-normal)
            y           (.y ai-normal)
            z           (.z ai-normal)]
        (swap! b conj x y z)
        ))
    @b))

(defn process-text-coords [aimesh]
  (let [text-coords     (.mTextureCoords aimesh 0)
        num-text-coords (if (not (nil? text-coords)) (.remaining text-coords) nil )
        ai-vertices     (.mVertices aimesh)
        num-vertices    (.mNumVertices aimesh)
        b               (atom [])]
    (if (not (nil? num-text-coords))
      (while (< 0 (.remaining text-coords))
        (let [text-coord     (.get text-coords)]
          (swap! b conj (.x text-coord))
          (swap! b conj (- 1 (.y text-coord))))))
     @b))

(defn process-vertices [aimesh]
  (let [ai-vertices       (.mVertices aimesh)
        b                 (atom [])]
    (while (< 0 (.remaining ai-vertices))
      (let [ai-vertex   (.get ai-vertices)
            x           (.x ai-vertex)
            y           (.y ai-vertex)
            z           (.z ai-vertex)]
        (swap! b conj x y z)))
    @b))

;;Placeholder
(defn process-colors [aimesh]
  [1.0 0.0 0.0
   0.0 1.0 0.0
   0.0 0.0 1.0])


(def ai-flags (bit-or  org.lwjgl.assimp.Assimp/aiProcess_JoinIdenticalVertices
                       org.lwjgl.assimp.Assimp/aiProcess_Triangulate
                       org.lwjgl.assimp.Assimp/aiProcess_GenSmoothNormals
                       org.lwjgl.assimp.Assimp/aiProcess_FixInfacingNormals
                       org.lwjgl.assimp.Assimp/aiProcess_PreTransformVertices
                       org.lwjgl.assimp.Assimp/aiProcess_SortByPType
                       org.lwjgl.assimp.Assimp/aiProcess_FindInvalidData
                       org.lwjgl.assimp.Assimp/aiProcess_GenUVCoords
                       org.lwjgl.assimp.Assimp/aiProcess_FindDegenerates
                       org.lwjgl.assimp.Assimp/aiProcess_CalcTangentSpace
                       org.lwjgl.assimp.Assimp/aiProcess_OptimizeMeshes
                       0
               ))

(defn load-mesh [file]
  (let [aiscene        (org.lwjgl.assimp.Assimp/aiImportFile file ai-flags)
        num-materials  (.mNumMaterials aiscene)
        num-meshes     (.mNumMeshes aiscene)
        ai-materials   (.mMaterials aiscene)
        ai-meshes      (.mMeshes aiscene)
        meshes         (mapv (fn [x] (org.lwjgl.assimp.AIMesh/create (long (.get ai-meshes x )))) (range num-meshes))
        md (mapv (fn [x]
                   {:indices          (process-indices x)
                    :normals          (process-normals x)
                    :text-coords      (process-text-coords x)
                    :vertices         (process-vertices x)
                    :colors           (process-colors x)}) meshes)]
    (org.lwjgl.assimp.Assimp/aiReleaseImport aiscene)
    md))



(defn init-mesh [path]
  (let [vertices_and_indices      (first (cutter.gl_init/load-mesh path))
        vertices                  (float-array (:vertices  vertices_and_indices))
        colors                    (float-array (:colors  vertices_and_indices))
        indices                   (int-array   (:indices  vertices_and_indices))
        texture-coords            (float-array (:text-coords  vertices_and_indices))
        normal-coords             (float-array (:normals  vertices_and_indices))
        vertices-buffer           (-> (BufferUtils/createFloatBuffer (count vertices))
                                      (.put vertices)
                                      (.flip))
        vertices-count            (count vertices)
        colors-count              (count colors)
        indices-count             (count indices)
        uvcount                   (count texture-coords)
        normalcount               (count normal-coords)
        vertices-buffer           (-> (BufferUtils/createFloatBuffer vertices-count)
                                      (.put vertices)
                                      (.flip))
        colors-buffer             (-> (BufferUtils/createFloatBuffer colors-count)
                                      (.put colors)
                                      (.flip))
        indices-buffer            (-> (BufferUtils/createIntBuffer indices-count)
                                      (.put indices)
                                      (.flip))
        uv-buffer                 (-> (BufferUtils/createFloatBuffer uvcount)
                                      (.put texture-coords)
                                      (.flip))
        normal-buffer             (-> (BufferUtils/createFloatBuffer normalcount)
                                      (.put normal-coords)
                                      (.flip))
        vao-id                    (GL30/glGenVertexArrays)
        _                         (GL30/glBindVertexArray vao-id)
        vbo-id                    (GL15/glGenBuffers)
        _                         (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER vbo-id)
        _                         (GL15/glBufferData GL15/GL_ARRAY_BUFFER
                                                     ^FloatBuffer vertices-buffer
                                                     GL15/GL_STATIC_DRAW)
        _                         (GL20/glVertexAttribPointer 0 3 GL11/GL_FLOAT false 0 0)
        _                         (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER 0)
        vboc-id                   (GL15/glGenBuffers)
        _                         (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER vboc-id)
        _                         (GL15/glBufferData GL15/GL_ARRAY_BUFFER colors-buffer GL15/GL_STATIC_DRAW)
        _                         (GL20/glVertexAttribPointer 1 3 GL11/GL_FLOAT false 0 0)
        _                         (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER 0)
        vbot-id                   (GL15/glGenBuffers)
        _                         (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER vbot-id)
        _                         (GL15/glBufferData GL15/GL_ARRAY_BUFFER uv-buffer GL15/GL_STATIC_DRAW)
        _                         (GL20/glVertexAttribPointer 2 2 GL11/GL_FLOAT false 0 0)
        _                         (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER 0)
        vbon-id                   (GL15/glGenBuffers)
        _                         (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER vbon-id)
        _                         (GL15/glBufferData GL15/GL_ARRAY_BUFFER normal-buffer GL15/GL_STATIC_DRAW)
        _                         (GL20/glVertexAttribPointer 3 3 GL11/GL_FLOAT false 0 0)
        _                         (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER 0)
        vboi-id             (GL15/glGenBuffers)
        _                   (GL15/glBindBuffer GL15/GL_ELEMENT_ARRAY_BUFFER vboi-id)
        _                   (GL15/glBufferData GL15/GL_ELEMENT_ARRAY_BUFFER indices-buffer GL15/GL_STATIC_DRAW)
        _                   (GL15/glBindBuffer GL15/GL_ELEMENT_ARRAY_BUFFER 0)
        ]
    {:path                path
     :vertices            vertices
     :colors              colors
     :indices             indices
     :uv                  texture-coords
     :normals             normal-coords
     :vertices-buffer     vertices-buffer
     :colors-buffer       colors-buffer
     :indices-buffer      indices-buffer
     :uv-buffer           uv-buffer
     :normal-buffer       normal-buffer
     :verticex-count      vertices-count
     :colors-count        colors-count
     :indices-count       indices-count
     :uv-count            uvcount
     :normal-count        normalcount
     :vao-id              vao-id
     :vbo-id              vbo-id
     :vboc-id             vboc-id
     :vbot-id             vbot-id
     :vbon-id             vbon-id
     :vboi-id             vboi-id}))
