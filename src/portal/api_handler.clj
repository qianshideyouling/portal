(ns portal.api-handler
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]))

(def apis (java.util.HashMap.))

(defn register-api [name outputdoc docstring args nargs func]
  (.put apis name (array-map :name name :outputdoc outputdoc :docstring docstring :args args :nargs nargs :func func)))

(defmacro defapi
  [name doc output args & body]
  `(let [fn# (fn ~args ~@body)
         nargs# ~(count args)
         fn2# (fn [~(quote x)] (map fn# ~(quote x)))]
     (def ~name fn#)
     (register-api ~(keyword name) ~output ~doc ~(str args) nargs# fn#)
     (when (= 1 nargs#)
       (register-api ~(keyword (str "each-" name)) ~(str "seq of " output) ~(str "map over input seq. for each item: " doc)
                     ~(str (vec (map (comp symbol(partial str "seq-of-")) args))) nargs# fn2#))
     true))

(defn call [name & args]
  (let [api (get apis (keyword name))]
    (when-not api
      (throw (Exception. (str "api: " name " not found"))))
    (when (not= (:nargs api) (count args))
      (throw (Exception. (str "api " name "expect " (:nargs api) "args, but given " (count args)))))
    (apply (:func api) args)))

(defn nargs [name]
  (let [api (get apis (keyword name))]
    (when-not api
      (throw (Exception. (str "api: " name " not found"))))
    (:nargs api)))

(def ^:dynamic *thread-env* nil)

(defn rdelim [delim]
  (case delim
    "^" "//^"
    "/" "/"
    "." "\\."
    "$" "\\$"
    "&" "\\&"
    nil))

(defn numberize [x]
  (cond 
    (not (string? x)) x
    (re-matches #"^[\\-]?[0-9]+$" x) (Long/parseLong x)
    (re-matches #"^[\\-]?[0-9]+[\\.]?[0-9]*$" x) (Long/parseLong x)
    (re-matches #"^true$" x) true
    (re-matches #"^false$" x) false
    :otherwise x))

(defn thread-call-path
  ([seed path] (thread-call-path "/" seed path))
  ([delim seed path] 
    (if (or (empty? path) (= delim path))
      seed
      (let [rdelim (rdelim delim)
            path (if (string? path)
                   (map #(.replaceAll % "@#@#@" delim)
                        (str/split (.replaceAll path (str rdelim rdelim) "@#@#@")
                                   (re-pattern rdelim)))
                   path)
            name (first path)
            na (nargs name)
            args (take na (rest path))
            new-path (drop na (rest path))
            _ (when (not= na (count args)) (throw (Exception. "not enough arguments left")))
            args (map (fn[x] (if (= x "~")
                               seed
                               (numberize x)))
                      args)
            result (apply call name args)]
        (thread-call-path delim result new-path)))))

(defapi echo 
  ""
  ""
  [v]
  v)