(ns #^{:author "Mikael Reponen"}
  cutter.texturearray
  (:require ;[clojure.tools.namespace.repl :refer [refresh]]
            ;[watchtower.core :as watcher]
            ;[clojure.java.io :as io]
            [while-let.core :as while-let]
            [cutter.cutter :refer :all]
            [cutter.opencv :refer :all]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan sliding-buffer dropping-buffer close! thread
                     alts! alts!! timeout]]
            ;clojure.string
            )
            )

;Texture buffers
(defn set-buffer-texture [buffername destination-texture-key]
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        source                   (:source texture-array)
        running?                 (:running texture-array)
        mode                     (:mode texture-array)
        mode                     (if (nil? mode) :fw mode)
        loop?                    (:loop texture-array)
        loop?                    (if (nil? loop?) true loop?)
        fps                      (:fps texture-array)
        idx                      (:idx texture-array)
        start-index              (:start-index texture-array)
        stop-index               (:stop-index texture-array)
        i-textures               (:i-textures @cutter.cutter/the-window-state)
        texture                  (destination-texture-key i-textures)
        queue                    (:queue texture)
        buffer-length            (count source)
        texture-array            (assoc texture-array
                                    :idx idx,
                                    :destination destination-texture-key,
                                    :source source,
                                    :running running?,
                                    :mode mode,
                                    :loop loop?
                                    :fps fps,
                                    :index (mod (max 0 start-index) (min stop-index buffer-length))
                                    :start-index start-index,
                                    :stop-index stop-index)
        texture-arrays            (assoc texture-arrays  buffername-key texture-array)
        startTime                 (atom (System/nanoTime))
        internal-index             (atom 0)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays)
        (if (and (not running?) (= :yes (:active @cutter.cutter/the-window-state)))
        (do
          (let [texture-array (assoc texture-array :running true :fps fps)]
          (swap! cutter.cutter/the-window-state assoc :texture-arrays
            (assoc texture-arrays
              buffername-key texture-array))
                (async/thread
                  (while-let/while-let [running (:running (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))]
                  (let [mode                (:mode (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))
                        loop?               (:loop (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))
                        fps                 (:fps (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))
                        index               (:index (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))
                        buffer-destination  (:destination (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))
                        queue               (:queue ( buffer-destination (:i-textures @cutter.cutter/the-window-state)))
                        start-index         (:start-index (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))
                        stop-index          (:stop-index (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))
                        source              (:source (buffername-key (:texture-arrays @cutter.cutter/the-window-state)))
                        buffer-length       (count source)
                        texture-arrays      (:texture-arrays @cutter.cutter/the-window-state)
                        cur-index           (case mode
                                                  :fw (if loop? (mod (max index start-index) (min stop-index buffer-length))
                                                        (min index (min stop-index (- buffer-length 1))))
                                                  :bw (if loop? (mod (min index stop-index) (min stop-index buffer-length))
                                                    (max index (min start-index buffer-length))))]
                      (reset! startTime (System/nanoTime))
                      (reset! internal-index cur-index)
                      (async/offer!
                        queue
                        (nth source  (mod cur-index (min stop-index buffer-length)) ))
                      (case mode
                        :fw  (do (swap! internal-index inc))
                        :bw  (do (swap! internal-index dec))
                        :idx (reset! index (:index (buffername-key texture-arrays)) ))
                      (swap! cutter.cutter/the-window-state assoc :texture-arrays
                        (assoc texture-arrays buffername-key (assoc texture-array
                                                              :fps fps
                                                              :mode mode
                                                              :loop loop?
                                                              :index @internal-index
                                                              :destination buffer-destination
                                                              :queue queue
                                                              :start-index start-index
                                                              :stop-index stop-index
                                                              :source source)))
                      (Thread/sleep (cutter.general/sleepTime @startTime (System/nanoTime) fps))))))))))

