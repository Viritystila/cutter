(ns #^{:author "Mikael Reponen"}
  cutter.interface
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [watchtower.core :as watcher]
            [clojure.java.io :as io]
            [while-let.core :as while-let]
            [cutter.cutter :refer :all]
            [cutter.buffer :refer :all]
            [cutter.camera :refer :all]
            [cutter.video :refer :all]
            [cutter.opencv :refer :all]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer sliding-buffer dropping-buffer close! thread
                     alts! alts!! timeout]]
            clojure.string))
;;;;;;;;;;;;
;;;Camera;;;
;;;;;;;;;;;;
(defn cam [device destination-texture-key]
  (cutter.camera/set-live-camera-texture device destination-texture-key))

(defn stop-cam [device]
  (cutter.camera/stop-camera device))

(defn rec [device buffername]
  (cutter.camera/rec-camera device buffername))

(defn set-cam [device property val]
  (set-camera-property device property val))

(defn get-cam [device property]
  (set-camera-property device property))
