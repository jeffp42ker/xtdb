(ns dev
  (:require [clojure.java.io :as io]
            [clojure.tools.namespace.repl :as tn]
            [crux.embedded-kafka :as ek]
            [crux.bootstrap :as b]
            [crux.io :as cio])
  (:import [kafka.server KafkaServerStartable]
           [org.apache.zookeeper.server ServerCnxnFactory]
           [clojure.lang IDeref Var$Unbound]
           [java.io Closeable]))

;; Inspired by
;; https://medium.com/@maciekszajna/reloaded-workflow-out-of-the-box-be6b5f38ea98

(def instance)
(def init)

(defn ^Closeable closeable
  ([value]
   (closeable value identity))
  ([value close]
   (reify
     IDeref
     (deref [_]
       value)
     Closeable
     (close [_]
       (close value)))))

(defn closeable-future-call [f]
  (let [done? (promise)]
    (closeable
     (future
       (try
         (f)
         (finally
           (deliver done? true))))
     (fn [this]
       (future-cancel this)
       @done?))))

(defn start []
  (alter-var-root
   #'instance #(cond (not (bound? #'init))
                     (throw (IllegalStateException. "init not set."))

                     (or (nil? %)
                         (instance? Var$Unbound %))
                     (cast Closeable (init))

                     :else
                     (throw (IllegalStateException. "Already running."))))
  :started)

(defn stop []
  (when (and (bound? #'instance)
             (not (nil? instance)))
    (alter-var-root #'instance #(.close ^Closeable %)))
  :stopped)

(defn reset []
  (stop)
  (let [result (tn/refresh :after 'dev/start)]
    (if (instance? Throwable result)
      (throw result)
      result)))

(defn with-system-var [f target-var]
  (fn [system]
    (try
      (alter-var-root target-var (constantly system))
      (f system)
      (finally
        (alter-var-root target-var (constantly nil))))))

(defn with-system-promise [f promise]
  (fn [system]
    (deliver promise system)
    (f system)))

(defn ^Closeable new-zk [{:keys [storage-dir]}]
  (closeable
    (ek/start-zookeeper
     (io/file storage-dir "zk-snapshot")
     (io/file storage-dir "zk-log"))
    (fn [^ServerCnxnFactory zk]
      (.shutdown zk))))

(defn ^Closeable new-kafka [{:keys [storage-dir]}]
  (closeable
   (ek/start-kafka-broker
    {"log.dir" (.getAbsolutePath (io/file storage-dir "kafka-log"))})
   (fn [^KafkaServerStartable kafka]
     (.shutdown kafka)
     (.awaitShutdown kafka))))

(defn ^Closeable new-kv-store [{:keys [storage-dir]
                                :as config}]
  (b/start-kv-store (assoc config :db-dir (io/file storage-dir "data"))))

(defn ^Closeable new-index-node [kv-store config]
  (closeable-future-call
   #(b/start-system kv-store {})))

(defn new-crux-system [f config]
  (closeable-future-call
   #(with-open [zk (new-zk config)
                kafka (new-kafka config)
                kv-store (new-kv-store config)
                index-node (new-index-node kv-store config)]
      (f (merge config {:zk @zk
                        :kafka @kafka
                        :kv-store kv-store
                        :index-node @index-node})))))

(def config {:storage-dir "dev-storage"
             :kv-backend "rocksdb"})

(def system)

(alter-var-root
 #'init (constantly
         #(let [started (promise)
                instance (-> (comp deref :index-node)
                             (with-system-promise started)
                             (with-system-var #'system)
                             (new-crux-system config))]
            @started
            instance)))

(defn delete-storage []
  (stop)
  (cio/delete-dir (:storage-dir config))
  :ok)
