(ns portal.http-server
  (:require [clojure.string :as str]
            [org.httpkit.server :as http]
            [compojure.route :as route]
            [compojure.core :as comp]
            [clojure.java.io :as jio]
            [clojure.tools.nrepl.server :as nrepl]
            [clojure.xml :as xml]
            [ring.util.response :as response]
            [ring.util.mime-type :as mime]))

(defn multi-atom-get
  [map-of-atoms key default-val]
  (or (get map-of-atoms key)
      (locking map-of-atoms
        (or (get map-of-atoms key)
            (let [a (atom default-val)]
              (.put map-of-atoms key a)
              a)))))

(deftype SingleUse
  [data-payload-atom]
  clojure.lang.IDeref
  (deref [this]
    (let [x @data-payload-atom]
      (if (identical? x data-payload-atom)
        (throw (Exception. "singleuse: data has been used"))
        (do (reset! data-payload-atom data-payload-atom)
          x)))))

(defn single-use [payload-value]
  (SingleUse. (atom payload-value)))

(defn random-is 
  [max]
  (let [cnt (atom 0)
        r (java.util.Random.)]
    (proxy [java.io.InputStream] []
      (read [& args]
        (cond 
          (> @cnt max) -1
          (= 0 (count args))
          (do (swap! cnt inc) (.nextInt r 255))
          :otherwise (do (reset! cnt (+ @cnt (alength (first args))))
                       (.nextBytes r (first args))
                       (alength (first args))))))))

(defn bytes? [x]
  (when x
    (= (Class/forName "[B")
       (.getClass x))))

(defn is [a]
  (cond (string? a) (java.io.ByteArrayInputStream. (.getBytes a))
        (instance? java.io.InputStream a) a
        (bytes? a) (java.io.ByteArrayInputStream. a)
        (instance? java.io.Reader a) (org.apache.commons.io.input.ReaderInputStream. a)
        :otherwise (throw (Exception. (str "data need to be String/Reader/byte-array/inputstream, you provided:"
                                           (if a (type a)
                                             "null"))))))

(defn stream->bytes
  [x]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy x out)
    (.toByteArray out)))

(defn stringify [x]
  (cond (string? x) x
        (bytes? x) (String. x)
        (instance? java.io.InputStream x) (slurp x)
        :else
        (str x)))

(defn size [frame]
  (cond (nil? frame)
        0
        (string? frame)
        (.length frame)
        (bytes? frame)
        (alength frame)
        (instance? java.nio.ByteBuffer frame)
        (- (.limit frame) (.position frame))
        :else
        -1))

(defn to-byte-buffer-seq
  ([inputstream]
    (let [xsize (* 64 1024)]
      (to-byte-buffer-seq inputstream
                          xsize
                          (int (- xsize (/ xsize 5)))
                          1
                          (byte-array xsize)
                          0
                          xsize)))
  ([inputstream xsize min-buffer-transfer-size k buffer off remaining]
    (let [alen (. inputstream read buffer off remaining)
          used (+ off alen)
          remaining (- remaining alen)]
      (cond (and (= alen -1) (= off 0))
            nil
            (= alen -1)
            (cons (java.nio.ByteBuffer/wrap buffer 0 (inc used))
                  nil)
            (= xsize used)
            (cons (java.nio.ByteBuffer/wrap buffer)
                  (lazy-seq (to-byte-buffer-seq inputstream
                                                xsize
                                                min-buffer-transfer-size
                                                (inc k)
                                                (byte-array xsize)
                                                0
                                                xsize)))
            (> used min-buffer-transfer-size)
            (cons (java.nio.ByteBuffer/wrap buffer 0 used)
                  (lazy-seq (to-byte-buffer-seq inputstream 
                                                xsize 
                                                min-buffer-transfer-size
                                                (inc k)
                                                (byte-array xsize)
                                                0
                                                xsize)))
            :else
            (recur inputstream xsize min-buffer-transfer-size
                   (inc k)
                   buffer used remaining)))))

(defn gz-compress
  [inputstream]
  nil)

