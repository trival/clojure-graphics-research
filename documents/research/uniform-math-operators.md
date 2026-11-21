# Uniform math operators in squint — dispatch cost research

**Status: research with measurements; nothing decided.** Follow-up to
[clojurescript-port-research.md](clojurescript-port-research.md) §6.1–6.2
(tier-1 generic math, `defop`), done in squint (this repo's direction) instead
of shadow-cljs.

**Question.** Can sketch and shader code use bare operators (`+ - * /`, an
in-place `+=` form) that work on every valid operand combination (scalars,
vectors, matrices, CPU values and shader expressions), perhaps inside a `#gl`
form? What does that cost on the CPU, and where?

**Scope of "cost".** Shader generation (building expression trees, emitting
WGSL) runs once per shade and is not a concern; it is measured only for scale.
The concern is **CPU hot paths in the render loop**: per-frame uniform updates,
camera math, per-vertex or per-particle CPU work.

Short answer:

- **Bare operators work only through rewriting.** squint compiles any symbol
  named `+`, `*` or `+=` to a JS infix operator, even with a namespace alias,
  and then emits invalid JS. A `(gl …)` macro or an unqualified `#gl` reader tag
  that rewrites `+` → `m/add` works, and folds variadic calls at compile time.
  The in-place form must be `+=!`; `+=` cannot be a function name (§1).
- **Generic dispatch is always a runtime CPU cost, as you assumed.** Each
  operation does up to two `typeof` tests, a method call on the left operand and
  a short `instanceof` chain on the right one (§3). The type tests themselves
  are cheap. The real cost is what dispatch prevents: once the shared `add` /
  `mul` sees several types, the JIT stops inlining it, and then the intermediate
  vectors it allocates stop being optimized away (§5).
- **Measured on the hot path**: next to a dedicated per-type call (what a static
  compiler emits), generic dispatch costs **0–60 % more per operation at an
  isolated site, up to 3× in a realistic process on V8, and 5–17× on a composite
  vector expression** (§4).
- **Supporting GPU expressions slows CPU code only through shared code.** If
  shader construction and the render loop call the same generic `add`, CPU
  vector ops get 1.5–5× slower, even though the expression branch never runs for
  CPU operands. Separate CPU and expression copies of each helper, emitted by
  the macro, avoid this (§4.3, §5).
- **Compile-time dispatch works in squint macros.** A typed `defop` prototype
  infers types from parameter hints, emits exactly the hand-written monomorphic
  code (equal timings), emits WGSL from the same source, and reports a wrong
  operand combination as a compile error (§6). The limit: the macro only sees
  its own form. Types come from hints, literals and `let`; anything defined
  elsewhere is unknown and falls back to runtime dispatch.

- **A second, CPU-only hot form works and costs nothing (§8).** `#cpu` resolves
  types at compile time and splits every vector into its scalar components,
  writing results into typed-array storage. It matches hand-written scalar code:
  1.9 vs 1.75 ns for a vec3 `mix` (V8), and 16 vs 18 ns per particle step, with
  the same GC count. Generic `#gl` code on the same particle loop takes 78 ns
  and 14× the collections.

Recommendation: `#gl` for shader code and setup, where CPU and GPU values mix;
`#cpu` for render-loop math; `defop` for helpers used on both sides (§7).
Implementation plan and the Scala comparison: §9.

---

## How it was measured

- Code: [experiments/math-dispatch/](../../experiments/math-dispatch/), squint
  0.16.214. Compile with
  `cd experiments/math-dispatch && ../../node_modules/.bin/squint compile src/bench/*.cljs`.
- Runs: `node out/bench/run.mjs`, `node out/bench/combos.mjs` (all in one
  process), `node out/bench/combos.mjs <combo> <mono|b> [expr]` (one loop per
  process), `node out/bench/typed_use.mjs <typed|hand|generic>`,
  `node out/bench/hot.mjs <mix|particles> <variant>` (§8),
  `node out/bench/cpu_check.mjs` (`#cpu` smoke test). Same with `bun`. GC
  counts: `node --trace-gc … | grep -cE "Scavenge|Mark-Compact|Minor"`.
- Kept in the repo: the code that supports further work and reproduces the
  tables. The one-off probes (§1, §2, `&env` in §8.1) and strategy C (§3) were
  removed once their results were recorded here, with the code that matters
  quoted. The C columns in §4 therefore cannot be rerun from the current tree.
- Engines: node v24.16.0 (V8, as in Chrome) and bun 1.4.4 (JavaScriptCore, as in
  Safari). Firefox not measured.
- Machine: Intel i7-1165G7, Linux 7.0, on mains power, no other load.
- Each figure: 3 warmup rounds, median of 7 timed rounds, divided by the loop
  count (5–10 M), so a figure is **ns per loop iteration**, including the
  checksum add and the operand array reads. Every variant of one workload is
  compiled from the same source by a macro and differs only in the call at the
  operation site. Reruns varied by about ±10 %.

---

## 1. Operators in squint

Tested with small probe files (removed; the forms tested are in the table):

| Source                                                             | Emitted JS                                       |
| ------------------------------------------------------------------ | ------------------------------------------------ |
| `(+ a b c)`, `(* a b)`                                             | `(a + b + c)`, `(a * b)`: inline, no call        |
| `(:refer-clojure :exclude [+])` + own `(defn + …)`, then `(+ a b)` | still `(a + b)`: the own `+` is ignored silently |
| `(m/+ a b)` from another namespace                                 | `(a m/+ b)`: **invalid JS**                      |
| `(reduce m/+ 0 xs)` (value position)                               | `reduce(m._PLUS_, …)`: works                     |
| `(v/+= x y)`                                                       | `(x v/+= y)`: **invalid JS**                     |
| `(v/+=! x y)`, `(v/*=! x y)`, `(v/add! x y)`                       | normal function calls                            |

So a library cannot export callable `+`, `*` or `+=`. Two working ways to get
bare operators back:

```clojure
(defn fade-macro [t] (gl (* t t (+ 3 (* -2 t)))))   ; macro
(defn fade-tag   [t] #gl (* t t (+ 3 (* -2 t))))    ; reader tag
```

Both compile to the same JS:

```js
m_DOT_ops.mul(m_DOT_ops.mul(t, t), m_DOT_ops.add(3, m_DOT_ops.mul(-2, t)));
```

The probe's macro and reader, in full:

```clojure
;; src/m/macros.cljc
(ns m.macros (:require [clojure.walk :as w]))

(def op-map '{+ m.ops/add * m.ops/mul})

(defn- binarize [op args]
  (reduce (fn [acc x] (list op acc x)) (first args) (rest args)))

(defn rewrite [form]
  (w/postwalk (fn [x]
                (if (and (seq? x) (contains? op-map (first x)))
                  (binarize (op-map (first x)) (rest x))
                  x))
              form))

(defmacro gl [& body] `(do ~@(map rewrite body)))

