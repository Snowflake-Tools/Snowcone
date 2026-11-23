(ns snowcone.shell
  (:require
   [babashka.process :as p]
   [clojure.tools.logging :as log]
   [clojure.string :as s]))

(defn split-to-list
  "Splits a passed command into a list of strings for input to p/shell"
  [^String cmd]
  (remove s/blank? (s/split cmd #" ")))

(defn run-command
  "Runs a command, raises an exception if an error is encountered."
  ([cmd] (run-command cmd {}))
  ([cmd options]
   (log/debug (str "CMD: Running command '" cmd "' with options: ") options)
   (apply p/shell
          (merge {:out :string :err :string} options)
          (if (:raw-args options)
            cmd ;; pass a vector
            (split-to-list cmd)))))

;; ================================================================================
;; Testing
(comment
  (s/split "sadf asfd" #" ")

  (run-command "git pull")

  (run-command "git rev-parse --is-inside-work-tree" {})

  (split-to-list "git   pull"))
