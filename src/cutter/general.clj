(ns #^{:author "Mikael Reponen"
       :doc " General functions"}
  cutter.general
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [watchtower.core :as watcher]
            [clojure.java.io :as io]
            clojure.string)
  (:import (java.nio IntBuffer ByteBuffer FloatBuffer ByteOrder)
           (org.lwjgl BufferUtils)
           (java.io File FileInputStream)))

;
(def not-nil? (complement nil?))

(defn sleepTime
    [startTime endTime fps]
    (let [  dtns    (- endTime startTime)
            dtms    (* dtns 1e-6)
            fpdel   (/ 1 fps)
            fpdelms (* 1e3 fpdel)
            dt      (- fpdelms dtms)
            dtout  (if (< dt 0)  0  dt)]
            dtout))

(defn files-exist
  "Check to see that the filenames actually exist."
  [filenames]
  (let [full-filenames (flatten filenames)]
    (reduce #(and %1 %2) ; kibit keep
            (for [fn full-filenames]
              (if (or (nil? fn)
                      (.exists (File. ^String fn)))
                fn
                (do
                  (println "ERROR:" fn "does not exist.")
                  nil))))))

(defn sane-user-inputs
  [shader-filename shader-str]
  (and (files-exist (flatten [shader-filename]))
       (not (and (nil? shader-filename) (nil? shader-str)))))

(defn limit-max [input max] (vec (subvec input 0 (min max (count input)))))

(defn remove-inexistent [filenames max] (limit-max (vec (remove nil? (map (fn [x] (files-exist [x])) filenames))) max))