(defn send-response 
  [request-without-body {seq-of-frames :body :as ring-response}]
  (let [seq-of-frames (single-use seq-of-frames)
        meta (dissoc ring-response :body)
        ring-response nil
        open (atom true)]
    (http/with-channel request-without-body channel
      (try 
        (when-not (:websocket? request-without-body)
          (http/send! channel meta false))
        (http/on-receive channel (fn [data]
                                   "cannot receive data via websocket."
                                   (reset! open false)))
        (http/on-close channel (fn [status]
                                 (reset! open false)))
        (let [seq-of-frames @seq-of-frames]
          (loop [frame (first seq-of-frames)
                 seq-of-frames (rest seq-of-frames)]
            (when-not @open (let [x "chunked transfer canceled"]
                              (throw (Exception. x))))
            (http/send! channel frame false)
            (when (seq seq-of-frames)
              (recur (first seq-of-frames)
                     (rest seq-of-frames)))))
        (catch Throwable e
          (http/close channel)
          (throw e)))
      (http/close channel))))

(defn dont-manipulate-response?
  [response]
  (or (= (:status response) 304)
      (and (string? (:body response)) (= "" (:body response)))
      (instance? java.io.File (:body response))
      (nil? (:body response))))

(defn response-needs-compression?
  [request response]
  (and (not (:websocket? request))
       (not (:eventsource? request))
       (not (= "no-compress" (:content-type request)))
       (not (false? (:compress? response)))))

(defn seq-size [body]
  (let [body-byte-count (atom 0)
        body (map (fn [x] (swap! body-byte-count + (size x)) x) body)]
    [body-byte-count body]))

(defn one-before-last 
  ([item col]
    (one-before-last item (first col) (rest col)))
  ([item next others]
    (if-not (seq others)
      [item next]
      (cons next (lazy-seq (one-before-last item (first others) (rest
                                                                  others)))))))
