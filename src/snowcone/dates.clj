(ns snowcone.dates)

(defn datestamp []
  (str (.format 
        (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss")
        (java.util.Date.))))

(comment
  ;; Added seconds so repeated builds under a minute are fixed
  (datestamp)
)
