(ns bench.macros
  (:require [clojure.walk :as w]))

(defn- binarize [op args]
  (reduce (fn [acc x] (list op acc x)) (first args) (rest args)))

(defn rewrite-ops
  "Replaces `+` / `*` calls in `form` with calls to `<ns>/add` / `<ns>/mul`,
  left-folding variadic calls into binary ones."
  [ns-sym form]
  (let [op-map {'+ (symbol (name ns-sym) "add")
                '* (symbol (name ns-sym) "mul")}]
    (w/postwalk
     (fn [x]
       (if (and (seq? x) (contains? op-map (first x)))
         (binarize (op-map (first x)) (rest x))
         x))
     form)))

(defmacro num?
  "`typeof x === 'number'`, tagged boolean so squint emits no truth_ call."
  [x]
  (with-meta (list 'js* "(typeof ~{} === 'number')" x) {:tag 'boolean}))

(defmacro inst?
  "`x instanceof c`, tagged boolean; squint's `instance?` emits an IIFE."
  [c x]
  (with-meta (list 'js* "(~{} instanceof ~{})" x c) {:tag 'boolean}))

(defmacro with-ops
  "Evaluates body with `+` and `*` routed to the given dispatch namespace."
  [ns-sym & body]
  `(do ~@(map #(rewrite-ops ns-sym %) body)))
