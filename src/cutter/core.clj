(ns cutter.core)

(defn foo
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))

(ns #^{:author "Mikael Reponen"}
  cutter.core
  (:use [clojure.data])
  (:require
   [cutter.cutter :refer :all]
   [cutter.cutter_helper :refer :all]
   [clojure.tools.namespace.repl :refer [refresh]]))

  (defn main
    []
    (println "start")
    )
