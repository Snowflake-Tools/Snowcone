(ns snowcone.gitops
  (:require
   [clojure.string :as s]
   [snowcone.shell :refer [run-command]]))

(defn get-current-branch [dir]
  (s/trim (:out (run-command "git branch --show-current" {:dir dir}))))

(defn switch-branch [branch dir]
  (run-command (str "git checkout " branch) {:dir dir}))

(defn fetch-all [dir]
  (run-command "git fetch --all" {:dir dir}))

(defn fetch-branch-from-origin
  "Fetches the latest commit for the target branch from origin."
  [branch dir]
  (run-command (str "git fetch origin " branch " --depth=1") {:dir dir}))

(defn get-all-branches [dir]
  (->> (run-command "git branch" {:dir dir})
       :out
       (s/split-lines)
       (map #(s/trim (subs % 1)))
       (set)))

(defn add-file [file dir]
  (run-command (str "git add " file) {:dir dir}))

(defn commit [message dir]
  (run-command
   ["git" "commit" "-m" (str message)]
   {:dir dir :raw-args true}))

(defn reset [dir]
  (run-command "git reset --hard" {:dir dir}))

(defn push [dir]
  (run-command "git push" {:dir dir}))

(defn upstream-branch [dir]
  (s/trim (:out (run-command "git rev-parse --abbrev-ref @{u}" {:dir dir}))))

(defn upstream-branch-exists [dir]
  (zero? (:exit (run-command "git rev-parse --abbrev-ref @{u}"
                             {:dir dir :continue true :err :string}))))

(defn push-set-origin [remote-branch dir]
  (run-command (str "git push --set-upstream origin " remote-branch) {:dir dir}))

(defn untrack-all [branch dir]
  (when-not (s/starts-with? branch "build")
    (throw (ex-info "Trying to untrack a non-build branch. Stopping." {})))
  (run-command "git rm -rf --cached ." {:dir dir}))

(defn list-tracked-files [branch dir]
  (->>
   (run-command (str "git ls-tree -r --name-only --full-tree " branch) {:dir dir})
   :out
   (s/split-lines)
   (remove s/blank?)))

(defn list-modified-files [dir]
  (->>
   (run-command "git status -s" {:dir dir})
   :out
   (s/split-lines)
   (remove s/blank?)))

(defn restore-file
  "A method of copying a file between branches."
  [file-path source-branch dir offline]
  (run-command
   (if offline
     (str "git restore --source=" source-branch " -- " file-path)
     (str "git restore --source=origin/" source-branch " -- " file-path))
   {:dir dir}))

(defn current-commit [dir]
  (s/trim (:out (run-command "git rev-parse HEAD" {:dir dir}))))

(defn current-short-commit [dir]
  (s/trim (:out (run-command "git rev-parse --short HEAD" {:dir dir}))))
