(ns bench.combos
  "Per operand combination: dedicated monomorphic call vs generic dispatch,
  each at its own call site. Run without arguments to measure everything in one
  process (shared dispatch feedback, as in a real program), or with
  `<combo> <variant>` to measure one loop in a fresh process."
  (:require [bench.types :as t :refer [Vec2 Vec3 Vec4 Mat4]]
            [bench.disp-b]
            [bench.mono :as mono]
            [bench.harness :refer [measure]])
  (:require-macros [bench.macros-combo :refer [defcombo]]))

(defn- arr [f] (let [a #js []] (dotimes [i 1024] (.push a (f i))) a))
(defn- r01 [i] (/ (mod (* i 7919) 1000) 1000))

(def nums-a (arr #(+ 1 (r01 %))))
(def nums-b (arr #(+ 2 (r01 (+ % 3)))))
(def v2s-a (arr #(Vec2. (r01 %) 1)))
(def v2s-b (arr #(Vec2. 1 (r01 %))))
(def v3s-a (arr #(Vec3. (r01 %) 1 2)))
(def v3s-b (arr #(Vec3. 1 (r01 %) 2)))
(def v4s-a (arr #(Vec4. (r01 %) 1 2 3)))
(def v4s-b (arr #(Vec4. 1 (r01 %) 2 3)))
(def m4s (arr (fn [i] (Mat4. (js/Float64Array.from #js [1 0 0 0 0 1 0 0 0 0 1 0 (r01 i) 0 0 1])))))

(defn- f32* [a b] (js* "~{} * ~{}" a b))
(defn- num-id [x] x)
(defn- get-x [r] (.-x r))

(defcombo f32*f32   mul f32*      num-id nums-a nums-b)
(defcombo vec2+vec2 add mono/v2+  get-x  v2s-a v2s-b)
(defcombo vec3+vec3 add mono/v3+  get-x  v3s-a v3s-b)
(defcombo vec3*f32  mul mono/v3*s get-x  v3s-a nums-a)
(defcombo f32*vec3  mul mono/s*v3 get-x  nums-a v3s-a)
(defcombo vec4*vec4 mul mono/v4*v4 get-x v4s-a v4s-b)
(defcombo mat4*vec4 mul t/m4*v4   get-x  m4s v4s-a)

(def table
  [["f32*f32" f32*f32-mono f32*f32-b]
   ["vec2+vec2" vec2+vec2-mono vec2+vec2-b]
   ["vec3+vec3" vec3+vec3-mono vec3+vec3-b]
   ["vec3*f32" vec3*f32-mono vec3*f32-b]
   ["f32*vec3" f32*vec3-mono f32*vec3-b]
   ["vec4*vec4" vec4*vec4-mono vec4*vec4-b]
   ["mat4*vec4" mat4*vec4-mono mat4*vec4-b]])

(def n 5000000)

(defn- build-shader-exprs!
  "Simulates shader construction at setup: the same generic add/mul called
  with expression operands (expr+expr, expr*f32, vec3*expr, f32*expr)."
  []
  (let [u (t/sym "u" "vec3f") s (t/sym "s" "f32")]
    (dotimes [_ 20000]
      (bench.disp-b/add (bench.disp-b/mul u 0.5) (bench.disp-b/mul (Vec3. 1 2 3) s))
      (bench.disp-b/mul 2 s)
      (bench.disp-b/add s 1))))

(defn -main
  "No arguments: all combos in one process. `<combo> <mono|b>`: one loop in a
  fresh process. A third argument `expr` first runs shader construction
  through the same generic functions."
  []
  (let [[combo variant pollute] (.slice js/process.argv 2)]
    (when (= pollute "expr") (build-shader-exprs!))
    (if combo
      (let [row (first (filter #(= combo (first %)) table))
            idx (get {"mono" 1 "b" 2} variant)]
        (measure (str combo " " variant) (nth row idx) n))
      (doseq [[label mono-f b-f] table]
        (measure (str label " mono") mono-f n)
        (measure (str label " B") b-f n)))))

(-main)
