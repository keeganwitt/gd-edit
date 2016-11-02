(ns gd-edit.structure
  (:require [clojure.test])
  (:import  [java.nio ByteBuffer]
            [java.nio.file Path Paths Files FileSystems StandardOpenOption]
            [java.nio.channels FileChannel])
  (:gen-class))


(defn spec-type
  [spec & rest]
  (:struct/type (meta spec)))

(defmulti read-spec spec-type)

(def primitives-specs
  ;; name => primitives spec
  {:byte   [:byte    1 (fn[^ByteBuffer bb] (.get bb))      ]
   :int16  [:int16   2 (fn[^ByteBuffer bb] (.getShort bb)) ]
   :int32  [:int     4 (fn[^ByteBuffer bb] (.getInt bb))   ]
   :float  [:float   4 (fn[^ByteBuffer bb] (.getFloat bb)) ]
   :double [:double  4 (fn[^ByteBuffer bb] (.getDouble bb))]
   })


(defn sizeof
  "Given a spec, return the byte size it represents"
  [spec]

  ;; Look through all the items in the given spec
  (reduce
   (fn [accum item]
     ;; Does a matching entry exist for the item in the primitives array?
     (let [primitives-spec (primitives-specs item)]
       (if primitives-spec
         ;; If so, add the size to the accumulator
         (+ accum (nth primitives-spec 1))

         ;; If not, we've come across a field name. Don't do anything
         accum
         )))

   ;; Start the sum at 0
   0

   ;; Ignore all odd fields, assuming they are fieldnames
   (take-nth 2 (rest spec))))


(defn ordered-map?
  [spec]
  (= (-> spec
         meta
         :struct/return-type)
     :map))


(defn strip-orderedmap-fields
  "Returns a spec with the keyname fields stripped if it looks like an orderedmap"
  [spec]

  ;; If it looks like an ordered map
  (if (ordered-map? spec)

    ;; strip all the odd number fields
    (take-nth 2 (rest spec))

    ;; Doesn't look like an ordered map...
    ;; Return the spec untouched
    spec))


(defn compile-meta
  [{:keys [struct/length-prefix] :as spec-meta}]

  ;; The only meta field that needs to be updated is the "length-prefix" field
  ;; If it's not available, we don't need to do anything to compile this
  (if (nil? length-prefix)
    (assoc spec-meta :compiled true)

    ;; There is no reason for a length prefix to be a composite type
    ;; So we won't handle that case
    (let [primitives-spec (primitives-specs length-prefix)]
      (if primitives-spec
        ;; Update the length-prefix field to the function to be called
        (assoc spec-meta
               :struct/length-prefix (nth primitives-spec 2)
               :compiled true)

        ;; If we're not looking at a primitive type, we're looking at a composite type...
        (throw (Throwable. (str "Cannot handle spec:" length-prefix)))))))


(declare compile-spec-)

(defn compile-spec
  [spec]

  (let [compiled-spec (compile-spec- spec)
        compiled-meta (compile-meta (meta spec))]
    (with-meta compiled-spec compiled-meta)))


(defn- compile-spec-
  [spec]

  (let [type (spec-type spec)]
    (cond
      ;; String specs are dummy items
      ;; Just return the item itself
      (= type :string)
      spec

      ;; Sequence of specs?
      ;; Compile each one and return it
      ;; This should handle :variable-count also
      (sequential? spec)
      (reduce
       (fn [accum item]
         (conj accum (compile-spec item))
         )
       (empty spec) 
       spec)

      ;; Otherwise, this should just be a plain primitive
      :else
      (nth (primitives-specs spec) 2)
      )))


(defn read-struct
  "Spec can also be a single primitive spec. In that case, we'll just read
  a single item from the bytebuffer.

  Spec can be a simple sequence of type specs. In that case, a list values
  that corresponds to each item in the given spec.

  Spec can be a collect with some special meta tags. In those cases, special
  handling will be invoked via the 'read-spec' multimethod."
  [spec ^ByteBuffer bb]

  ;; Handle single primitive case
  ;; Handle sequence specs case
  ;; Handle complicated case

  (let [;; Get a list of specs to read without the fieldnames
        stripped-spec (strip-orderedmap-fields spec)

        ;; Read in data using the list of specs
        values (read-spec stripped-spec bb)]

    ;; We've read all the values
    ;; If the user didn't ask for a map back, then we're done
    (if (not (ordered-map? spec))
      values

      ;; The user did ask for a map back
      ;; Construct a map using the fieldnames in the spec as the key and the read value as the value
      (zipmap (take-nth 2 spec) values))
    ))


