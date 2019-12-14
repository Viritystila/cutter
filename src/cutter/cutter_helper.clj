(ns #^{:author "Mikael Reponen"}
  cutter.cutter_helper
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [watchtower.core :as watcher]
            [clojure.java.io :as io]
            [while-let.core :as while-let]
            [cutter.cutter :refer :all]
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


(defn list-cameras [] (println (:cameras @the-window-state)))

(defn list-videos [] (println (:videos @the-window-state)))

(defn list-textures [] (println (:textures @the-window-state)))

(defn list-texture-arrays [] (println (:texture-arrays @the-window-state)))

(defn list-camera-devices [] (println (:camera-devices @the-window-state)))

(defn list-video-filenames [] (println (:video-filenames @the-window-state)))

(defn list-texture-filenames [] (println (:texture-filenames @the-window-state)))

(defn list-texture-folders [] (println (:texture-folders @the-window-state)))
