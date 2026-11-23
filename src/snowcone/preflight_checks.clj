(ns snowcone.preflight-checks
  (:require
   [babashka.process :as p]
   [snowcone.shell :refer [run-command]]
   [clojure.tools.logging :as log]
   [babashka.fs :as fs]
   [clj-yaml.core :as yaml]
   [clojure.string :as str]))

;; C-c C-k -> cider-load-buffer

;; Resources:
;; https://github.com/babashka/fs

(defn validate-config
  "Given a parsed config data object, ensure required keys are present"
  [config]

  (when-not (contains? config :project)
    (throw (ex-info "Config requires a 'project' section." config)))

  (when-not (contains? config :build)
    (throw (ex-info "Config requires a 'build' section." config)))

  (when-not (contains? config :environments)
    (throw (ex-info "Config requires an 'environments' section." config)))

  ;; Ensure 'config' data passes through if all valid.
  (assoc config :config-validated true))

(defn check-if-git-repository-root
  "Check if the .git folder is present and the user is in a worktree."
  [path]

  ;; Ensure the git folder is present
  (when-not (fs/exists? (fs/path path ".git"))
    (throw (ex-info "Not in a git repository" {})))

  ;; Ensure it's a real git worktree
  (let [result (run-command "git rev-parse --is-inside-work-tree" {:dir path})]
    (when-not (= 0 (:exit result))
      (throw (ex-info "Git worktree not valid." {}))))

  (log/info (str "Operating at path '" path "' which is a valid repository")))

(defn check-if-config-file-is-parsable
  "Runs a simple check to see if the config.yml is parseable and has a project.name value."
  [path]

  (when-not (fs/exists? (fs/path path "config.yml"))
    (throw (ex-info "Config file 'config.yml' does not exist, add one" {})))

  (let [parsed-yml
        (-> (fs/path path "config.yml")
            str
            slurp
            yaml/parse-string)

        project-name (get-in parsed-yml [:project :name])]

    (when-not (string? project-name)
      (throw (ex-info "Config file 'config.yml' requires project.name value." {})))

    (log/info (str "Config file 'config.yml' present, project name: '" project-name "'"))))

;; ================================================================================
;; Testing
(comment
  ;; (check-if-git-repository-root (fs/cwd))
  ;; (check-if-config-file-is-parsable (fs/cwd))

  (fs/exists? (fs/path (fs/cwd) "config.yml"))

  (fs/exists? (fs/path (fs/cwd) "config.yml"))

  (p/shell "ls" "-la")

  (->
   (p/shell {:out :string} "ls" "-la")
   :out
   str/split-lines)

  (fs/find-up "." ".git")
  (fs/directory? "."))