;; src/m/readers.cljc
(ns m.readers (:require [m.macros :as mm]))
(defn gl [form] (mm/rewrite form))

;; src/data_readers.cljc
{gl m.readers/gl}

;; use
(ns e1.use-macro (:require [m.ops]) (:require-macros [m.macros :refer [gl]]))
```

The same rewrite, parameterized by the target namespace, lives on as `with-ops`
in `src/bench/macros.cljc`; the `#cpu` tag (§8) uses the same reader mechanism.

- The reader tag needs an entry in `src/data_readers.cljc` on a `:paths` root.
  An unqualified tag is accepted. The reader function runs in SCI at compile
  time and returns the rewritten form.
- Variadic `(* t t x)` is folded into binary calls at compile time, so there is
  no rest-args array at runtime.
- Outside `#gl`, plain `+` stays the JS operator: numbers only, zero cost.
- In-place update: `+=!`, `*=!`. These are valid names and match the Clojure
  convention that `!` marks mutation. Inside `#gl` the rewrite can map
  `(+= v a)` → `(m/add! v a)` if the shorter spelling is wanted there.

## 2. squint code-generation traps found on the way

These are library rules regardless of how the dispatch question ends.

| Trap                                                 | Effect                                                                                 | Rule                                                                               |
| ---------------------------------------------------- | -------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `deftype` with `Object` methods                      | Compiles to `function (_, b)`, so every argument is shifted by one. `a.add(b)` throws. | Use `defclass`, which emits a correct ES class.                                    |
| `(if (my-pred? x) …)` where `my-pred?` is a function | `if (squint_core.truth_(my_pred_QMARK_(x)))`; `^boolean` on `defn` is ignored          | Predicates are macros emitting `^boolean (js* …)`. With that, `and` becomes `&&`.  |
| `(and (f a) (f b))` in a test position               | An IIFE plus `truth_`                                                                  | Same as above. Comparisons (`<`, `=`) are already boolean.                         |
| `(instance? C x)`                                    | An IIFE plus `truth_`                                                                  | A macro emitting `^boolean (js* "(~{} instanceof ~{})")`.                          |
| `(number? x)`                                        | A call to `squint_core.number_QMARK_`                                                  | A `typeof` macro on hot paths.                                                     |
| `js*` inside syntax-quote                            | Qualified to `my.ns/js*`, which fails                                                  | Write `~'js*`.                                                                     |
| Macro errors from SCI                                | Sometimes only `Cannot read properties of null (reading 'length')`, no location        | Bisect. Here it was a macro parameter called `name` shadowing `clojure.core/name`. |

