(ns gd-edit.db-query
  (:require [clojure.string :as string]))


(defn case-insensitive-match
  "Check if str2 can be found in str1"
  [str1 str2]

  (.contains (string/lower-case (str str1)) (string/lower-case (str str2))))

(def ci-match case-insensitive-match)

(defn pair-has-key
  [name]

  ;; given a a pair of items, where the first item is presumably the key
  ;; of some hashmap
  ;; Answer if the key contains a string
  (fn [pair]
    (-> (first pair)
        (string/lower-case)
        (.contains (string/lower-case name)))))

(defn hashmap-has-key
  [name]

  (fn [map]
    (->> (keys map)
         (some (fn [key]
                 (-> (string/lower-case key)
                     (.contains name)))))))

(defmacro qand
  [& xs]

  `(fn [[~'key ~'value]]
    (and ~@xs)))

(defmacro qpred
  [& xs]

  `(fn [[~'key ~'value]]
     ~@xs))

(defn query
  "Given a db (list of maps) and some predicates, return all maps that
  satisfies all predicates "
  [db & predicates]

  (reduce
   (fn [accum query-pred]

     ;; We're walking over the query predicates
     ;; Each time, we'll narrow the accum result further by the current
     ;; query item
     (filter (fn [kv-map]
               (some (fn [kv-pair]
                       ;; Is the query predicate actually another sequence of predicates?
                       (if (not (sequential? query-pred))

                         ;; If not, we assume this is a single predicate
                         ;; Execute it as is
                         (query-pred kv-pair)

                         ;; Otherwise, we have a sequence of predicates that this kv-pair
                         ;; must all satisfy
                         ;; This means we can construct a series of simple predicates
                         ;; and make sure it's checking on the same kv-pair
                         (every? (fn [pred] (pred kv-pair))
                                 query-pred)))
                     kv-map)
               )
             accum))

   db
   predicates))

(defn token->op-fn
  [op-token]

  (cond
    (= op-token "~")
    ci-match

    (= op-token "=")
    =

    (= op-token ">")
    >

    (= op-token "<")
    <

    (= op-token "!=")
    not=))

(defn tokens->query-predicate
  "Given a list of token that represents a query clause, return a predicate suitable for used
  by the query function"
  [[target op query-val & rest]]

  (cond
    (= target "key")
    (qpred ((token->op-fn op) key query-val))

    (= target "value")
    (qpred ((token->op-fn op) value query-val))

    :else
    (qand  (ci-match key target)
           ((token->op-fn op) value query-val))))

;; The outputted query predicates is currently a list of predicates.
;; An item might also be a vector, in which case, a single kv-pair must satisfy all the predicates
(defn query-ast->query-predicates
  [query-ast]

  (loop [ast query-ast
         result-predicates []]

    (cond
      ;; Done walking through the ast?
      (empty? ast)
      result-predicates

      ;; Take a peek at the first item
      (not (sequential? (first ast)))
      ;; If it's not a vector, assume we can just grab 3 items and use that as a clause
      ;; Note that we're not checking any kind of grammar here...
      (recur (drop 3 ast)
             (conj result-predicates (tokens->query-predicate (take 3 ast))))

      ;; If it is a vector, we're going to recurisvely process that node and append the results
      (sequential? (first ast))
      (recur (drop 1 ast)
             (conj result-predicates (query-ast->query-predicates (first ast))
             ))
      )))

(defn tokens->query-ast
  "Given a list of token that represents a query, return a sequence that can be used to run the
  query."
  [tokens]

  ;; Walk through the tokens and place any sub-expressions a expression list
  (loop [ts tokens
         ast-stack [[]]]

    (let [token (first ts)]
      (cond
        ;; Did we exhaust the input tokens?
        (nil? token)
        (do
          ;; We should be left with a single constructed ast node
          ;; If this didn't happen, then there is an un-closed paren in the query string somewhere
          (assert (= (count ast-stack) 1))
          (first ast-stack))

        ;; Opening new expression
        (= token "(")
        (recur (rest ts)
               (conj ast-stack [])) ;; push the new expression onto the stack

        ;; or closing the current expression?
        ;; Move whatever we've collected into the last item of the ast
        ;; Grab the last 2 items, conj the last one into the second to the last one
        (= token ")")
        (let [last-nodes (take-last 2 ast-stack)]
          (recur (rest ts)
                 (conj (drop-last 2 ast-stack)
                       (conj (first last-nodes) (second last-nodes)))))

        ;; Just another token?
        :else
        (recur (rest ts)
               ;; Drop the item in as the last item on the top of the stack
               (assoc ast-stack
                      (dec (count ast-stack))
                      (conj (last ast-stack) token)))
        ))))

(defn query-string->tokens
  [input]
  (into [] (re-seq #"[\(\)\~\>\<=]|(?:\!\=)|\"[^\"]+\"|\w+" input)))

(defn query-string->query-predicates
  [input]

  (-> input
      (query-string->tokens)
      (tokens->query-ast)
      (query-ast->query-predicates)))

#_(def r (time
          (query gd-edit.core/db
                 (tokens->query-predicate ["recordname" "~" "affix"])
                 (tokens->query-predicate ["key" "~" "cold"])
                 (tokens->query-predicate ["levelreq" "=" 74])
                 )))
