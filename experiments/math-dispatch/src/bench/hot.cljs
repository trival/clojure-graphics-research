(ns bench.hot
  "Render-loop workloads for the #cpu prototype. One variant per process:
  `node out/bench/hot.mjs <workload> <variant>`; add `--trace-gc` to node to
  count collections.

  mix:       out = a*(1-t) + b*t, one vec3 result per iteration
  particles: 10,000 particles per frame; vel += g*dt; vel *= damping;
             pos += vel*dt (ns per particle step)"
  (:require [bench.types :refer [Vec3]]
            [bench.mono :as mono]
            [bench.disp-b]
            [bench.disp-cpu]
            [bench.harness :refer [measure]])
  (:require-macros [bench.cpu :refer [cpu]]
                   [bench.typed :refer [defop]]
                   [bench.macros :refer [with-ops]]))

;; ---------------------------------------------------------------- mix

(def ^:private fa (js/Float64Array.of 1 2 3))
(def ^:private fb (js/Float64Array.of 3 2 1))
(def ^:private ca (Vec3. 1 2 3))
(def ^:private cb (Vec3. 3 2 1))

(defn mix-cpu! [out a b t]
  #cpu (=! out (+ (* ^vec3 a (- 1 ^f32 t)) (* ^vec3 b t))))

(defn mix-cpu-fallback! [out a b t]
  #cpu (=! out (+ (* a (- 1 ^f32 t)) (* ^vec3 b t))))

(defn mix-hand! [out a b t]
  (let [s (- 1 t)]
    (aset out 0 (+ (* (aget a 0) s) (* (aget b 0) t)))
    (aset out 1 (+ (* (aget a 1) s) (* (aget b 1) t)))
    (aset out 2 (+ (* (aget a 2) s) (* (aget b 2) t)))
    out))

(defop mix-typed ^vec3 [^vec3 a ^vec3 b ^f32 t]
  (let [s (- 1 t)] (+ (* a s) (* b t))))

(with-ops bench.disp-b
  (defn mix-gl [a b t] (+ (* a (+ 1 (* -1 t))) (* b t))))

(defn- mix-into [f]
  (fn [n]
    (let [out (js/Float64Array. 3)]
      (loop [i 0 acc 0]
        (if (< i n)
          (do (f out fa fb (* i 1e-7)) (recur (inc i) (+ acc (aget out 0))))
          acc)))))

(defn- mix-alloc [f]
  (fn [n]
    (loop [i 0 acc 0]
      (if (< i n) (recur (inc i) (+ acc (.-x (f ca cb (* i 1e-7))))) acc))))

;; ---------------------------------------------------------------- particles

(def ^:private np 10000)
(def ^:private dt 0.016)
(def ^:private damping 0.99)

(defn- f64-particles []
  (let [ps #js [] vs #js []]
    (dotimes [i np]
      (.push ps (js/Float64Array.of (mod i 100) 0 (js/Math.floor (/ i 100))))
      (.push vs (js/Float64Array.of 0 (mod i 7) 1)))
    #js [ps vs]))

(defn- class-particles []
  (let [ps #js [] vs #js []]
    (dotimes [i np]
      (.push ps (Vec3. (mod i 100) 0 (js/Math.floor (/ i 100))))
      (.push vs (Vec3. 0 (mod i 7) 1)))
    #js [ps vs]))

(defn- run-frames
  "Runs n/np frames of step over particle arrays; checksum from particle 0."
  [make step get-y]
  (fn [n]
    (let [pv (make) ps (aget pv 0) vs (aget pv 1) frames (/ n np)]
      (dotimes [_ frames]
        (dotimes [i np] (step ps vs i)))
      (get-y (aget ps 0)))))

(defn- step-cpu [ps vs i]
  (let [p (aget ps i) v (aget vs i)]
    (cpu (+=! ^vec3 v (* (vec3 0 -9.81 0) ^f32 dt))
         (=! v (* v ^f32 damping))
         (+=! ^vec3 p (* v dt)))))

(defn- step-hand [ps vs i]
  (let [p (aget ps i) v (aget vs i)]
    (aset v 1 (+ (aget v 1) (* -9.81 dt)))
    (aset v 0 (* (aget v 0) damping))
    (aset v 1 (* (aget v 1) damping))
    (aset v 2 (* (aget v 2) damping))
    (aset p 0 (+ (aget p 0) (* (aget v 0) dt)))
    (aset p 1 (+ (aget p 1) (* (aget v 1) dt)))
    (aset p 2 (+ (aget p 2) (* (aget v 2) dt)))))

(def ^:private gravity (Vec3. 0 -9.81 0))

(with-ops bench.disp-b
  (defn- step-gl [ps vs i]
    (let [v (* (+ (aget vs i) (* gravity dt)) damping)]
      (aset vs i v)
      (aset ps i (+ (aget ps i) (* v dt))))))

(defn- step-mono-alloc [ps vs i]
  (let [v (mono/v3*s (mono/v3+ (aget vs i) (mono/v3*s gravity dt)) damping)]
    (aset vs i v)
    (aset ps i (mono/v3+ (aget ps i) (mono/v3*s v dt)))))

(defn- step-class-inplace
  "Like Scala's addSelf / mulSelf on Vec3 class fields."
  [ps vs i]
  (let [p (aget ps i) v (aget vs i)]
    (set! (.-y v) (+ (.-y v) (* -9.81 dt)))
    (set! (.-x v) (* (.-x v) damping))
    (set! (.-y v) (* (.-y v) damping))
    (set! (.-z v) (* (.-z v) damping))
    (set! (.-x p) (+ (.-x p) (* (.-x v) dt)))
    (set! (.-y p) (+ (.-y p) (* (.-y v) dt)))
    (set! (.-z p) (+ (.-z p) (* (.-z v) dt)))))

;; ---------------------------------------------------------------- main

(def ^:private variants
  {"mix" {"cpu" (mix-into mix-cpu!)
          "hand" (mix-into mix-hand!)
          "cpu-fallback" (mix-into mix-cpu-fallback!)
          "typed-alloc" (mix-alloc mix-typed)
          "gl" (mix-alloc mix-gl)}
   "particles" {"cpu" (run-frames f64-particles step-cpu #(aget % 1))
                "hand" (run-frames f64-particles step-hand #(aget % 1))
                "class-inplace" (run-frames class-particles step-class-inplace #(.-y %))
                "mono-alloc" (run-frames class-particles step-mono-alloc #(.-y %))
                "gl" (run-frames class-particles step-gl #(.-y %))}})

(defn -main []
  (let [[w v] (.slice js/process.argv 2)]
    (measure (str w " " v) (get-in variants [w v]) 5000000)))

(-main)