Effect of the `truth_` trap, measured on the first run: kind-table dispatch on
plain numbers took 22 ns (V8) / 71 ns (JSC) per iteration, against 4.5 / 2.3 ns
once the predicates were macros.

The `deftype` repro (removed):

```clojure
(deftype V3 [^:mutable x ^:mutable y ^:mutable z]
  Object
  (add [_ b] (V3. (+ x (.-x b)) (+ y (.-y b)) (+ z (.-z b)))))
```

```js
let f = (function (_, b) { const self__ = this; return (new V3((self__.x + b.x), …)); });
V3.prototype["add"] = f;   // a.add(w) binds _ = w, b = undefined → throws
```

The `defclass` form that works:

```clojure
(defclass V3c
  (field x) (field y) (field z)
  (constructor [this x0 y0 z0] (set! x x0) (set! y y0) (set! z z0))
  Object
  (add [this b] (V3c. (+ x (.-x b)) (+ y (.-y b)) (+ z (.-z b)))))
```

The predicate probe compiled each form below; only the first two shapes emit a
bare test:

```clojure
(if ^boolean (js* "typeof ~{} === 'number'" a) 1 2) ; → if (typeof a === 'number')
(if (and (< a b) (> a 0)) 1 2)                      ; → if (((a < b)) && ((a > 0)))
(defn ^boolean num? [x] (js* "typeof ~{} === 'number'" x))
(if (num? a) 1 2)                                   ; → if (squint_core.truth_(num_QMARK_(a)))
(if (instance? js/Array a) 1 2)                     ; → if (squint_core.truth_((() => {… instanceof …})()))
```

The fix as used now, in `src/bench/macros.cljc`; a tag set by a macro survives:

```clojure
(defmacro num? [x] (with-meta (list 'js* "(typeof ~{} === 'number')" x) {:tag 'boolean}))
(defmacro inst? [c x] (with-meta (list 'js* "(~{} instanceof ~{})" x c) {:tag 'boolean}))
```

## 3. What a generic operator has to do

Strategy **B** (`src/bench/disp_b.cljs`, methods in `types.cljs`):

```clojure
(defn add [a b]
  (if (num? a)
    (if (num? b) (js* "~{} + ~{}" a b) (.radd b a))   ; f32 + f32 | f32 + T
    (.add a b)))                                        ; T + anything
;; Vec3.add: (cond (num? b) … (inst? Vec3 b) … :else (lift to expression))
```

