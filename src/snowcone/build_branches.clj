(ns snowcone.build-branches
  (:require
   [snowcone.gitops :as gitops]
   [clojure.tools.logging :as log]
   [snowcone.dates :refer [datestamp]]
   [snowcone.fileops :as fileops]
   [babashka.fs :as fs]
   [clojure.set :as set]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as s]))

;; REPL:
;; C-u C-c C-z to switch to this namespace inside the repl
;; TODO: Rebind lsp-find-definition 

(def python-json-loading-template "
# This file is meant to make it easy to load JSON data to a snowflake object
# from a project git repository. 
import json

def main():
  return json.loads(r\"\"\"
{{ json }}
\"\"\".strip())

# Example loading procedure (in Snowflake)

# CREATE OR REPLACE PROCEDURE my_db.my_schema.LOAD_CONFIG()
#   RETURNS OBJECT
#   LANGUAGE PYTHON
#   RUNTIME_VERSION = '3.12'
#   PACKAGES = ('snowflake-snowpark-python', 'json')
#   IMPORTS = ('@my_db.my_schema.my_repo/branches/main/deployment.py')
#   HANDLER = 'main';
")

(defn config-branches [{:keys [environments]}]
  (->> (vals environments)
       (mapcat (juxt :source_branch :target_branch))
       (set)))

(defn clean-notebook-path [path notebook-file]
  (-> (str (fs/path path notebook-file))
      (s/replace #"^/" "")))

(defn clean-full-notebook-path [dir path notebook-file]
  (-> (str (fs/path dir path notebook-file))))

(defn copy-and-transform-single-notebook
  [notebook-file
   {:keys [id notebooks path] :as notebook-data}
   {:keys [source_branch target_branch replacements] :as target-data}
   {:keys [dir verbose offline] :as config}]
  (let [file-path (clean-notebook-path path notebook-file)
        full-file-path (clean-full-notebook-path dir path notebook-file)]
    (log/info (str "Handling " file-path))

    ;; Restore file to correct location
    (gitops/restore-file file-path source_branch dir offline)
    (log/info (str "Restored notebook to path " file-path))
    ;; If there is an exception this will need to be rolled back

    ;; Rewrite notebook with any replacements
    (fileops/replace-strings-in-file full-file-path replacements)

    ;; Add changes
    (gitops/add-file file-path dir)
    (log/info (str "Finished handling " file-path))))

(defn copy-and-transform-one-notebook-set
  "Given a folder of notebooks, copy and transform all notebooks to the branch."
  [{:keys [id notebooks] :as notebook-data}
   {:keys [source_branch target_branch] :as target-data}
   {:keys [dir verbose offline] :as config}]
  (let [current-branch (gitops/get-current-branch dir)]

    ;; If not already, switch to the correct target branch.
    (when-not (= current-branch target_branch)
      (gitops/switch-branch target_branch dir)
      (log/info (str "Switched to branch '" target_branch "'")))

    (log/info (str "Handling " (count notebooks) " notebooks for " (name id) " in branch '" target_branch "'"))

    (when verbose
      (log/info (str (name id) " notebook data:"))
      (pprint notebook-data))

    (doseq [notebook-file notebooks]
      (copy-and-transform-single-notebook
       notebook-file notebook-data
       target-data config))

    (log/info "Notebook set handled.")))

(defn build-one-branch
  "Given a target branch, assemble all notebooks for that branch."
  [{:keys [id source_branch target_branch] :as target-data}
   {:keys [dir verbose offline notebooks push] :as config}]
  (try
    (let [env-target-slug (s/trim (s/lower-case (name id)))
          ;; tmp-dir (str (fs/path dir (str ".tmp-" env-target-slug)))
          log-prefix (s/upper-case (str env-target-slug "-BUILD: "))]

      (when verbose
        (log/info (str "\nDEBUG: '" (s/upper-case env-target-slug) "' Target Data:"))
        (prn target-data))

      (log/info (str "\n" (s/upper-case env-target-slug) ": Building target"))

      ;; Ensure the latest commits are fetched for the source
      (when-not offline
        (gitops/fetch-branch-from-origin source_branch dir)
        (log/info (str "Fetched source (" source_branch ") branch")))

      ;; Switch to the source branch (should not change the branch)
      (gitops/switch-branch target_branch dir)
      (log/info (str "Switched to branch '" target_branch "'"))

      ;; Reset the build-branch to ensure no changes have slipped in during editing
      (gitops/reset dir)
      (log/info target_branch " branch reset and ready to copy changes from " source_branch)

      (when-not offline
        (when-not (gitops/upstream-branch-exists dir)
          (throw (ex-info "Upstream branch does not exist." target-data)))
        (gitops/fetch-branch-from-origin target_branch dir)
        (log/info (str "Fetched target ("  target_branch ") branch")))

      ;; Untrack all files for a clean start in the target branch
      (when-not (empty? (gitops/list-tracked-files target_branch dir))
        (gitops/untrack-all target_branch dir))

      ;; Create .tmp-build-<slug> directory to copy files to
      ;; -p -> make if already exists
      ;; (run-command (str "mkdir -p " tmp-dir) {:dir dir})
      ;; (log/info "Working in directory" tmp-dir)
      (doseq [[id notebook] notebooks]
        (copy-and-transform-one-notebook-set
         (assoc notebook :id id) target-data config))

      ;; Find all notebook files and copy them to the temporary directory
      ;; ^^ Nope, trying to use

      ;; Commit
      (let [commit-msg (s/join " " [(str "BUILD[" (s/upper-case env-target-slug) "]")
                                    (str "at " (datestamp))
                                    (str "from branch '" source_branch "'")
                                    (str "at commit '" (:current-commit config) "'")])
            lastbuild-file-path (str (fs/path dir ".lastbuild"))
            config-yml-file-path (str (fs/path dir "deployment.yml"))
            config-json-file-path (str (fs/path dir "deployment.json"))
            config-py-file-path (str (fs/path dir "deployment.py"))]

        ;; Write a 'last build' file
        (spit lastbuild-file-path commit-msg)
        (gitops/add-file lastbuild-file-path dir)

        (let [branch-config-data
              (doall
               (-> config
                   (assoc :current-environment id)
                   (dissoc :environments :dir :offline :notebooks)
                    ;; Re-associate the correct environment in-place
                   (assoc :environments {id (get-in config [:environments id])})
                    ;; Also include for legacy compatability
                   (assoc :environment-data (assoc (get-in config [:environments id]) :id id))
                   ;; Also provide notebooks as a list for compatability
                   (assoc :notebooks (mapv (fn [[k v]] (assoc v :id k)) (:notebooks config)))))

              branch-config-json (fileops/to-json branch-config-data)]

          ;; Write a yaml deployment info file
          (spit config-yml-file-path (fileops/to-yaml branch-config-data))
          (gitops/add-file config-yml-file-path dir)

          ;; Write a json deployment info file
          (spit config-json-file-path branch-config-json)
          (gitops/add-file config-json-file-path dir)

          ;; Write a python deployment info file.
          (spit config-py-file-path (fileops/replace-mustaches
                                     python-json-loading-template
                                     {:json branch-config-json}))
          (gitops/add-file config-py-file-path dir))

        ;; Commit the changes
        (log/info (str "Building with commit message: " commit-msg))
        (gitops/commit commit-msg dir))

      (when (and offline push)
        (log/info "OFFLINE: Skipping push."))

      ;; Push if flag is passed
      (when-not offline
        (when push
          ;; Then push if online, branch exists, etc.
          (gitops/push dir)))

      ;; Success.
      (log/info log-prefix "Finished building target."))

    (catch Exception e
      (log/error "Error encountered while handling branch " id)
      (gitops/reset dir)
      (throw e))))

(defn build-branches
  "Proceeds with the construction of build branches."
  [{:keys [offline dir environments build-all-branches] :as config}]
  (try
    (log/info "Branching build strategy selected.")
    (when-not offline
      (gitops/fetch-all dir)
      (log/info "Fetched latest git branches from remote"))

    ;; Pull all branches from config and verify they exist
    (let [current-branch (:initial-branch config)
          repo-branches (gitops/get-all-branches dir)
          required-branches (config-branches config)
          missing-branches (set/difference required-branches repo-branches)]

      ;; Not building from the config in 'development' or 'master' could cause problems
      (log/info (str "Configuration file is from branch: " current-branch))
      (gitops/switch-branch current-branch dir)
      (log/info (str "Branches required: " required-branches))
      (log/info (str "Branches in repository: " repo-branches))

      ;; Fail if branch name seems wrong
      ;; (when (s/starts-with? current-branch "build")
      ;;   (throw (ex-info "You appear to be inside a build branch." {:branch current-branch})))

      ;; Warn if key build-branches are missing and need to be created.
      (when-not (empty? missing-branches)
        (throw (ex-info (s/join "\n" ["Git branches missing, create them: "
                                      "  git switch --orphan <branch name>"
                                      "  git commit --allow-empty -m 'creating build branch'"
                                      "  git push -u origin <branch-name>"])
                        {:missing-branches missing-branches})))

      (log/info "All required branches present. Proceeding with branch-based build.")

      ;; NOTE: It would probably be smartest only to build from the
      ;; branch that the user is currently present on to ensure that the
      ;; build files listed in the config are actually present.

      ;; Filter environments by those that match the current base branch
      (let [target-envs (cond->> environments
                          (not build-all-branches)
                          (filter (fn [[_ envr]] (= current-branch (:source_branch envr)))))]

        ;; Show a message when not all branches are being assembled
        (when-not build-all-branches
          (log/info (str "Building only branches sourced from '" current-branch "'"
                        ", switch branches to build others or use --build-all-branches")))

        ;; We can destructure the map entry to [key value]

        (doseq [[target-id target-data] target-envs]
          (build-one-branch (assoc target-data :id target-id) config)))

      ;; Switch home and signal success.

      (log/info "\nSuccess.")
      (gitops/switch-branch current-branch dir)
      (log/info (str "Completed build from '" current-branch "' branch.")))

    (catch Exception e
      (log/error "Error encountered while handling branching builds.")
      (gitops/switch-branch (:initial-branch config) dir)
      (throw e))))

(comment
  (build-branches t))
;; https://clojuredocs.org/clojure.core/juxt
