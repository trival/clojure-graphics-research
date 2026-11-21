(ns bench.disp-cpu
  "CPU-only generic fallback for `#cpu` operands of unknown type. Numbers and
  Float64Array vectors only, no expression branch, so its type feedback never
  sees shader expressions. Allocates its results."
  (:require-macros [bench.macros :refer [num?]]))

(defn- zip [a b f]
  (let [n (.-length a) out (js/Float64Array. n)]
    (dotimes [i n] (aset out i (f (aget a i) (aget b i))))
    out))

(defn- bcast [v s f]
  (let [n (.-length v) out (js/Float64Array. n)]
    (dotimes [i n] (aset out i (f (aget v i) s)))
    out))

(defn- op [a b f]
  (if (num? a)
    (if (num? b) (f a b) (zip (.fill (js/Float64Array. (.-length b)) a) b f))
    (if (num? b) (bcast a b f) (zip a b f))))

(defn add [a b] (op a b (fn [x y] (+ x y))))
(defn sub [a b] (op a b (fn [x y] (- x y))))
(defn mul [a b] (op a b (fn [x y] (* x y))))

(defn dot [a b]
  (loop [i 0 acc 0]
    (if (< i (.-length a)) (recur (inc i) (+ acc (* (aget a i) (aget b i)))) acc)))

(defn assign!
  "Copies vector v into out; returns out."
  [out v]
  (.set out v)
  out)

(defn add-assign!
  "out += v (vector or scalar); returns out."
  [out v]
  (dotimes [i (.-length out)]
    (aset out i (+ (aget out i) (if (num? v) v (aget v i)))))
  out)