(defn copy-buffer [src tgt]
  (let [texture-arrays            (:texture-arrays @cutter.cutter/the-window-state)
        source-key                (keyword src)
        target-key                (keyword tgt)
        source-array              (source-key texture-arrays)
        target-array              (assoc source-array :running false :idx tgt)
        texture-arrays            (assoc texture-arrays target-key target-array)]
        (println (keys texture-arrays))
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays)) nil)

(defn stop-buffer [buffername]
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        texture-array            (assoc texture-array :running false)
        texture-arrays           (assoc texture-arrays buffername-key texture-array)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays)) nil)

(defn set-buffer-fps [buffername val]
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        texture-array            (assoc texture-array :fps val)
        texture-arrays           (assoc texture-arrays buffername-key texture-array)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays) nil))

(defn set-buffer-fw [buffername]
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        texture-array            (assoc texture-array :mode :fw)
        texture-arrays           (assoc texture-arrays buffername-key texture-array)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays) nil))

(defn set-buffer-bw [buffername]
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        texture-array            (assoc texture-array :mode :bw)
        texture-arrays           (assoc texture-arrays buffername-key texture-array)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays) nil))

(defn set-buffer-loop [buffername loop?]
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        texture-array            (assoc texture-array :loop loop?)
        texture-arrays           (assoc texture-arrays buffername-key texture-array)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays) nil))

(defn set-buffer-paused [buffername]
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        texture-array            (assoc texture-array :mode :idx)
        texture-arrays           (assoc texture-arrays buffername-key texture-array)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays) nil))

(defn set-buffer-index [buffername val]
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        source                   (:source texture-array)
        buffer-length            (count source)
        texture-array            (assoc texture-array :index (mod (int val) buffer-length))
        texture-arrays           (assoc texture-arrays buffername-key texture-array)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays) nil))

(defn set-buffer-limits [buffername start-index stop-index]
  (let [maximum-buffer-length    (:maximum-buffer-length @cutter.cutter/the-window-state)
        texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        texture-array            (assoc texture-array :start-index (max 0 (int start-index)))
        texture-array            (assoc texture-array :stop-index (min maximum-buffer-length (int stop-index)))
        texture-arrays           (assoc texture-arrays buffername-key texture-array)]
        (swap! cutter.cutter/the-window-state assoc :texture-arrays texture-arrays) nil))

(defn replace-in-buffer [filename buffername index]
  (let [  texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
          buffername-key           (keyword buffername)
          texture-array            (buffername-key texture-arrays)
          running?                 false
          idx                      buffername
          maximum-buffer-length    (:maximum-buffer-length @cutter.cutter/the-window-state)
          bufdestination           (:destination texture-array)
          bufdestination           (if (nil? bufdestination) :iChannelNull bufdestination)
          running?                 (:running texture-array)
          running?                 (if (nil? running?) false running?)
          mode                     (:mode texture-array)
          mode                     (if (nil? mode) :fw mode)
          loop?                    (:loop texture-array)
          loop?                    (if (nil? loop?) true loop?)
          fps                      (:fps texture-array)
          fps                      (if (nil? fps) 30 fps)
          start-index              (:start-index texture-array)
          start-index              (if (nil? start-index) 0 start-index)
          stop-index               (:stop-index texture-array)
          stop-index               (if (nil? stop-index) maximum-buffer-length stop-index)
          source                   (:source texture-array)
          source                   (if (nil? source) [] source)
          mat                      (cutter.opencv/oc_load_image filename)
          source                   (if (< index (count source)) (assoc source index (matInfo mat)) source )
          newcount                 (count source)]
      (swap! cutter.cutter/the-window-state assoc :texture-arrays
        (assoc texture-arrays buffername-key (assoc texture-array :idx buffername
                                                                  :destination bufdestination
                                                                  :source source
                                                                  :running running?
                                                                  :fps fps
                                                                  :index 0
                                                                  :mode mode
                                                                  :loop  loop?
                                                                  :start-index start-index
                                                                  :stop-index (min maximum-buffer-length newcount))))) nil)

(defn stop-all-buffers []
  (mapv (fn [x] (stop-buffer (str (name x)))) (vec (keys (:texture-arrays @cutter.cutter/the-window-state)))))
