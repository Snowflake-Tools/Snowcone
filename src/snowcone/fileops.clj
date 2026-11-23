(ns snowcone.fileops
  (:require
   [clojure.string :as s]
   [cheshire.core :as json]
   [selmer.parser :as selmer]
   [selmer.util :refer [without-escaping]]
   [clojure.tools.logging :as log]
   [clj-yaml.core :as yaml]))

(defn replace-strings
  "Replaces every instance of each original-replacement pair in the target file."
  [text replacements]
  (reduce
   (fn [content [original replacement]]
     (s/replace content original replacement))
   text
   replacements))

(defn replace-mustaches
  [text replacement-key-pairs]
  (str (without-escaping
        (selmer/render text replacement-key-pairs))))

(defn replace-strings-in-file
  "Replaces every instance of each original-replacement pair in the target file."
  [file-path replacements]
  (log/debug (str "Making replacements in " file-path))
  (spit file-path
        (-> (slurp file-path) (replace-strings replacements))))

(defn to-yaml
  ([data]
   (to-yaml data {}))
  ([data options]
   (let [default-opts {:flow-style :auto :indent 2}]
     (yaml/generate-string data :dumper-options (merge default-opts options)))))

(defn to-json
  ([data]
   (to-json data {}))
  ([data options]
   (let [default-opts {:pretty true}]
     (json/generate-string data (merge default-opts options)))))

(comment
  (replace-strings-in-file "test.txt" '(("one" "three")))
  (to-yaml {:hello "World"}))
