(ns #^{:author "Mikael Reponen"
       :doc " General functions"}
  cutter.general
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [watchtower.core :as watcher]
            [clojure.java.io :as io]
            clojure.string))

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
