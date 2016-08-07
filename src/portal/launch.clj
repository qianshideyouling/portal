(ns portal.launch
  (:require [portal.core :as portal]))

(defonce server-config-map
  {:port 8080})

(defn -main []
  (print "------------------ portal start  --------------------\r\n")
    (portal/server server-config-map)
  (print "------------------ portal complete--------------------"))

