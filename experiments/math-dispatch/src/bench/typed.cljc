(ns bench.typed
  "Minimal typed-form prototype: bottom-up type inference over + - * on f32
  and vec3, `let`, parameter hints. Emits a CPU function with every operator
  resolved to its monomorphic implementation, plus a WGSL function string.
  Unknown operand types fall back to generic runtime dispatch."
  (:require [clojure.string :as str]))

(def ^:private impls
  "[op type-a type-b] -> [result-type cpu-emitter]"
  {['+ :f32 :f32]   [:f32 (fn [a b] (list 'js* "(~{} + ~{})" a b))]
   ['- :f32 :f32]   [:f32 (fn [a b] (list 'js* "(~{} - ~{})" a b))]
   ['* :f32 :f32]   [:f32 (fn [a b] (list 'js* "(~{} * ~{})" a b))]
   ['+ :vec3 :vec3] [:vec3 (fn [a b] (list 'bench.mono/v3+ a b))]
   ['- :vec3 :vec3] [:vec3 (fn [a b] (list 'bench.mono/v3- a b))]
   ['* :vec3 :f32]  [:vec3 (fn [a b] (list 'bench.mono/v3*s a b))]
   ['* :f32 :vec3]  [:vec3 (fn [a b] (list 'bench.mono/s*v3 a b))]})

(def ^:private generic {'+ 'bench.disp-b/add '* 'bench.disp-b/mul})

(def ^:private wgsl-type {:f32 "f32" :vec3 "vec3f"})

(defn- hint [sym] (some-> sym meta :tag name keyword))

(declare infer)

(defn- infer-op [op args env]
  (let [[a & more] (map #(infer % env) args)]
    (reduce
     (fn [[ta ca wa] [tb cb wb]]
       (if-let [[tr emit] (get impls [op ta tb])]
         [tr (emit ca cb) (str "(" wa " " op " " wb ")")]
         (if (or (= ta :unknown) (= tb :unknown))
           [:unknown (list (generic op) ca cb) nil]
           (throw (ex-info (str "no " op " for " (name ta) " and " (name tb)) {})))))
     a more)))

(def ^:private stmts
  "WGSL `let` statements collected while inferring one defop body."
  (atom []))

(defn- infer-let [bindings body env]
  (loop [bs (partition 2 bindings) env env cpu []]
    (if-let [[s e] (first bs)]
      (let [[t c w] (infer e env)]
        (swap! stmts conj (str "let " s " = " w ";"))
        (recur (rest bs) (assoc env s t) (conj cpu s c)))
      (let [[t c w] (infer body env)]
        [t (list 'let cpu c) w]))))

(defn- wgsl-num [n]
  (let [s (str n)] (if (str/includes? s ".") s (str s ".0"))))

(defn infer
  "Returns [type cpu-form wgsl-expr] for form under env {sym type}; `let`
  bindings are appended to `stmts`."
  [form env]
  (cond
    (number? form) [:f32 form (wgsl-num form)]
    (symbol? form) [(get env form :unknown) form (str form)]
    (and (seq? form) (= 'let (first form))) (infer-let (second form) (nth form 2) env)
    (and (seq? form) (contains? #{'+ '- '*} (first form))) (infer-op (first form) (rest form) env)
    :else (throw (ex-info (str "unsupported form " (pr-str form)) {}))))

(defmacro defop
  "Typed definition: `(defop name ^type [^type p ...] body)`. Defines `name`
  (CPU, monomorphic calls) and `name-wgsl` (WGSL source)."
  [fname params body]
  (reset! stmts [])
  (let [env (into {} (map (fn [p] [p (or (hint p) :unknown)]) params))
        [t cpu wgsl] (infer body env)
        ret (or (hint params) t)
        sig (str/join ", " (map #(str % ": " (wgsl-type (env %))) params))
        lines (concat [(str "fn " fname "(" sig ") -> " (wgsl-type ret) " {")]
                      (map #(str "  " %) @stmts)
                      [(str "  return " wgsl ";") "}"])]
    `(do (defn ~fname ~(vec params) ~cpu)
         (def ~(symbol (str fname "-wgsl")) ~(str/join "\n" lines)))))
