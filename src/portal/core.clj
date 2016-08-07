(ns portal.core
  (:require [clojure.string :as str]
            [portal.http-server :as http-server]
            [compojure.route :as route]
            [compojure.core :as comp]
            [clojure.java.io :as jio]
            [clojure.tools.nrepl.server :as nrepl]
            [clojure.xml :as xml]
            [ring.util.response :as response]
            [ring.util.mime-type :as mime]
            [portal.api-handler :as api]))

(defn append-log
  [log-path message]
  (println message)
  (let [path (.getAbsolutePath (java.io.File. log-path))]
    (locking path
      (if-not
        (.exists (.getParentFile (new java.io.File path)))
        (.mkdirs (.getParentFile (new java.io.File path))))
      (with-open [w (jio/writer path :append true)
                  out (new java.io.PrintWriter w)]
        (.println out (str (java.util.Date.);(.format (new java.text.SimpleDataFormat "yyyy-MM-dd HH:mm:ss") (java.util.Date))
                           " " (if (string? message) message (pr-str message))))
        (.flush out)))))

(defn get-delim [uri]
  "/")

(defn rdelim [delim]
  "/")

(defn extract-token [uri delim]
  "token")

(defn session [token]
  "session")

(defn call-thread [ring-request]
  (try (let [payload (:body ring-request)
             uri (java.net.URLDecoder/decode (:uri ring-request) "UTF-8")
             delim (get-delim uri)
             rdelim (rdelim delim)
             content-type (:content-type ring-request)
             token (extract-token uri delim)
             session (session token)]
         (when (nil? session)
               (throw (Exception. (str "session authentication failed, can not authentication token" token))))
         (binding [api/*thread-env* session]
           (let [script (str/replace-first uri (re-pattern (str "^/.*?" rdelim ".*?" rdelim)) "")
                 response (api/thread-call-path delim payload script)]
             response)))
    (catch Exception e
      (Thread/sleep 50);; accident failure-retry cycle ddos
      {:body (str "failure during threading" (with-out-str (.printStackTrace e (java.io.PrintWriter. *out*))))
       :status 500})))

(defn call-curl [{uri :uri :as ring-request}]
  "")

(defn banned? [request]
  false)

(defn banned-response [ring-request]
  nil)


(defn calm? [request]
  false)

(defn calm-response [ring-request]
  nil)

(defn static-route [ring-request]
  (let [uri (java.net.URLDecoder/decode (:uri ring-request) "UTF-8")
        target (.getAbsolutePath (java.io.File. (str "." uri)))]
    (merge (response/file-response target)
                                 {:mime (mime/ext-mime-type target)})))

(defn request-handler
  [{uri :uri :as ring-request}]
  (cond (banned? ring-request)
        (banned-response ring-request)
        (calm? ring-request)
        (calm-response ring-request)
        :else
        (let [{:keys [uri status]
               :as ring-request}
              (if (.startsWith uri "/curl")
                (call-curl ring-request)
                ring-request)]
          (cond status ring-request
                (.startsWith uri "/thread") (call-thread ring-request)
                :else (static-route ring-request)))))

(defn response-handler [request response]
  nil)

(defn server [http-options-map]
  (http-server/server (fn [request] (request-handler request))
                      (fn [request response] (response-handler request response))
                      http-options-map))