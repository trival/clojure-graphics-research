(ns bench.typed-use
  "defop prototype in use, benchmarked against hand-written mono code and
  generic dispatch (strategy B) on the same expression."
  (:require [bench.types :refer [Vec3]]
            [bench.mono]
            [bench.disp-b]
            [bench.harness :refer [measure]])
  (:require-macros [bench.typed :refer [defop]]
                   [bench.macros :refer [with-ops]]))

(defop fade ^f32 [^f32 t]
  (* t t (- 3 (* 2 t))))

(defop mix3 ^vec3 [^vec3 a ^vec3 b ^f32 t]
  (let [s (- 1 t)]
    (+ (* a s) (* b t))))

(defop untyped-scale [v ^f32 s]
  (* v s))

(with-ops bench.disp-b
  (defn mix3-generic [a b t] (+ (* a (+ 1 (* -1 t))) (* b t))))

(defn mix3-hand [a b t]
  (let [s (- 1 t)]
    (bench.mono/v3+ (bench.mono/v3*s a s) (bench.mono/v3*s b t))))

(def ^:private a (Vec3. 1 2 3))
(def ^:private b (Vec3. 3 2 1))

(defn- loop-with [f]
  (fn [n]
    (loop [i 0 acc 0]
      (if (< i n) (recur (inc i) (+ acc (.-x (f a b (* i 1e-7))))) acc))))

(defn -main
  "Without argument: prints the generated WGSL. With `typed`, `hand` or
  `generic`: measures that variant (one per process, since closures from
  `loop-with` share V8 type feedback)."
  []
  (case (aget js/process.argv 2)
    "typed" (measure "mix3 defop (typed macro)" (loop-with mix3) 5000000)
    "hand" (measure "mix3 hand-written mono" (loop-with mix3-hand) 5000000)
    "generic" (measure "mix3 generic B" (loop-with mix3-generic) 5000000)
    (do (println fade-wgsl)
        (println mix3-wgsl)
        (println "untyped-scale (vec3 * 2):" (.-x (untyped-scale a 2))))))

(-main)