(defmethod read-spec :default
  [spec bb]

  (cond

    ;; If the spec has been compiled, we will get a function in the place of a
    ;; keyword indicating a primitive.
    ;; Call it with the byte buffer to read it
    (clojure.test/function? spec)
    (spec bb)

    ;; If we're looking at some sequence, assume this is a sequence of specs and
    ;; read through it all
    (seq? spec)
    (reduce
     (fn [accum spec-item]
       (conj accum (read-spec spec-item bb)))
     []
     spec)

    ;; Otherwise, we should be dealing with some kind of primitive...
    ;; Look up the primitive keyword in the primitives map
    :else
    (let [primitives-spec (primitives-specs spec)]

      ;; If the primitive is found...
      (if primitives-spec

        ;; Execute the read function now
        ((nth primitives-spec 2) bb)

        ;; Otherwise, give up
        (throw (Throwable. (str "Cannot handle spec:" spec)))))))


(defn ordered-map
  "Tags the given spec so read-structure will return a map instead of a vector.
  Returns the collection it was given."
  [& fields]
  (with-meta fields {:struct/return-type :map}))


(defn variable-count
  "Tags the given spec to say it will repeat a number of times.

  Exmaple:
  (variable-count :uint32 :count-prefix :uint16)

  This says we will read a :uint16 first to determine how many :uint32 to read
  from the stream."
  [spec & {:keys [length-prefix]
           :or {length-prefix :int32}}]

  ;; We're about to attach some meta info to the spec
  ;; First, wrap the spec in another collection...
  ;; We'll attach the information to information to that collection instead of the
  ;; original spec
  (let [spec-seq (list spec)]

    ;; Attach the meta info to the spec 
    ;; Any sequence can be have meta info attached
    (with-meta spec-seq
      {:struct/type :variable-count
       :struct/length-prefix length-prefix})))


(defmethod read-spec :variable-count
  [spec bb]

  (let [
        ;; Destructure fields in the attached meta info
        {length-prefix :struct/length-prefix} (meta spec)

        ;; Read out the count of the spec to read
        length (read-spec length-prefix bb)

        ;; Unwrap the spec so we can read it
        unwrapped-spec (first spec)
        ]

    ;; Read the spec "length" number of times and accumulate the result into a vector
    ;; We're abusing reduce here by producing a lazy sequence to control the number of
    ;; iterations we run.
    (reduce
     (fn [accum _]
       ;; Read another one out
       (conj accum (read-spec unwrapped-spec bb)))
     []
     (repeat length 1))))


(defn string
  "Returns a spec used to read a string of a specific type of encoding.
  Can supply :length-prefix to indicate how to read the length of the string.
  The default :length-prefix is a :int32."
  [enc & {:keys [length-prefix]
          :or {length-prefix :int32}}]

  ;; Tag a dummy sequence with the info passed into the function
  (with-meta '(:string)
    {:struct/type :string
     :struct/length-prefix length-prefix
     :struct/string-encoding enc}))


(defmethod read-spec :string
  [spec ^ByteBuffer bb]

  (let [
        valid-encodings {:ascii "US-ASCII"
                         :utf-8 "UTF-8"}

        ;; Destructure fields in the attached meta info
        {length-prefix :struct/length-prefix requested-encoding :struct/string-encoding} (meta spec)

        ;; Read out the length of the string
        length (read-spec length-prefix bb)

        ;; Create a temp buffer to hold the bytes before turning it into a java string
        buffer (byte-array length)
        ]

    ;; Read the string bytes into the buffer
    (.get bb buffer 0 length)

    (String. buffer (valid-encodings requested-encoding))))


(defn seq->bytes
  [seq]

  ;; Construct a byte array from the contents of...
  (byte-array

   ;; sequence of bytes reduced from individual items in the given sequence
   (reduce
    (fn [accum item]
      (conj accum (byte item))) [] seq)
   ))


(defn bytes->bytebuffer
  [bytes]

  (ByteBuffer/wrap bytes))


(defn seq->bytebuffer
  [seq]
  (-> seq
      seq->bytes
      bytes->bytebuffer))