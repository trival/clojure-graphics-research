(ns bench.cpu
  "`#cpu` / `(cpu …)` prototype: bare operators for render-loop code.

  Every vector value is split at compile time into one scalar JS expression per
  component (scalar replacement), so a typed body allocates nothing and calls
  nothing. Vectors are Float64Array storage at the boundary: inputs are read
  with `aget`, results are written by the statement forms

    (=! out expr)    out = expr
    (+=! out expr)   out = out + expr

  Types: `^f32` / `^vec2` / `^vec3` / `^vec4` hints on any occurrence of a
  symbol apply to all its occurrences in the form; `(vec3 x y z)`, number
  literals and `let` are inferred. Operators: + - * (variadic), dot.
  A node with an operand of unknown type compiles to a call into the CPU-only
  generic namespace `bench.disp-cpu`, with a compile-time warning."
  (:require [clojure.walk :as w]))

(def ^:private vec-n {:vec2 2 :vec3 3 :vec4 4})

(def ^:private binds
  "Scalar temporaries [sym expr] collected while compiling one form."
  (atom []))

(def ^:private warned (atom #{}))

(defn- tmp! [prefix e]
  (let [s (gensym prefix)]
    (swap! binds conj s e)
    s))

(defn- atomic? [e]
  (or (symbol? e) (number? e)
      (and (seq? e) (= 'aget (first e)) (symbol? (second e)) (number? (nth e 2)))))

(defn- atomize [e] (if (atomic? e) e (tmp! "s" e)))

(defn- collect-hints [form]
  (let [hints (atom {})]
    (w/postwalk (fn [x]
                  (when-let [t (and (symbol? x) (some-> x meta :tag name keyword))]
                    (swap! hints assoc (with-meta x nil) t))
                  x)
                form)
    @hints))

(defn- scalar [e] {:t :f32 :e e})
(defn- vect [t cs] {:t t :c (vec cs)})
(defn- unknown [e] {:t :unknown :e e})

(defn- runtime-value
  "A compiled value as a runtime JS value (allocates for vectors)."
  [{:keys [t e c]}]
  (if (vec-n t) (apply list 'js/Float64Array.of c) e))

(defn- js-op [op a b] (list 'js* (str "(~{} " op " ~{})") a b))

(def ^:private fallback {'+ 'bench.disp-cpu/add '- 'bench.disp-cpu/sub
                         '* 'bench.disp-cpu/mul 'dot 'bench.disp-cpu/dot})

(declare compile-expr)

(defn- binary [op a b]
  (let [{ta :t} a {tb :t} b
        na (vec-n ta) nb (vec-n tb)]
    (cond
      (or (= ta :unknown) (= tb :unknown))
      (unknown (list (fallback op) (runtime-value a) (runtime-value b)))

      (= op 'dot)
      (if (and na (= ta tb))
        (scalar (reduce #(js-op "+" %1 %2) (map #(js-op "*" %1 %2) (:c a) (:c b))))
        (throw (ex-info (str "dot needs two equal vectors, got " (name ta) " and " (name tb)) {})))

      (and (= ta :f32) (= tb :f32)) (scalar (js-op (str op) (:e a) (:e b)))

      (and na (= ta tb)) (vect ta (map #(js-op (str op) %1 %2) (:c a) (:c b)))

      (and na (= tb :f32))
      (let [s (atomize (:e b))] (vect ta (map #(js-op (str op) % s) (:c a))))

      (and nb (= ta :f32))
      (let [s (atomize (:e a))] (vect tb (map #(js-op (str op) s %) (:c b))))

      :else (throw (ex-info (str "no " op " for " (name ta) " and " (name tb)) {})))))

(defn- compile-let [bindings body env]
  (let [env (reduce (fn [env [s e]]
                      (let [r (compile-expr e env)
                            r (case (:t r)
                                :f32 (scalar (atomize (:e r)))
                                :unknown (unknown (tmp! (str s "_") (:e r)))
                                (vect (:t r) (map atomize (:c r))))]
                        (assoc env (with-meta s nil) r)))
                    env (partition 2 bindings))]
    (compile-expr body env)))

(defn compile-expr
  "Compiles form under env {sym compiled-value} to {:t :f32|:vecN|:unknown ...}."
  [form env]
  (cond
    (number? form) (scalar form)

    (symbol? form)
    (let [s (with-meta form nil)]
      (or (get env s)
          (do (when-not (@warned s)
                (swap! warned conj s)
                (println (str "WARNING #cpu: `" s "` has no type, runtime dispatch")))
              (unknown s))))

    (and (seq? form) (= 'let (first form))) (compile-let (second form) (nth form 2) env)

    (and (seq? form) (#{'vec2 'vec3 'vec4} (first form)))
    (vect (keyword (name (first form)))
          (map #(:e (compile-expr % env)) (rest form)))

    (and (seq? form) (#{'+ '- '* 'dot} (first form)))
    (let [[a & more] (map #(compile-expr % env) (rest form))]
      (reduce #(binary (first form) %1 %2) a more))

    :else (throw (ex-info (str "#cpu: unsupported form " (pr-str form)) {}))))

(defn- compile-stmt [form env]
  (let [[op target expr] form]
    (case op
      (=! +=!)
      (let [r (compile-expr expr env)
            self-ref? (some #{target} (tree-seq coll? seq expr))]
        (if (= :unknown (:t r))
          [(list (if (= op '=!) 'bench.disp-cpu/assign! 'bench.disp-cpu/add-assign!) target (:e r))]
          (let [cs (cond->> (:c r) self-ref? (mapv atomize))]
            (map-indexed (fn [i c]
                           (list 'aset target i
                                 (if (= op '+=!) (js-op "+" (list 'aget target i) c) c)))
                         cs))))
      [(runtime-value (compile-expr form env))])))

(defn compile-form
  "Compiles a whole #cpu form to plain code."
  [form]
  (let [hints (collect-hints form)
        env (into {} (map (fn [[s t]]
                            [s (if-let [n (vec-n t)]
                                 (vect t (map #(list 'aget s %) (range n)))
                                 (scalar s))])
                          hints))
        stmts (if (and (seq? form) (= 'do (first form))) (rest form) [form])
        ;; temporaries are scoped per statement: a statement may read what
        ;; the previous one wrote
        compiled (doall (map (fn [stmt]
                               (reset! binds [])
                               (let [out (doall (compile-stmt stmt env))]
                                 (if (seq @binds) `(let ~(vec @binds) ~@out) `(do ~@out))))
                             stmts))]
    `(do ~@compiled)))

(defmacro cpu
  "Compiles the body as #cpu code; see the namespace docstring."
  [& body]
  (compile-form (if (next body) (cons 'do body) (first body))))