Per operation, at worst: 2 `typeof` tests, 1 method call (an inline-cache lookup
on the left operand's class), and an `instanceof` chain on the right operand
with as many links as that class accepts operand kinds (3–4 here: number, same
type, matrix, expression). A CPU value meeting a shader expression falls through
to `lift` at the end of the chain, so expression support costs nothing on
CPU-only paths.

Strategy **C** (removed): both operands mapped to a kind integer (a `k` field on
every class, still present in `types.cljs`), one lookup in a flat 6×6 function
table:

```clojure
(defn- kind [x] (if (num? x) 0 (.-k x)))

(defn add [a b]
  (if (and (num? a) (num? b))
    (js* "~{} + ~{}" a b)
    ((aget add-table (+ (* (kind a) 6) (kind b))) a b)))
;; add-table: (js/Array. 36) filled with a throwing no-op, then one
;; (fn [a b] …) per supported pair, e.g. index 2*6+2 for vec3 + vec3, and
;; i*6+5 / 5*6+i for anything with an expression.
```

It was never faster than B: the call through a table entry is a polymorphic call
site of its own. Dropped.

So yes: every layer of options per operand adds a test, and all of it runs on
the CPU at runtime. Section 4 shows the tests are not the main cost.

## 4. Measurements

### 4.1 One combination per call site: dedicated vs generic

`combos.cljs`: one loop per operand combination over 1024-element operand
arrays. **mono** calls the dedicated function, as a static compiler would emit.
**B** / **C** call the generic `add` / `mul`. Results are freshly allocated
(vectors are immutable values here).

**Isolated**: each figure from a fresh process, so the generic function has only
ever seen this one combination. This is the best case.

| Combination  | V8 mono | V8 B | V8 C | JSC mono | JSC B | JSC C |
| ------------ | ------: | ---: | ---: | -------: | ----: | ----: |
| f32 \* f32   |     1.1 |  1.1 |  1.8 |      1.0 |   1.0 |   1.0 |
| vec2 + vec2  |     7.2 |  9.1 |  9.7 |      3.2 |   3.1 |   4.2 |
| vec3 + vec3  |     7.7 |  9.9 | 10.4 |      3.9 |   3.6 |   4.7 |
| vec3 \* f32  |     6.6 |  6.5 |  8.1 |      2.8 |   2.6 |   3.3 |
| f32 \* vec3  |     6.1 |  6.5 |  7.3 |      2.6 |   2.6 |   3.7 |
| vec4 \* vec4 |     8.8 | 10.3 | 11.4 |      5.8 |   5.8 |   6.6 |
| mat4 \* vec4 |     7.2 | 11.8 | 14.3 |      8.9 |  20.1 |  20.1 |

**Shared**: all combinations in one process, one after the other, so the generic
`add` / `mul` have seen every kind. This is what a real program looks like,
where one library `mul` serves the camera, the uniforms and the geometry.

| Combination  | V8 mono | V8 B | V8 C | JSC mono | JSC B | JSC C |
| ------------ | ------: | ---: | ---: | -------: | ----: | ----: |
| f32 \* f32   |     1.3 |  1.0 |  1.0 |      1.1 |   1.1 |   1.0 |
| vec2 + vec2  |     7.1 |  9.1 | 10.2 |      3.3 |   3.1 |   4.0 |
| vec3 + vec3  |     7.8 | 15.9 | 19.7 |      3.6 |   3.5 |   5.0 |
| vec3 \* f32  |     6.4 |  9.3 | 15.2 |      3.3 |   3.0 |   3.9 |
| f32 \* vec3  |     6.3 |  9.2 | 18.0 |      3.1 |   3.0 |   4.2 |
| vec4 \* vec4 |     8.7 | 18.8 | 28.9 |      5.8 |   5.4 |  17.1 |
| mat4 \* vec4 |     8.7 | 25.1 | 26.8 |      7.3 |  20.1 |  18.9 |

### 4.2 A composite expression: `a*(1-t) + b*t` on vec3

`typed_use.cljs`, one process per variant:

| Variant                                                 |   V8 |  JSC |
| ------------------------------------------------------- | ---: | ---: |
| typed `defop` (macro resolves every operator, §6)       |  7.4 |  1.8 |
| hand-written monomorphic calls                          |  7.5 |  1.8 |
| generic B (`with-ops`, the same as `#gl` without types) | 38.6 | 30.6 |

### 4.3 Does supporting GPU expressions slow down CPU code?

`combos.cljs <combo> b expr`: before the timed loop, the same generic `add` /
`mul` build shader expressions (`expr + expr`, `expr * f32`, `vec3 * expr`,
`f32 * expr`, 20k times), as shader construction at sketch setup would. The
"clean" column runs without that. One process per figure.

| Combination  | V8 clean | V8 after shader build | JSC clean | JSC after shader build |
| ------------ | -------: | --------------------: | --------: | ---------------------: |
| f32 \* f32   |      1.1 |                   1.1 |       0.9 |                    2.4 |
| vec3 + vec3  |     10.3 |                  16.0 |       3.3 |                    3.2 |
| vec3 \* f32  |      6.6 |                  12.5 |       2.3 |                   11.9 |
| vec4 \* vec4 |      9.9 |                  17.9 |       4.3 |                    4.5 |

### 4.4 Other workloads (`run.cljs`)

| Workload                                                       |   V8 |  JSC |
| -------------------------------------------------------------- | ---: | ---: |
| scalar `fade` (6 ops), plain JS operators                      |  1.0 |  1.1 |
| scalar `fade`, generic B                                       |  4.4 |  2.5 |
| vec3 `acc + v*s`, monomorphic, allocating                      | 11.1 |  6.5 |
| vec3 `acc + v*s`, monomorphic, out-param into class fields     | 17.3 |  2.4 |
| vec3 `acc + v*s`, monomorphic, out-param into Float64Array     |  2.1 |  2.2 |
| vec3 `acc + v*s`, generic B                                    | 20.8 |  6.9 |
| one `mul` site fed 6 operand kinds in turn, B                  | 16.3 | 20.6 |
| `fade` building a shader expression tree (cold path, per call) |  163 |  205 |

## 5. What the numbers say

- **Isolated, the dispatch is nearly free.** With a fresh process, B costs 0–30
  % on V8 and nothing on JSC (mat4·vec4 excepted, where `Mat4.mul`'s chain and
  the non-inlined `m4*v4` show). The JIT inlines the generic function and folds
  the type tests away.
- **Shared, it is not.** V8 records type feedback per function, not per call
  site. Once `add` has seen numbers, vec2, vec3 and vec4, its method call goes
  polymorphic for every caller: vec3 + vec3 rises from 9.9 to 15.9, vec4 · vec4
  from 10.3 to 18.8. This affected my own harness too: three closures made by
  one `loop-with` function shared feedback, and a hand-written loop measured 16
  ns against 7 ns until each ran in its own process.
- **Composite expressions show the real mechanism.** In 4.2 the typed variant on
  JSC takes 1.8 ns for four vector operations, so all intermediate vectors are
  optimized away (escape analysis). That only works when every call is inlined.
  Generic dispatch blocks the inlining, so each operation allocates a real
  vector, and the expression becomes 5× (V8) to 17× (JSC) slower. **The cost of
  generic operators in the render loop is mostly allocation and lost
  optimization, not the type tests.** These allocations are also per-frame
  garbage, against goal G6. That part is inferred from the timings; GC was not
  traced.
- **The GPU check does carry over to CPU code, but indirectly (§4.3).** For CPU
  operands the expression branch never runs, since `lift` is the last link of
  every chain, so the check costs no instructions. What carries over is the type
  feedback: once shader construction has called `add` / `mul` with expression
  operands, the method call inside them has one more class to tell apart, for
  every caller, including the render loop that runs afterwards. The result is
  1.5–2× on V8 vector ops and up to 5× on JSC (`vec3 * f32`). Since feedback
  belongs to the code and not to the values, **separate code removes it**:
  - A `defop` already separates the two. Its CPU kernel contains no expression
    path at all; only the `defop` entry tests once whether it was handed an
    expression.
  - An untyped `#gl` helper meant for both sides (e.g. `fade`) can be compiled
    by its macro into two copies, one over CPU-only `add` / `mul` and one over
    the expression builders, chosen by one test at entry. The CPU copy then
    behaves like the "clean" column. That follows from the code being identical
    to what was measured, with only the feedback differing; the entry test
    itself was not measured separately.
  - Calling the one shared generic `add` from both shader code and render-loop
    code is the variant to avoid.
- **Storage matters as much as dispatch.** On V8, writing doubles into class
  fields is slow (17.3 ns for an out-param that allocates nothing), because
  since pointer compression V8 keeps double-valued fields boxed. The same
  out-param on `Float64Array` storage takes 2.1 ns on both engines. A tier-2 `!`
  API should write into typed arrays (or the `defstruct` buffers of research
  §6.4), not into object fields.
- **Kind tables (C) lose to method dispatch (B)** on both engines.
- **Shader generation**: ~25 ns per expression node. A shade with a thousand
  nodes costs tens of microseconds, once. Irrelevant, as expected.

Render-loop budget, using the shared-process figures (a frame is 16.7 ms at 60
Hz):

| Generic vector ops per frame                |   Extra over typed code |
| ------------------------------------------- | ----------------------: |
| 1,000 (camera, a few dozen uniforms)        |      ~10–30 µs (~0.1 %) |
| 10,000 (moderate CPU animation)             |    ~0.1–0.3 ms (~1–2 %) |
| 100,000 (CPU particles, per-vertex updates) | ~1–3 ms plus GC (10 %+) |

## 6. Compile-time dispatch: the typed form

`src/bench/typed.cljc`, a prototype of about 80 lines covering `+ - *` on f32
and vec3 with `let`:

```clojure
(defop mix3 ^vec3 [^vec3 a ^vec3 b ^f32 t]
  (let [s (- 1 t)]
    (+ (* a s) (* b t))))
```

expands to

```js
var mix3 = function (a, b, t) {
	const s_1 = 1 - t;
	return bench_DOT_mono.v3_PLUS_(
		bench_DOT_mono.v3_STAR_s(a, s_1),
		bench_DOT_mono.v3_STAR_s(b, t),
	);
};
```

```wgsl
fn mix3(a: vec3f, b: vec3f, t: f32) -> vec3f {
  let s = (1.0 - t);
  return ((a * s) + (b * t));
}
```

- Scalar operations become JS operators, vector operations become direct calls
  to the dedicated functions. The timings equal hand-written code (§4.2).
- `(defop bad [^vec3 a ^f32 t] (+ a t))` fails the build:
  `no + for vec3 and f32`. A typed form brings compile-time type errors back
  inside its own body. The error currently points at the macro's own line; a
  real implementation should report the line of the user's form, taken from its
  metadata.
- An unhinted parameter (`(defop untyped-scale [v ^f32 s] (* v s))`) falls back
  to the generic `mul` at that node only.
- **Limit.** squint has no analyzer, so the macro sees only its own form. Types
  can come from parameter hints, literals, `let` and calls to other `defop`s, if
  the macro records their signatures in compile-time state (SCI). That state has
  the stale-expansion problem of research §3.2 under hot reload. A `def`
  elsewhere, a closure, or a field read is unknown and needs a hint.

The same inference gives the in-place form its meaning: a typed `(+= acc v)` can
expand to `(v3/add! acc acc v)`. Untyped, it becomes the generic `add!`, with
the dispatch cost above but writing in place and allocating nothing.

## 7. Recommendation

| Where                                                        | Form                                                                     | Dispatch                                            |
| ------------------------------------------------------------ | ------------------------------------------------------------------------ | --------------------------------------------------- |
| Shader bodies, setup, geometry generation                    | `#gl` / `(gl …)`: bare operators over generic `add` / `mul` (strategy B) | runtime; cost irrelevant here                       |
| Helpers shared by CPU and GPU (noise, color, easing)         | `defop` with type hints                                                  | compile time on CPU, plus WGSL from the same source |
| Render-loop math (camera, per-frame uniforms, CPU particles) | `#cpu` with `=!` / `+=!` into typed-array storage (§8)                   | compile time; components inlined; no allocation     |
| Light per-frame code (≲ 1,000 ops)                           | `#gl` is acceptable                                                      | runtime, ~0.1 % of a frame                          |

Both forms use the same syntax, bare operators; the tag says which guarantees
apply. `#gl` interoperates and pays dispatch. `#cpu` is CPU-only and must be
fully typed. CPU code and expression building never share one generic function
(§5): the `#gl` / `defop` macros emit a separate CPU copy and expression copy of
each helper, with one test at entry.

Library rules that follow regardless (§2): `defclass`, not `deftype`; predicates
as `^boolean` macros; no functions named `+`, `*` or `+=`; vectors on
typed-array storage wherever the render loop writes them.

## 8. A hot form: `#cpu`

Prototype: `src/bench/cpu.cljc` (about 150 lines), registered as a reader tag in
`src/data_readers.cljc` (`{cpu bench.cpu/compile-form}`) and as a `(cpu …)`
macro. CPU-only: there is no expression path, so `#gl`'s interop costs (§4.3)
cannot reach it.

### 8.1 Design

- **Scalar replacement at compile time.** Every vector value inside the form is
  N scalar JS expressions, one per component. Operators combine them
  componentwise, and a `let`-bound vector becomes N scalar locals. No vector
  object exists at runtime, so nothing allocates, independent of what the JIT
  manages.
- **Storage at the boundary is typed arrays** (`Float64Array` here). Inputs are
  read with `aget`; the statement forms write the target: `(=! out expr)`,
  `(+=! out expr)`. The target's type follows from `expr`.
- **Types.** squint passes macros no information about enclosing locals, so
  types must be visible inside the form. A probe macro printing `&env` inside
  `(defn f [^vec3 a b] (let [^f32 s 1.0 c (+ a b)] (show-env)))` got only
  compiler state (`:ns-state`, `:imports`, `:core-vars`, `:output-dir`, …), no
  locals and no hints; `:ns-state` holds top-level vars with `:arglists` only,
  hints dropped. Allowed sources: a `^vec3` / `^f32` hint on any occurrence of a
  symbol (it applies to every occurrence in the form), constructors
  (`(vec3 0 -9.81 0)`), number literals, `let` inference.
- **Operators**: `+ - *` (variadic, componentwise or scalar broadcast), `dot`.
  Mismatched sizes are compile errors, as in §6.
- **Unknown types** fall back to `bench.disp-cpu`, a CPU-only generic namespace
  on numbers and `Float64Array`s. The build prints `WARNING #cpu: \`a\` has no
  type, runtime dispatch`.
- **Temporaries are scoped per statement**, so a statement reads what the
  previous one wrote. The first version hoisted all temporaries to the top of
  the form and computed `v * damping` before gravity was added; that showed in
  the emitted JS, not in any error.

Source and output:

```clojure
(defn step [ps vs i]
  (let [p (aget ps i) v (aget vs i)]
    #cpu (do (+=! ^vec3 v (* (vec3 0 -9.81 0) ^f32 dt))
             (=! v (* v ^f32 damping))
             (+=! ^vec3 p (* v dt)))))
```

```js
const p_1 = ps[i];
const v_2 = vs[i];
v_2[0] = v_2[0] + 0 * dt;
v_2[1] = v_2[1] + -9.81 * dt;
v_2[2] = v_2[2] + 0 * dt;
const s6598_3 = v_2[0] * damping;
const s6599_4 = v_2[1] * damping;
const s6600_5 = v_2[2] * damping;
v_2[0] = s6598_3;
v_2[1] = s6599_4;
v_2[2] = s6600_5;
p_1[0] = p_1[0] + v_2[0] * dt;
p_1[1] = p_1[1] + v_2[1] * dt;
return (p_1[2] = p_1[2] + v_2[2] * dt);
```

`(vec3 0 -9.81 0)` costs no allocation; its zero components remain as `0 * dt`,
which the JIT folds and a later version could fold itself. The temporaries in
the middle statement are unnecessary for a componentwise operation, but the rule
is simple: copy first whenever the target also appears on the right-hand side.

### 8.2 Measurements

`hot.cljs`, one variant per process, checksums identical within each workload.
GC counts are for the whole process (3 warmup + 7 timed rounds of 5 M
iterations), so only their ratios matter.

**`mix`**: `out = a*(1-t) + b*t`, one vec3 per iteration, ns per iteration:

| Variant                                                  |   V8 |  JSC | V8 GCs |
| -------------------------------------------------------- | ---: | ---: | -----: |
| `#cpu` into Float64Array                                 |  1.9 |  1.6 |      5 |
| hand-written scalar code into Float64Array               |  1.8 |  1.5 |      3 |
| typed `defop` returning a new Vec3 (class)               |  7.2 |  1.8 |    780 |
| generic `#gl` on Vec3 classes                            | 40.8 | 33.4 | 14,884 |
| `#cpu` with one untyped operand (fallback to `disp-cpu`) | 97.6 | 84.8 | 12,284 |

**Particles**: 10,000 particles per frame;
`vel += g*dt; vel *= damping; pos += vel*dt`; ns per particle step, so the
per-frame cost is this figure times 10,000:

| Variant                                                     |   V8 |  JSC | V8 GCs | V8 per frame |
| ----------------------------------------------------------- | ---: | ---: | -----: | -----------: |
| `#cpu`, each particle a Float64Array(3)                     | 16.1 |  5.6 |     26 |      0.16 ms |
| hand-written scalar code, same storage                      | 17.8 |  6.1 |     26 |      0.18 ms |
| in-place writes to Vec3 class fields (as Scala's `addSelf`) | 38.4 |  4.7 |    119 |      0.38 ms |
| monomorphic allocating Vec3 calls                           | 34.8 | 37.0 |    165 |      0.35 ms |
| generic `#gl` on Vec3 classes                               | 77.7 | 93.6 |    355 |      0.78 ms |

### 8.3 What the numbers say

- **`#cpu` is as fast as hand-written code** on both engines and leaves no
  garbage. Its GC count equals the hand-written baseline. It is the only variant
  whose speed does not depend on the JIT's escape analysis.
- **Its one weak point is the fallback.** A single untyped operand made `mix`
  50× slower, slower even than `#gl`: the prototype's generic namespace
  allocates a `Float64Array` per result and loops through a function argument.
  For a form whose purpose is speed, an untyped operand should be a compile
  error, with the fallback available only on request.
- **Storage dominates the particle loop.** 16 ns per particle against 1.9 ns for
  one `mix` is not arithmetic: every particle is two separately allocated typed
  arrays, and the loop chases two pointers per step. Packed storage, one
  `Float64Array` (or a `defstruct` buffer) with an offset per particle, is the
  obvious next measurement. It needs `#cpu` targets with an offset (§9).
- **Class fields with in-place writes** are fast on JSC (4.7) and slow on V8
  (38.4), the boxed-double effect of §5. The Scala library's in-place API
  (`addSelf`) writes `var` Double fields; whether Scala.js output behaves the
  same is the first thing the Scala comparison should check.

## 9. Recommendations for an implementation plan

For when this direction is taken up as a milestone plan
(`documents/m*-…-plan.md`).

### 9.1 Architecture

1. **One shared compiler core in `.cljc`**: the type lattice, inference, and an
   **op table as data**,
   `[op type-a type-b] → {result type, componentwise CPU emitter, WGSL emitter}`.
   The three front ends below sit on it, so a new operator or type is one table
   entry for all of them.
2. **`#gl` / `(gl …)`** for shader code and setup: rewrites operators to the
   generic functions; emits a CPU copy and an expression copy of each helper,
   chosen once at entry (§5).
3. **`defop`** for helpers used on both sides: a typed CPU kernel plus a WGSL
   function from the same source, with callee dependencies recorded (research
   §6.2).
4. **`#cpu` / `(cpu …)`** for the render loop: fully typed, scalar replacement,
   writes into storage. An untyped operand is a compile error unless the form
   opts into the fallback.
5. **A `defop` signature registry** kept as compile-time state in SCI, so `#cpu`
   and `#gl` can type calls to `defop`s. Check what happens to it under
   watch/hot reload (stale expansions, research §3.2) before relying on it.

### 9.2 Decisions to make early, each with a measurement

| Decision                                 | Options                                                                                            | Measure                                                          |
| ---------------------------------------- | -------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| CPU vector representation outside `#cpu` | `defclass` values (fast to allocate, boxed fields on V8) vs small typed arrays everywhere          | `#gl` and `defop` workloads on both; typed-array allocation cost |
| Hot storage                              | array of `Float64Array`s vs one packed buffer with offsets vs `defstruct` buffers (`Float32Array`) | particle loop on each (§8.3)                                     |
| `#cpu` storage kinds                     | typed arrays only, or class fields too (`(.-x v)`), chosen by hint                                 | readability in a ported sketch; V8 cost of field writes (§8.2)   |
| `f32` vs `f64` on the CPU                | `Float64Array` (exact) vs `Float32Array` (matches the GPU, direct upload)                          | same workloads; upload path without conversion                   |
| Reader tag vs macro spelling             | `#cpu (…)` vs `(cpu …)`; both work                                                                 | editor support: clj-kondo needs hooks for either                 |

### 9.3 Coverage needed before a sketch can be ported

- Types: `f32`, `vec2–4`, `mat3`, `mat4` (scalarized `mat4 * vec4` is 16
  multiply-adds; check code size against a loop).
- Operations: swizzles (free under scalar replacement), `normalize`, `length`,
  `cross`, `mix`, `clamp`, `smoothstep`, `select` / `if`, bounded loops, calls
  to `defop`s.
- `#cpu` targets with offsets into buffers:
  `(=! (at Particles buf i :pos) expr)` → `aset` on base + field offset.
- **Errors at the user's line** (form metadata), naming the operation and
  operand types, with "did you mean" for misspelled hint types.
- **clj-kondo hooks** for `#gl`, `#cpu`, `defop`, so the editor neither flags
  hinted symbols nor misses undefined ones.
- The library rules of §2 as a checklist, with a test that greps emitted JS for
  `truth_` and IIFEs in library code.

### 9.4 Comparison with the Scala library

The point of the milestone is to compare. To make the comparison fair, the same
workloads should exist on both sides, measured the same way:

- **Benchmarks.** Port `mix`, the particle step and the §4.1 combinations to
  `trivalibs-scala/bench/`, in the shape of the existing `NoiseBench.scala`
  (exported loops, run by `bun bench/*.bench.ts`). Do that in the Scala repo,
  not from here (the submodule is reference only). For each workload, the Scala
  variants are:
  - operators on `Vec3` (allocating);
  - in-place `addSelf` / `mulSelf`;
  - `StructRef` buffer writes.

  Same machine, both engines, the same method (3 warmups, median of 7, one
  process per variant), linked with `jsMode full` as the library recommends.
  Hypotheses to test, from §5 and §8.2:
  - Scala operators behave like the "monomorphic allocating" rows, because
    `inline` removes dispatch but not allocation.
  - `addSelf` on V8 is slowed by boxed `var` Double fields.
  - `StructRef` pays for going through `DataView`.

- **Code.** The same render-loop function written in both, side by side, for
  readability (goal G8). For example Scala `vel += g * dt` /
  `pos.addSelf(vel * dt)` against `#cpu (+=! ^vec3 pos (* vel dt))`.
- **Errors.** The same type mistake (`vec3 + vec2`, a misspelled field) in both:
  when it is reported (compile vs expansion), where (line), and how the message
  reads.
- **Bundle size** of the math part, gzipped, against the Scala `jsMode full`
  output of the same functions.
- **Loop latency.** Edit-to-frame time for a constant in a `#cpu` form, against
  the Scala rebuild. This is an input to
  [static-vs-live-workflow.md](static-vs-live-workflow.md).

## 10. Open

- **Firefox (SpiderMonkey)** is not measured. Its inlining and feedback model
  differ from both engines here.
- **GC.** Allocation pressure from generic operators in a real frame loop was
  not traced. Measure it with `--trace-gc` or DevTools on a ported sketch.
- **Inference scope.** How far `defop` inference must go (swizzles, builtins,
  bounded loops, calls between `defop`s, mat4 rules) to cover the trivalibs
  helper libs. The prototype only shows the mechanism.
- **Dev builds vs release.** Everything here ran unbundled. Vite's production
  build (esbuild/rollup minification) should not change JIT behaviour, but it
  was not checked.
- **Hint ergonomics.** Whether `^vec3` hints on every `defop` parameter read
  well in sketch code (goal G8). Only real sketch code answers that.
