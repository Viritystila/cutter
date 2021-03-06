(ns #^{:author "Mikael Reponen"}
  cutter.cutter_helper
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            ;[watchtower.core :as watcher]
            ;[clojure.java.io :as io]
                                        ;[while-let.core :as while-let]
            [clojure.math.numeric-tower :as math]
            [cutter.cutter :refer :all]
            [cutter.texturearray :refer :all]
            [cutter.camera :refer :all]
            [cutter.video :refer :all]
            [cutter.opencv :refer :all]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan sliding-buffer dropping-buffer close! thread
                     alts! alts!! timeout]]
            ;clojure.string
            )
           )
;;;;;;;;
;;v4l2;;
;;;;;;;;
(defn openV4L2output [device]
  (let [h (:height @the-window-state)
          w                (:width @the-window-state)
          in_fd            (org.bytedeco.javacpp.v4l2/v4l2_open device 02)
          cap              (new org.bytedeco.javacpp.v4l2$v4l2_capability)
          flag             (org.bytedeco.javacpp.v4l2/v4l2_ioctl in_fd (long org.bytedeco.javacpp.v4l2/VIDIOC_QUERYCAP) cap)
          _                (println "VIDIOC_QUERYCAP: " flag)
          v4l2_format      (new org.bytedeco.javacpp.v4l2$v4l2_format)
          _               (.type v4l2_format (long org.bytedeco.javacpp.v4l2/V4L2_BUF_TYPE_VIDEO_OUTPUT))
          v4l2_pix_format (new org.bytedeco.javacpp.v4l2$v4l2_pix_format)
          _               (.pixelformat v4l2_pix_format (long org.bytedeco.javacpp.v4l2/V4L2_PIX_FMT_RGB24))
          _               (.width v4l2_pix_format w)
          _               (.height v4l2_pix_format h)
          minsize         (* 3 (.width v4l2_pix_format))
          _               (if (< (.bytesperline v4l2_pix_format) minsize) (.bytesperline v4l2_pix_format minsize))
          minsize         (* (.height v4l2_pix_format) (.bytesperline v4l2_pix_format))
          _               (if (< (.sizeimage v4l2_pix_format) minsize) (.sizeimage v4l2_pix_format minsize))
          _               (.fmt_pix v4l2_format v4l2_pix_format)
          flag            (org.bytedeco.javacpp.v4l2/v4l2_ioctl in_fd (long org.bytedeco.javacpp.v4l2/VIDIOC_S_FMT) v4l2_format)
          _               (println "VIDIOC_S_FMT: " flag)
          bff             (new org.bytedeco.javacpp.BytePointer minsize)]
          (reset! (:deviceName @the-window-state) device)
          (reset! (:deviceId @the-window-state) in_fd)
          (reset! (:minsize @the-window-state) minsize)
          (reset! (:bff @the-window-state) bff)
          (reset! (:isInitialized @the-window-state) true)))

(defn closeV4L2output [] (org.bytedeco.javacpp.v4l2/v4l2_close @(:deviceId @the-window-state))
                              (reset! (:isInitialized @the-window-state) false))

(defn toggle-recording [device]
  (let [  save    (:save-frames @the-window-state)]
    (if (and (not (nil? device))  (= false @save))
      (do
        (openV4L2output device)
        (println "Start recording")
        (reset! (:save-frames @the-window-state) true ))
      (do (println "Stop recording")
          (reset! (:save-frames @the-window-state) false )
          (closeV4L2output)
          (Thread/sleep 100)))))

;;;;;;;;;;;;;;;
;;Data array;;;
;;;;;;;;;;;;;;;
(defn set-dataArray-item [arraykey idx val]
    (let [{:keys [maxDataArrays maxDataArraysLength i-dataArrays]} @cutter.cutter/the-window-state
          haskey        (contains? i-dataArrays arraykey)
          idx           (mod idx (- maxDataArraysLength 1))
          dataArray     (arraykey i-dataArrays)
          isarray       (vector? val)
          data          (if haskey (:datavec dataArray) nil )
          data          (if haskey
                            (if isarray  (apply assoc data (interleave (range idx (+ idx (count val ))) val ))
                              (assoc data idx val)) nil)
                              ;_ (println data)
          dataArray     (if haskey (assoc dataArray :datavec data))
          i-dataArrays  (if haskey (assoc i-dataArrays arraykey dataArray))]
      (if haskey (swap! the-window-state assoc :i-dataArrays i-dataArrays))
        nil))


