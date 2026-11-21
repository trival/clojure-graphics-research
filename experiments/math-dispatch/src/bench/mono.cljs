(ns bench.mono
  "Strategy A: monomorphic functions, what a typed macro would emit once the
  operand types are known at expansion time."
  (:require [bench.types :refer [Vec2 Vec3 Vec4]]))

(defn v2+ [a b] (Vec2. (+ (.-x a) (.-x b)) (+ (.-y a) (.-y b))))
(defn v3+ [a b] (Vec3. (+ (.-x a) (.-x b)) (+ (.-y a) (.-y b)) (+ (.-z a) (.-z b))))
(defn v3- [a b] (Vec3. (- (.-x a) (.-x b)) (- (.-y a) (.-y b)) (- (.-z a) (.-z b))))
(defn v3*s [a s] (Vec3. (* (.-x a) s) (* (.-y a) s) (* (.-z a) s)))
(defn s*v3 [s a] (Vec3. (* s (.-x a)) (* s (.-y a)) (* s (.-z a))))
(defn v4*v4 [a b] (Vec4. (* (.-x a) (.-x b)) (* (.-y a) (.-y b)) (* (.-z a) (.-z b)) (* (.-w a) (.-w b))))

(defn v3-add-scaled-f64!
  "out = a + b * s on Float64Array(3) storage, no allocation, no boxed fields."
  [out a b s]
  (aset out 0 (+ (aget a 0) (* (aget b 0) s)))
  (aset out 1 (+ (aget a 1) (* (aget b 1) s)))
  (aset out 2 (+ (aget a 2) (* (aget b 2) s)))
  out)

(defn v3-add-scaled!
  "out = a + b * s, no allocation."
  [out a b s]
  (set! (.-x out) (+ (.-x a) (* (.-x b) s)))
  (set! (.-y out) (+ (.-y a) (* (.-y b) s)))
  (set! (.-z out) (+ (.-z a) (* (.-z b) s)))
  out)
