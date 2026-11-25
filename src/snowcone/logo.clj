(ns snowcone.logo)

;; This has backslashes escaped - test after changing!
(def snowcone-logo "
     _____
    / ___/____  ____ _      ___________  ____  ___
    \\__ \\/ __ \\/ __ \\ | /| / / ___/ __ \\/ __ \\/ _ \\
   ___/ / / / / /_/ / |/ |/ / /__/ /_/ / / / /  __/
  /____/_/ /_/\\____/|__/|__/\\___/\\____/_/ /_/\\___/
  =================================================
    -*- Advanced Snowflake Project Build Tool -*-
")

(defn print-snowcone-logo []
  (println snowcone-logo))