(defn get-dataArray-item [arraykey idx]
    (let [{:keys [maxDataArrays maxDataArraysLength i-dataArrays]} @cutter.cutter/the-window-state
          haskey        (contains? i-dataArrays arraykey)
          idx           (mod idx (- maxDataArraysLength 1))
          dataArray     (arraykey i-dataArrays)
          data          (if haskey (:datavec dataArray) nil)
          val           (if haskey (nth data idx) nil)]
          val))
;;;;;;;;;;
;;Floats;;
;;;;;;;;;;
(defn set-float [floatKey val]
    (let [{:keys [i-floats]} @cutter.cutter/the-window-state
          haskey        (contains? i-floats floatKey)
          floatVal      (floatKey i-floats)
          floatVal      (if haskey (assoc floatVal :data  val) nil)
          i-floats      (if haskey (assoc i-floats floatKey floatVal))]
      (if haskey (swap! the-window-state assoc :i-floats i-floats))
        nil))

(defn get-float [floatKey ]
    (let [{:keys [i-floats]} @cutter.cutter/the-window-state
          haskey        (contains? i-floats floatKey)
          floatVal      (floatKey i-floats)
          val           (if haskey (:data floatVal) nil)]
        val))
;;;;;;;;
;;Text;;
;;;;;;;;
(defn write-text
  " (cutter.cutter_helper/write-text \"cutter\" 0 220 10 100 0.2 0.4 20 10 true) "
  [text x y size r g b thickness linetype clear]
  (let  [i-textures          (:i-textures @the-window-state)
         texture             (:iText i-textures)
         width               (:width texture)
         height              (:height texture)
         mat                 (:mat texture)
         queue               (:queue texture)
         pbo                 (:pbo texture)
         gl_buffer           (:gl_buffer texture)
         _                   (if clear (.setTo mat  (new org.opencv.core.Scalar 0 0 0 0)))
         corner              (new org.opencv.core.Point x y)
         style               (org.opencv.imgproc.Imgproc/FONT_HERSHEY_TRIPLEX)
         colScal             (new org.opencv.core.Scalar (float r) (float g) (float b))
         _                   (org.opencv.imgproc.Imgproc/putText mat text corner style size colScal thickness linetype)
         texture             (assoc texture :mat mat)
         i-textures          (assoc i-textures :iText texture)]
    (async/offer! queue (conj (matInfo mat) pbo))
    (swap! the-window-state assoc :i-textures i-textures))
  nil)


