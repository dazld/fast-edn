(ns build
  (:refer-clojure :exclude [compile])
  (:require
   [clojure.tools.build.api :as b]))

(defn compile
  "Compiles EdnParser.java into target/classes. Runs as the :deps/prep-lib step
   when this repository is a git dependency."
  [_]
  (b/javac {:src-dirs   ["src"]
            :class-dir  "target/classes"
            :basis      (b/create-basis {:project "deps.edn"})
            :javac-opts ["--release" "11" "-Xlint:-options"]}))
