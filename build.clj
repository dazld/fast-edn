(ns build
  (:refer-clojure :exclude [compile])
  (:require
   [clojure.tools.build.api :as b]))

(defn compile
  "Compiles EdnParser.java into classes/, which is checked in so the repository
   works as a git dependency without a prep step. Commit the result with every
   change to the Java source; CI fails when they differ."
  [{:keys [class-dir] :or {class-dir "classes"}}]
  (b/delete {:path class-dir})
  (b/javac {:src-dirs   ["src"]
            :class-dir  class-dir
            :basis      (b/create-basis {:project "deps.edn"})
            :javac-opts ["--release" "11" "-Xlint:-options"]}))
