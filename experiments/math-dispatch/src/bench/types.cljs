(ns bench.types
  "CPU value classes and a shader-expression node, each carrying the methods
  for method-based dispatch (strategy B) and a kind tag `k` for table dispatch
  (strategy C, since removed). Kinds: 0 number, 1 vec2, 2 vec3, 3 vec4, 4 mat4,
  5 expr."
  (:require-macros [bench.macros :refer [num? inst?]]))

(declare Expr lift)

(defclass Vec2
  (field x) (field y) (field k 1)
  (constructor [this x0 y0] (set! x x0) (set! y y0))
  Object
  (add [this b]
       (cond (num? b) (Vec2. (+ x b) (+ y b))
             (inst? Vec2 b) (Vec2. (+ x (.-x b)) (+ y (.-y b)))
             :else (.add (lift this) b)))
  (radd [this a] (Vec2. (+ a x) (+ a y)))
  (mul [this b]
       (cond (num? b) (Vec2. (* x b) (* y b))
             (inst? Vec2 b) (Vec2. (* x (.-x b)) (* y (.-y b)))
             :else (.mul (lift this) b)))
  (rmul [this a] (Vec2. (* a x) (* a y))))

(defclass Vec3
  (field x) (field y) (field z) (field k 2)
  (constructor [this x0 y0 z0] (set! x x0) (set! y y0) (set! z z0))
  Object
  (add [this b]
       (cond (num? b) (Vec3. (+ x b) (+ y b) (+ z b))
             (inst? Vec3 b) (Vec3. (+ x (.-x b)) (+ y (.-y b)) (+ z (.-z b)))
             :else (.add (lift this) b)))
  (radd [this a] (Vec3. (+ a x) (+ a y) (+ a z)))
  (mul [this b]
       (cond (num? b) (Vec3. (* x b) (* y b) (* z b))
             (inst? Vec3 b) (Vec3. (* x (.-x b)) (* y (.-y b)) (* z (.-z b)))
             :else (.mul (lift this) b)))
  (rmul [this a] (Vec3. (* a x) (* a y) (* a z))))

(defclass Vec4
  (field x) (field y) (field z) (field w) (field k 3)
  (constructor [this x0 y0 z0 w0] (set! x x0) (set! y y0) (set! z z0) (set! w w0))
  Object
  (add [this b]
       (cond (num? b) (Vec4. (+ x b) (+ y b) (+ z b) (+ w b))
             (inst? Vec4 b) (Vec4. (+ x (.-x b)) (+ y (.-y b)) (+ z (.-z b)) (+ w (.-w b)))
             :else (.add (lift this) b)))
  (radd [this a] (Vec4. (+ a x) (+ a y) (+ a z) (+ a w)))
  (mul [this b]
       (cond (num? b) (Vec4. (* x b) (* y b) (* z b) (* w b))
             (inst? Vec4 b) (Vec4. (* x (.-x b)) (* y (.-y b)) (* z (.-z b)) (* w (.-w b)))
             :else (.mul (lift this) b)))
  (rmul [this a] (Vec4. (* a x) (* a y) (* a z) (* a w))))

(defn m4*v4
  "Column-major mat4 times vec4, allocating."
  [m v]
  (let [e (.-e m) x (.-x v) y (.-y v) z (.-z v) w (.-w v)]
    (Vec4. (+ (* (aget e 0) x) (* (aget e 4) y) (* (aget e 8) z) (* (aget e 12) w))
           (+ (* (aget e 1) x) (* (aget e 5) y) (* (aget e 9) z) (* (aget e 13) w))
           (+ (* (aget e 2) x) (* (aget e 6) y) (* (aget e 10) z) (* (aget e 14) w))
           (+ (* (aget e 3) x) (* (aget e 7) y) (* (aget e 11) z) (* (aget e 15) w)))))

(defclass Mat4
  (field e) (field k 4)
  (constructor [this e0] (set! e e0))
  Object
  (add [this b] (throw (js/Error. "mat4 + not in this benchmark")))
  (radd [this a] (throw (js/Error. "mat4 + not in this benchmark")))
  (mul [this b]
       (cond (inst? Vec4 b) (m4*v4 this b)
             :else (.mul (lift this) b)))
  (rmul [this a] (throw (js/Error. "scalar * mat4 not in this benchmark"))))

(defn type-of
  "WGSL type name of a CPU value or expression."
  [v]
  (cond (num? v) "f32"
        (inst? Vec2 v) "vec2f"
        (inst? Vec3 v) "vec3f"
        (inst? Vec4 v) "vec4f"
        (inst? Mat4 v) "mat4x4f"
        :else (.-type v)))

(defn- result-type [ta tb]
  (cond (= ta tb) ta
        (= ta "f32") tb
        (= tb "f32") ta
        (and (= ta "mat4x4f") (= tb "vec4f")) "vec4f"
        :else (throw (js/Error. (str "no operator for " ta " and " tb)))))

(defclass Expr
  (field op) (field type) (field args) (field k 5)
  (constructor [this op0 type0 args0] (set! op op0) (set! type type0) (set! args args0))
  Object
  (add [this b] (Expr. "+" (result-type type (type-of b)) [this (lift b)]))
  (radd [this a] (Expr. "+" (result-type (type-of a) type) [(lift a) this]))
  (mul [this b] (Expr. "*" (result-type type (type-of b)) [this (lift b)]))
  (rmul [this a] (Expr. "*" (result-type (type-of a) type) [(lift a) this])))

(defn lift
  "A CPU value as a literal expression node; expressions pass through."
  [v]
  (if (inst? Expr v) v (Expr. "lit" (type-of v) [v])))

(defn sym
  "A named shader input, e.g. a uniform or varying."
  [name type]
  (Expr. "sym" type [name]))
