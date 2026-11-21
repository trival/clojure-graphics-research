(ns bench.cpu-check
  "Smoke test of the #cpu prototype: prints results to compare by hand.
  Expected: mix [1.5 2 2.5], pos [0.1 9.9019 0], vel [1 -0.981 0], len2 12,
  untyped [2 4 6]; compiling prints one warning for the untyped `a`."
  (:require [bench.disp-cpu])
  (:require-macros [bench.cpu :refer [cpu]]))

(defn mix! [out a b t]
  #cpu (=! out (+ (* ^vec3 a (- 1 ^f32 t)) (* ^vec3 b t))))

(defn step! [pos vel dt]
  (cpu (+=! ^vec3 vel (* (vec3 0 -9.81 0) ^f32 dt))
       (+=! ^vec3 pos (* vel dt))))

(defn len2 [v] #cpu (let [w (* ^vec3 v 2)] (dot w w)))

(defn untyped! [out a s] #cpu (=! out (* a ^f32 s)))

(let [o (js/Float64Array. 3)
      p (js/Float64Array.of 0 10 0) v (js/Float64Array.of 1 0 0)]
  (mix! o (js/Float64Array.of 1 2 3) (js/Float64Array.of 3 2 1) 0.25)
  (step! p v 0.1)
  (js/console.log "mix" (vec o) "pos" (vec p) "vel" (vec v)
                  "len2" (len2 (js/Float64Array.of 1 1 1))
                  "untyped" (vec (untyped! (js/Float64Array. 3) (js/Float64Array.of 1 2 3) 2))))