(defn to-event-stream
  [seq-of-frames]
  (if-not (seq seq-of-frames)
    ["id:EMPTY\n\ndata:EMPTY\n\n"]
    (cons "id: START\n\n"
          (->> seq-of-frames
            (map (fn [x] (str "data: " 
                              (clojure.string/replace (stringify x)
                                                      #"\r?\n" "\ndata: ")
                              "\n\n")))
            (one-before-last "id: DONE\n\n")))))

(defn seq-like?
  [col]
  (and (not (string? col))
       (or (nil? col)
           (vector? col)
           (seq? col)
           (list? col)
           (seq col))))

(defn prepare-response [request response]
  (let [response (if (map? response) response {:body response})
        response (merge {:time-out (System/currentTimeMillis)
                                   :status 200
                                   :headers {}}
                        response)]
    (if (dont-manipulate-response? response)
      (assoc response :frame-count (atom -1) :body-byte-count (atom -1))
      (let [compress? (response-needs-compression? request response)
            meta (dissoc response :body :headers)
            headers (merge {"X-UA-Compatible" "IE=Edge"
                            "Access-Control-Allow-Origin" (or (get (:headers 
                                                                     request)
                                                                   "Origin") "*")}
                           (:headers response))
            headers (if compress? (assoc headers "Content-Encoding" "gzip")
                      headers)
            headers (if (:eventsource? request) (assoc headers "Content-Type"
                                                       "text/event-stream") headers)
            body (:body response)
            _ (when (and (or (:websocket? request) (:eventsource? request))
                         (not (seq-like? body)))
                (throw (Exception. (str "response must be s sequence, result were instead: "(type body)))))
;            [body-byte-count body] (cond (:websocket? request)
;                                         (seq-size body)
;                                         (:eventsource? request)
;                                         (->> body
;                                           to-event-stream
;                                           seq-size)
;                                         :else
;                                         (let [counted-body (atom 128);(sm/counting-stream (is body))
;                                               body (if compress? (gz-compress counted-body)
;                                                      counted-body)]
;                                           [counted-body (to-byte-buffer-seq body)]))
            frame-count (atom 0)
            body (to-byte-buffer-seq (is body))
            body (map (fn [x] (swap! frame-count inc) x) body)]
        (merge {:frame-count frame-count
                :body-byte-count (atom 0)
                :body body}
               meta 
               {:headers headers})))))

(def global-inflight-requests (atom {}))

(def global-unique-request-ids (atom -1))

(def connections (java.util.HashMap.))

(def default-info {:connect 0
                   :disconnect 0
                   :difference 0
                   :ttlb 0
                   :sending 0
                   :ttfb 0
                   :bytes 0
                   :frame 0})

(defn register-connect [ip-address]
  (let [ip-address (if (map? ip-address)
                     (:remote-addr ip-address)
                     ip-address)
        info-atom (multi-atom-get connections ip-address default-info)]
    (swap! info-atom (fn [m]
                       (-> m
                         (update-in [:connect] inc)
                         (update-in [:difference] inc))))))

(defn register-disconnect
  [ip-address sending ttfb ttlb bytes frames]
  (let [ip-address (if (map? ip-address)
                     (:remote-addr ip-address)
                     ip-address)
        info-atom (multi-atom-get connections ip-address default-info)]
    (swap! info-atom (fn [m]
                       (-> m
                         (update-in [:disconnect] inc)
                         (update-in [:difference] dec)
                         (update-in [:ttlb] + ttlb)
                         (update-in [:ttfb] + ttfb)
                         (update-in [:sending] + sending)
                         (update-in [:bytes] + (max bytes 0))
                         (update-in [:frames] + (max frames 0)))))))

(def global-recent-responses
  (java.util.concurrent.ArrayBlockingQueue. 100))

(defn add-recent-request-response-pair
  [request-without-body response-without-body]
  (let [response (if (map? response-without-body)
                   (assoc response-without-body
                          :body-byte-count @(:body-byte-count response-without-body)
                          :frame-count @(:frame-count response-without-body))
                   response-without-body)
        pair [request-without-body response]]
    (locking global-recent-responses
      (when-not (.offer global-recent-responses pair)
        (.poll global-recent-responses)
        (.offer global-recent-responses pair)))))

(defn balancer [handler-fn result-fn request]
  (let [id (swap! global-unique-request-ids inc)
        request (assoc request
                       :time-in (System/currentTimeMillis)
                       :request-body-length 0
                       :eventsource? (= (get (:headers request) "accept")
                                        "text/event-stream")
                       :id id)
        request-without-body (dissoc request :body)]
    (if (and (:eventsource? request) (get (:headers request) "last-event-id"))
      {:status 204 :body nil :headers {"access-control-allow-origin" 
                                       (or (get (:headers request) "Origin") "*")}}
      (try (swap! global-inflight-requests assoc id request-without-body)
        (register-connect request-without-body)
        (let [response (handler-fn request)
              ;response (prepare-response request-without-body response)
              response-without-body (dissoc request :body)
;              sent-response (if (dont-manipulate-response? response)
;                              response
;                              (send-response request-without-body response))
              response-without-body (assoc response-without-body :time-sent 
                                           (System/currentTimeMillis))
;              response-without-body (assoc response-without-body
;                                           :ttfb (- (:time-out response-without-body)
;                                                    (:time-in response-without-body))
;                                           :sending (- (:time-out response-without-body)
;                                                    (:time-in response-without-body))
;                                           :ttlb (- (:time-sent response-without-body)
;                                                    (:time-in response-without-body)))
              ]
          (result-fn request-without-body response-without-body)
          ;(add-recent-request-response-pair request-without-body response-without-body)
;          (register-disconnect request-without-body
;                              (:sending response-without-body)
;                              (:ttfb response-without-body)
;                              (:ttlb response-without-body)
;                              @(:body-byte-count response-without-body)
;                              @(:frame-count response-without-body))
;          ;sent-response
           {:status  200
      :headers {"Content-Type" "text/html"}
      :body    response})
        (catch Throwable e
          (result-fn request-without-body e)
          ;(add-recent-request-response-pair request-without-body e)
          ;(register-disconnect request-without-body 0 0 0 0 0)
          (throw e))
        (finally
          (swap! global-inflight-requests dissoc id))))))

(defn server [handler-fn result-fn config]
  (http/run-server (fn [request] (balancer handler-fn result-fn request))
                   (merge {:max-line (* 100 1024)
                           :max-body (- (* 100 1024 1024) 1)}
                          config)))