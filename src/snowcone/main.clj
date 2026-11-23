(ns snowcone.main
  (:require
   [snowcone.preflight-checks :as checks]
   [snowcone.build-folder :refer [build-folder]]
   [snowcone.build-branches :refer [build-branches]]
   [babashka.cli :as cli]
   [taoensso.timbre :as timbre]
   [clojure.tools.logging :as log]
   [snowcone.config :refer [get-config-data]]
   [snowcone.gitops :as gitops]
   [clojure.pprint :refer [pprint]]
   [babashka.fs :as fs]))

;; Resources:
;; https://github.com/babashka/cli

(def arg-spec
  "The command-line arguments to be parsed by the Snowcone command line."
  {:dir
   {:alias :d
    :desc "Run as if snowcone was started in <dir>"
    :default (str (fs/cwd))
    :validate                  ; tests if --dir exists,
    {:pred fs/directory?         ; with a custom error message
     :ex-msg (fn [{:keys [value]}]
               (str "Directory does not exist: " value))}}
   
   :config-name
   {:alias :c
    :desc "The name of the project configuration file, default 'config.yml'"
    :default "config.yml"}
   
   :push
   {:coerce :boolean ; defines a boolean flag
    :desc "Push when finished with build operation"}
   
   :offline
   {:coerce :boolean
    :desc "Don't fetch branches and perform other network-related git activites"}
   
   :show-config
   {:coerce :boolean
    :desc "Show the loaded config after ingestion"}
   
   :build-all-branches
   {:coerce :boolean
    :desc "Branch-based builds: Rebuild from all base branches"}
   
   :verbose
   {:coerce :boolean
    :desc "Show extra debugging outputs"}})

(defn clean-opts
  "Post-processing for incoming command line arguments"
  [opts]
  (-> opts
      ;; Clean, expand, home-normalize the provided directory
      (assoc :dir (str (fs/normalize (fs/expand-home (:dir opts)))))))

(defn build-project
  "With cleaned and validated config data, builds the Snowflake project."
  [config]
  
  ;; Show configuration if asked for
  (when (or (:show-config config) (:verbose config))
    (log/info "Building project with configuration data:")
    (pprint config))
  
  ;; Launch build based on build type
  (let [build-type (get-in config [:build :type])]
    (case (keyword build-type)
      :folder (build-folder config)
      :branch (build-branches config)
      (throw (ex-info "Unknown project type." {:type build-type})))))

(defn run
  "Runs the build process based on the provided configuration file"
  [input-opts]
  (log/info "\n\nSNOWCONE: Snowflake Build Tool 0.1.0")
  (let [opts (clean-opts input-opts)]
    
    (when (:verbose opts)
      (log/info "Command line arguments passed:")
      (pprint opts))
    
    ;; Preflight Checks
    (checks/check-if-git-repository-root (:dir opts))
    (checks/check-if-config-file-is-parsable (:dir opts))

    (when (:verbose opts)
      (timbre/set-min-level! :debug))
    
    ;; Combine config data with cli-opts
    (let [config-data (get-config-data (:dir opts) (:config-name opts))]
      (build-project
       (-> config-data
           (merge opts) ; include command line options in passed data
           (assoc :initial-branch (gitops/get-current-branch (:dir opts)))
           (assoc :current-commit (gitops/current-commit (:dir opts)))
           (assoc :current-commit-short (gitops/current-short-commit (:dir opts)))
           (checks/validate-config))))
    
    (log/info "Success. Please validate output.")))

(defn -main
  "Dispatches the 'run' function with parsed CLI arguments"
  [& args]

  ;; LOGGING SETUP
  ;; =============
  (timbre/set-min-level! :info) ; initial log level = info
  (timbre/merge-config!
   {
    :timestamp-opts ; Simplified local timestamps
    {:pattern  "yyyy-MM-dd HH:mm:ss"
     :timezone (java.util.TimeZone/getDefault)}

    :output-fn ; Format
    (fn [{:keys [timestamp_ level ?ns-str msg_]}]
      (format "%s [%s] (%s) - %s"
              (force timestamp_)
              (.toUpperCase (name level))
              (or ?ns-str "unknown")
              (force msg_)))})

  (cli/dispatch
   {:exec-fn run :spec arg-spec}
   args
   {:prog "snowcone" :help true}))

(defn add
  "Simple function to unit test"
  [a b]
  (+ a b))

;; ================================================================================
;; Testing
(comment
  ;; Test the main procedure
  (-main)
  (add 1 2)
  
  ;; To run main in a babashka script:
  ;;(apply -main *command-line-args*)
  )