(defn load-images-to-queue [filenames queue pbo]
  (let [ filenames  (filter (apply every-pred [string? #(.exists (clojure.java.io/as-file %))]) filenames)
        no-files    (count filenames)
        mat-info    (atom [])]
    (doseq [x filenames]
      (if (and (string? x) (.exists (clojure.java.io/as-file x)))
        (let [mi  (conj  (matInfo (cutter.opencv/oc_load_image x)) pbo)]
          (clojure.core.async/>!! queue  mi)
          (swap! mat-info conj mi )  ))) @mat-info ))


(defn map-to-request-format [m-i a]
  (let [ws     (mapv (fn [x] (nth x 5)) m-i )
        hs     (mapv (fn [x] (nth x 4)) m-i )
        cs     (mapv (fn [x] (nth x 6)) m-i )
        as     (mapv (fn [x] a) m-i )]
    (mapv (fn [w h c a] [w h c a]) ws hs cs as )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Add Texture from file to texture-array;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def fs ["/home/mikael/Pictures/test1.png" "/home/mikael/Pictures/test2.png"
         "/home/mikael/Pictures/test3.png" "/home/mikael/Pictures/test4.png"
         "/home/mikael/Pictures/test5.png" "/home/mikael/Pictures/test6.png"
         "/home/mikael/Pictures/test7.png" "/home/mikael/Pictures/test8.png"])
;; (add-to-buffer fs "a" :iChannel1)
(defn add-to-buffer [filenames buffername destination]
  (let [texture-arrays           (:texture-arrays @cutter.cutter/the-window-state)
        buffername-key           (keyword buffername)
        texture-array            (buffername-key texture-arrays)
        running?                 false
        idx                      buffername
        maximum-buffer-length    (:maximum-buffer-length @cutter.cutter/the-window-state)
        bufdestination           (destination texture-array)
        bufdestination           (if (nil? bufdestination) :iChannelNull bufdestination)
        running?                 (:running texture-array)
        running?                 (if (nil? running?) false running?)
        mode                     (:mode texture-array)
        mode                     (if (nil? mode) :fw mode)
        fps                      (:fps texture-array)
        fps                      (if (nil? fps) 30 fps)
        start-index              (:start-index texture-array)
        start-index              (if (nil? start-index) 0 start-index)
        stop-index               (:stop-index texture-array)
        stop-index               (if (nil? stop-index) maximum-buffer-length stop-index)
        source                   (:source texture-array)
        source                   (if (nil? source) [] source)
        old-pbo-ids              (:pbo_ids texture-array)
        old-pbo-ids              (if (nil? old-pbo-ids) [] old-pbo-ids)
        i-textures               (:i-textures @cutter.cutter/the-window-state)
        texture                  (destination i-textures)
        queue                    (:queue texture)
        mlt                      (:mult texture)
        req                      (:req texture)
        pbo                      (:pbo texture)
        image-buffer             (atom [])
        pbo_ids                  (atom [])
        rejected-buffers         (atom [])
        rejected-pbos            (atom [])
        t-a-index                (atom 0)
        filenames                (filter (apply every-pred [string? #(.exists (clojure.java.io/as-file %))]) filenames)
        no_files                 (count filenames)
        out                      (clojure.core.async/chan (async/buffer no_files))
        _                        (async/poll! req)
        mat_info                 (cutter.cutter_helper/load-images-to-queue filenames out pbo)
        req-input-dat            (cutter.cutter_helper/map-to-request-format mat_info 1)
        ;_ (println "asasdas"  req-input-dat)
        req-delete               (clojure.core.async/>!! (:request-queue @the-window-state) {:type :del :destination destination :buf-name buffername-key :data old-pbo-ids})
        req-delete-reply         (clojure.core.async/<!! req)
        req-input                (clojure.core.async/>!! (:request-queue @the-window-state) {:type :new :destination destination :buf-name buffername-key :data req-input-dat})
        orig_source_dat          (clojure.core.async/<!! req)
        is_good_dat              (vector? orig_source_dat)
        req-buffers              (if is_good_dat (first orig_source_dat) nil)
        req-pbo_ids              (if is_good_dat (last orig_source_dat) nil)
        ]
    (println "Adding images" filenames)
    (do  (while-let.core/while-let [image     (clojure.core.async/poll! out)]
           (let [dest-buffer         (nth req-buffers @t-a-index)
                 pbo_id              (nth req-pbo_ids @t-a-index)
                 rows                (nth image 1)
                 step                (nth image 2)
                 h                   (nth image 4)
                 w                   (nth image 5)
                 ib                  (nth image 6)
                 buffer_i            (nth image 0)
                 mat                 (nth image 7)
                 image               (assoc image 9 pbo_id)
                 copybuf             (oc-mat-to-bytebuffer mat)
                 buffer-capacity     (.capacity copybuf)
                 dest-capacity       (.capacity dest-buffer)
                 _                   (if (= buffer-capacity dest-capacity)
                                       (do (let [image           (assoc image 9 pbo_id)
                                                 _               (swap! pbo_ids conj pbo_id)
                                                 _               (swap! image-buffer conj (assoc image 0  (.flip (.put dest-buffer copybuf ))))   ]) )
                                       (do (swap! rejected-pbos conj pbo_id)
                                           (swap! rejected-buffers conj dest-buffer)))
                 ]
                                        ;(println "sdas" @image-buffer @pbo_ids)
             (swap! t-a-index inc)))
         (swap! cutter.cutter/the-window-state assoc :texture-arrays
                (assoc texture-arrays buffername-key (assoc texture-array :idx buffername
                                                            :destination bufdestination
                                                            :source @image-buffer
                                                            :running running?
                                                            :fps 30
                                                            :index 0
                                                            :mode :fw
                                                            :loop true
                                                            :start-index start-index
                                                            :stop-index maximum-buffer-length
                                                            :pbo_ids @pbo_ids)))
         (clojure.core.async/>!! (:request-queue @the-window-state) {:type :del :destination destination :buf-name buffername-key :data @rejected-pbos})
         (println "Finished adding images"))))

(defn add-from-dir [dir buffername destination]
  (let [dir    (clojure.java.io/file dir)
        files   (file-seq dir)
        files   (filter #(.isFile %) files)
        files   (mapv str files)]
    (println files)
    (add-to-buffer files buffername destination)
    ))
