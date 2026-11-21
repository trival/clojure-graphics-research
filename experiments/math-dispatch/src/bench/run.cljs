(ns bench.run
  "Dispatch-cost benchmarks. Same workload source, compiled against core JS
  operators and strategy B (typeof + method). Strategy C (kind table) was
  measured too and removed; see the research document §3."
  (:require [bench.types :as t :refer [Vec2 Vec3 Vec4 Mat4]]
            [bench.disp-b :as b]
            [bench.mono :as mono]
            [bench.harness :refer [measure]])
  (:require-macros [bench.macros :refer [with-ops num?]]))

;; ---------------------------------------------------------------- W1 scalar

(defn w1-plain [n]
  (loop [i 0 acc 0]
    (if (< i n)
      (let [t (* i 1e-7)]
        (recur (inc i) (+ acc (* t t (+ 3 (* -2 t))))))
      acc)))

(with-ops bench.disp-b
  (defn w1-b [n]
    (loop [i 0 acc 0]
      (if (< i n)
        (let [t (* i 1e-7)]
          (recur (inc i) (+ acc (* t t (+ 3 (* -2 t))))))
        acc))))

;; ---------------------------------------------------------------- W2 vec3

(def ^:private v (Vec3. 1 2 3))

(defn w2-mono-alloc [n]
  (loop [i 0 acc (Vec3. 0 0 0)]
    (if (< i n) (recur (inc i) (mono/v3+ acc (mono/v3*s v 0.001))) (.-x acc))))

(defn w2-mono-out [n]
  (let [acc (Vec3. 0 0 0)]
    (dotimes [_ n] (mono/v3-add-scaled! acc acc v 0.001))
    (.-x acc)))

(defn w2-mono-out-f64 [n]
  (let [acc (js/Float64Array. 3)
        vv (js/Float64Array.from #js [1 2 3])]
    (dotimes [_ n] (mono/v3-add-scaled-f64! acc acc vv 0.001))
    (aget acc 0)))

(with-ops bench.disp-b
  (defn w2-b [n]
    (loop [i 0 acc (Vec3. 0 0 0)]
      (if (< i n) (recur (inc i) (+ acc (* v 0.001))) (.-x acc)))))

;; ---------------------------------------------------------------- W3 mixed site

(def ^:private ident (Mat4. (js/Float64Array.from #js [1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1])))

(def ^:private pairs
  "One mul site fed six operand-kind combinations, interleaved."
  (let [as #js [] bs #js []]
    (dotimes [i 600]
      (case (mod i 6)
        0 (do (.push as 1.5) (.push bs 2.0))
        1 (do (.push as (Vec2. 1 2)) (.push bs 0.5))
        2 (do (.push as (Vec3. 1 2 3)) (.push bs 0.5))
        3 (do (.push as (Vec4. 1 2 3 4)) (.push bs (Vec4. 2 2 2 2)))
        4 (do (.push as 2.0) (.push bs (Vec3. 1 1 1)))
        5 (do (.push as ident) (.push bs (Vec4. 1 2 3 4)))))
    #js [as bs]))

(defn- first-comp [r] (if (num? r) r (.-x r)))

(with-ops bench.disp-b
  (defn w3-b [n]
    (let [as (aget pairs 0) bs (aget pairs 1)]
      (loop [i 0 acc 0]
        (if (< i n)
          (let [j (mod i 600)]
            (recur (inc i) (js* "~{} + ~{}" acc (first-comp (* (aget as j) (aget bs j))))))
          acc)))))

;; ---------------------------------------------------------------- W4 shader expressions

(with-ops bench.disp-b
  (defn fade-b [t] (* t t (+ 3 (* -2 t)))))

(defn w4-expr [n]
  (let [tt (t/sym "t" "f32")]
    (loop [i 0 acc 0]
      (if (< i n) (recur (inc i) (+ acc (.-length (.-args (fade-b tt))))) acc))))

(defn w4-cpu [n]
  (loop [i 0 acc 0]
    (if (< i n) (recur (inc i) (+ acc (fade-b (* i 1e-7)))) acc)))

;; ---------------------------------------------------------------- main

(defn -main []
  (let [ns 10000000 nv 5000000 nm 5000000 ne 1000000]
    (println "-- W1 scalar fade, clean feedback")
    (measure "W1 plain JS operators" w1-plain ns)
    (measure "W1 B typeof+method" w1-b ns)
    (println "-- W2 vec3 acc + v*0.001")
    (measure "W2 mono, allocating" w2-mono-alloc nv)
    (measure "W2 mono, out-param (no alloc)" w2-mono-out nv)
    (measure "W2 mono, out-param Float64Array" w2-mono-out-f64 nv)
    (measure "W2 B typeof+method" w2-b nv)
    (println "-- W3 one mul site, 6 operand kinds")
    (measure "W3 B typeof+method" w3-b nm)
    (println "-- W1/W2 again, after W3 polluted add/mul feedback")
    (measure "W1 B typeof+method (polluted)" w1-b ns)
    (measure "W2 B typeof+method (polluted)" w2-b nv)
    (println "-- W4 one generic fade, CPU and shader expression")
    (measure "W4 fade on numbers via B" w4-cpu ns)
    (measure "W4 fade building an AST (per call)" w4-expr ne)))

(-main)
