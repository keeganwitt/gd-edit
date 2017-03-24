(ns gd-edit.utils
  (:require [clojure
             [set :refer [intersection]]
             [string :as str]]
            [jansi-clj.core :refer :all]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [gd-edit.db-utils :as dbu])
  (:import java.nio.ByteBuffer
           java.nio.channels.FileChannel
           java.nio.file.Paths
           java.io.File))

(defn mmap
  [filepath]

  (with-open [db-file (java.io.RandomAccessFile. filepath "r")
              file-channel (.getChannel db-file)]
    (let [file-size (.size file-channel)]

      (.map file-channel java.nio.channels.FileChannel$MapMode/READ_ONLY 0 file-size))))

(defn file-contents
  [filepath]

  (with-open [file-channel (.getChannel (java.io.RandomAccessFile. filepath "r"))]
    (let [bb (ByteBuffer/allocate (.size file-channel))]
      (.read file-channel bb)
      (.rewind bb))))

(defn hexify [s]
  (apply str
         (map #(format "%02x " (byte %)) s)))


;;------------------------------------------------------------------------------
;; Timing functions
;;------------------------------------------------------------------------------
(defmacro timed
  "Times the execution time of the given expression. Returns a vector of [elapsed-time sexp-result]"
  [sexp]

  `(let [start# (System/nanoTime)
         result# ~sexp
         end# (System/nanoTime)]
    [(- end# start#) result#]))

(defn nanotime->secs
  "Given duration in nanoseconds, return the duration in seconds"
  [time]
  (/ (float time) 1000000000))


;;------------------------------------------------------------------------------
;; String comparison
;;------------------------------------------------------------------------------
(defn case-insensitive=
  "Check if str2 can be found in str1"
  [str1 str2]

  (= (str/lower-case (str str1)) (str/lower-case (str str2))))

(defn case-insensitive-match
  "Check if str2 can be found in str1"
  [str1 str2]

  (.contains (str/lower-case (str str1)) (str/lower-case (str str2))))

(def ci-match case-insensitive-match)

(defn bigrams [s]
  (->> (str/split s #"\s+")
       (mapcat #(partition 2 1 %))
       (set)))

(defn string-similarity [a b]
  (let [a-pairs (bigrams a)
        b-pairs (bigrams b)
        total-count (+ (count a-pairs) (count b-pairs))
        match-count (count (intersection a-pairs b-pairs))
        similarity (/ (* 2 match-count) total-count)]
    similarity))


;;------------------------------------------------------------------------------
;; Path related functions
;;------------------------------------------------------------------------------
(defn expand-home [s]
  (if (.startsWith s "~")
    (str/replace-first s "~" (System/getProperty "user.home"))
    s))

(defn filepath->components
  [path]

  (str/split path #"[/\\]"))

(defn components->filepath
  [components]

  (str/join File/separator components))

(defn last-path-component
  [path]

  (last (str/split path #"[/\\]")))

(defn path-exists
  [path]

  (if (and (not (nil? path))
           (.exists (io/file path)))
    true
    false))

;;------------------------------------------------------------------------------
;; Core lib extensions
;;------------------------------------------------------------------------------
(defmacro doseq-indexed [index-sym [item-sym coll] & body]
  `(doseq [[~item-sym ~index-sym]
           (map vector ~coll (range))]
     ~@body))

(def byte-array-type (Class/forName "[B"))

(defn byte-array?
  [obj]
  (= byte-array-type (type obj)))

(defmacro fmt
  [^String string]
  "Like 'format' but with string interpolation"
  (let [-re #"#\{(.*?)\}"
        fstr (str/replace string -re "%s")
        fargs (map #(read-string (second %)) (re-seq -re string))]
    `(format ~fstr ~@fargs)
    ))


;;------------------------------------------------------------------------------
;; Environment info
;;------------------------------------------------------------------------------

(defn working-directory
  []
  (System/getProperty "user.dir"))

(defn running-linux?
  []
  (= (System/getProperty "os.name") "Linux"))

(defn running-osx?
  []
  (= (System/getProperty "os.name") "Mac OS X"))

(defn running-nix?
  []
  (or (running-osx?)
      (running-linux?)))

(defn running-windows?
  []
  (str/starts-with? (System/getProperty "os.name") "Windows"))


;;------------------------------------------------------------------------------
;; Settings file
;;------------------------------------------------------------------------------
(defn settings-file-path
  []
  (.getAbsolutePath (io/file (working-directory) "settings.edn")))

(defn load-settings
  []
  (try
    (edn/read-string (slurp (settings-file-path)))
    (catch Exception e)))

(defn write-settings
  [settings]
  (spit (settings-file-path) (pr-str settings)))


;;------------------------------------------------------------------------------
;; Generic utils
;;------------------------------------------------------------------------------
(defn keyword->str
  [k]
  (if (keyword? k)
    (subs (str k) 1)
    k))

(defn print-indent
  [indent-level]

  (dotimes [i indent-level]
    (print "    ")))

;;------------------------------------------------------------------------------
;; gd-edit specific utils
;;------------------------------------------------------------------------------
(defn without-meta-fields
  [kv-pair]

  (not (.startsWith (str (first kv-pair)) ":meta-")))

(defn is-primitive?
  [val]

  (or (number? val) (string? val) (byte-array? val) (boolean? val)))

(defn is-item?
  "Does the given collection look like something that represents an in-game item?"
  [coll]

  (and (associative? coll)
       (contains? coll :basename)))

(defn is-skill?
  "Does the given collection look like something that represents an in-game skill?"
  [coll]

  (and (associative? coll)
       (contains? coll :skill-name)))

(defn is-faction?
  [coll]

  (and (associative? coll)
       (contains? coll :faction-value)))

(defn skill-name
  [skill]

  (let [record (-> (:skill-name skill)
                   (dbu/record-by-name))]
    (or (record "FileDescription")
        (record "skillDisplayName"))))

(defn faction-name
  [index]

  (let [faction-names {1  "Devil's Crossing"
                       2  "Aetherials"
                       3  "Chthonians"
                       4  "Cronley's Gang"
                       6  "Rovers"
                       8  "Homestead"
                       10 "The Outcast"
                       11 "Death's Vigil"
                       12 "Undead"
                       13 "Black Legion"
                       14 "Kymon's Chosen"}]
    (faction-names index)))

(defn item-is-materia?
  [item]

  (and (str/starts-with? (:basename item) "records/items/materia/")
       (= ((dbu/record-by-name (:basename item)) "Class") "ItemRelic")))
