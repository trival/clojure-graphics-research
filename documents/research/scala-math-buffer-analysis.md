# Scala math and buffer API — lessons from the squint dispatch research

**Status: research with measurements and a roadmap (§7); nothing changed in
trivalibs-scala.** Follow-up to
[uniform-math-operators.md](uniform-math-operators.md) §9.4 (the Scala
comparison). It reads the Scala library's emitted JS the way that document read
squint's, times the same workloads, and proposes changes to the Scala math and
buffer API. The changes go to the Scala repo only after this document has been
reviewed.

**Question.** Do the squint findings apply to the Scala library: shared generic
code losing type specialization (squint document §5), allocation in vector
expressions, boxed-double fields, typed-array storage? Which changes would make
the Scala math and `StructRef` / `StructArray` API faster on render-loop paths?
How does Scala compare with the fastest squint forms?

Short answer:

- **The same mechanism as squint §5, moved to link time.** Each op in the ops
  traits (`Vec3ImmutableOps.+`, `Vec3MutableOps.mulS`, `Mat4MutableOps.set`, …)
  becomes **one** shared JS function for every representation. The Scala.js
  optimizer devirtualizes it only while the program links a single
  representation. Link a `Vec3Tuple` op, or a `StructRef` in-place op, anywhere
  in the program, and every Vec3 op becomes virtual calls to `create` and to the
  accessor given (§1.1). Real sketches are on the edge: in `rooms/canvases` the
  immutable Vec3 ops are monomorphic, but the uniform writes (`:=`) dispatch
  over 2–5 implementations (§1.2).
- **`StructArray` element access is the one pathological cost.**
  `BufferView extends js.Object` makes Scala.js emit an `Object.defineProperty`
  constructor, and `arr(i)(k)` builds two of them per access. A particle loop
  over a `StructArray` takes **1,300 ns per particle on V8** (66 on JSC),
  against 6 ns hand-written on the same layout (§1.3, §2.2).
- **The idiomatic Scala forms are 6–7× off hand-written code**: 13 ns for a vec3
  `mix` against 1.8, 37 ns for a particle step on the in-place API against 5.7
  (230× through `StructArray`). Hand-written Scala over typed arrays is exactly
  as fast as squint's `#cpu`, because it compiles to the same JS (§2).
- **Three changes, each measured in a prototype (§3, §4):**
  1. `inline` on the small vector ops in the ops traits. Every call site gets
     its own monomorphic code, whatever else is linked: `mix` 15 → 7.5 ns,
     in-place particles 37 → 7.9 ns, in-place `StructRef` math 38 → 9.5 ns.
  2. A reusable cursor (or at least a plain Scala class) instead of a fresh
     `js.Object` per element: packed particles 645 → 18 ns (V8), 58 → 12 ns
     (JSC), both with inline ops (1,300 / 67 with the library today).
  3. Buffer storage through `Float32Array` instead of `DataView` for 4-byte
     fields: 2.2× (V8) to 4.2× (JSC) on a packed particle loop.

  `@inline` ref classes (§4.3) make a buffer ref cost nothing. Since the painter
  only ever writes flat f32 data, the roadmap (§7) replaces `StructArray` in the
  core with Float32Array buffers and inline writers, after inlining the math
  ops.

- **The per-frame cost in real sketches is small.** The `rooms/canvases` sway
  loop costs 11 µs per frame for 64 paintings, the camera 0.3 µs (§2.4). The
  changes matter for CPU particle and geometry work, and for keeping the
  library's own rule ("no typeclass / given dispatch on CPU hot paths",
  trivalibs `CLAUDE.md`) true in every program, not just in small ones.

---

## How it was measured

- Code: [experiments/scala-math/](../../experiments/scala-math/). It compiles
  against `trivalibs-scala/src` (this repo's submodule, 76ba5f1) without
  changing it. `project.scala` has the trivalibs settings (Scala 3.9.0, Scala.js
  1.22.0, `jsMode full`, ES modules, ES2021) with **`smallestmodules`**: one ES
  module per class, so every trait's shared functions and every given's
  accessors are in a file of their own
  (`out/trivalibs.graphics.math.-Vec3-Immutable-Ops.js`, …).
- Two links of the same sources:
  - `out/`: everything (`bench/*.scala`). The ops traits see the class, the
    tuple, two `StructRef` layouts and, in `Proto.scala`, five more
    representations of their own.
  - `out-class/`: only `Common.scala` + `ClassOps.scala`, workloads on the Vec3
    / Vec4 / Mat4 classes and plain typed arrays. Here the ops traits see one
    implementation each.
- Build (from the repo root; outside the Claude sandbox, whose read-only `/tmp`
  breaks scala-cli's JS linker):

  ```bash
  E=experiments/scala-math
  rm -rf $E/.scala-build
  scala-cli --power package $E/bench trivalibs-scala/src $E/project.scala --js -o $E/out -f
  scala-cli --power package $E/bench/Common.scala $E/bench/ClassOps.scala trivalibs-scala/src $E/project.scala --js -o $E/out-class -f
  ```

  `.scala-build` is cleared first, as the trivalibs `CLAUDE.md` requires for any
  comparison of emitted JS.

- Run: `bash experiments/scala-math/bench.sh > results.txt` runs every variant
  once per process on node and bun, plus a node GC count, then the comparable
  squint variants from `experiments/math-dispatch` (recompiled first), then the
  combos in one shared process. Single variant:
  `OUT=out node experiments/scala-math/run.mjs <export> [n]`. Raw output of the
  run used here: `experiments/scala-math/results.txt`.
- Method as in [uniform-math-operators.md](uniform-math-operators.md): 3 warmup
  rounds, median of 7, ns per loop iteration (5 M iterations; 1 M for the
  `StructArray` and camera loops). Same machine (i7-1165G7, Linux 7.0, mains
  power), node v24.16.0 (V8), bun 1.4.4 (JSC). The squint figures in the tables
  are from the same session, not copied from the earlier document. Reruns varied
  by about ±10 % (±25 % for the allocation-bound `pPart*Ref` rows).
- GC counts are whole-process `--trace-gc` scavenges. They compare variants
  **within one build** only: the same workload counts 789 in `out-class` and 395
  in `out`, presumably from different heap sizing.
- Checksums are identical across all variants of one workload, Scala and squint.

## 1. What the library compiles to

### 1.1 Ops traits: one shared function per op

Source (`trivalibs-scala/src/graphics/math/vec3.scala`):

```scala
trait Vec3ImmutableOps[Vec]:
  def create(x: Double, y: Double, z: Double): Vec
  extension (v: Vec)(using Vec3Base[Vec])
    def +(other: Vec): Vec = create(v.x + other.x, v.y + other.y, v.z + other.z)
```

`object Vec3 extends Vec3ImmutableOps[Vec3]`,
`object Vec3Tuple extends Vec3ImmutableOps[Vec3Tuple]`: both share the trait's
method body. Scala.js emits it once, as a static function taking the instance as
`$thiz`, and every `a + b` calls it.

With only the Vec3 class linked (`out-class/`, and a one-function probe), the
optimizer knows every receiver and folds both dispatches:

```js
function $f_…Vec3ImmutableOps__addVec…($thiz, v, x$2, other) {
  return new $c_…Vec3((v.b + other.b), (v.c + other.c), (v.d + other.d));
}
```

With the tuple and buffer representations linked too (`out/`), the same function
is generic:

```js
function $f_…Vec3ImmutableOps__addVec…($thiz, v, x$2, other) {
  return $thiz.am(((+x$2.b(v)) + (+x$2.b(other))), ((+x$2.c(v)) + (+x$2.c(other))), …);
}
```

`$thiz.am` is `create`, `x$2.b` is the `x` accessor of whichever given was
passed. The call site does not change; it still calls the one `$f_` function.
Every caller, for every representation, then shares that function's type
feedback in the JIT. The squint document measured that effect in §4.3; here it
also happens one step earlier, in the Scala.js optimizer, and is decided by
**what else the program links**.

For `StructRef`, the accessor itself is a double dispatch through the given
(`$p.b = function(v) { return this.a6(v) }`, `a6` reading the DataView).

### 1.2 What real sketches link

`sketches-scala/sketches/rooms/canvases/main.js` (committed fullopt build,
`fewestmodules`):

- `Vec3ImmutableOps.addVec` / `mulScalar` are monomorphic
  (`new $c_…Vec3((v.z + other.z), …)`): the sketch uses only the Vec3 class.
- `Mat4MutableOps.set` and `Vec4MutableOps.set`, the uniform writes behind
  `binding.set(mat)` / `:=`, call accessors through the given:
  `mb.o1(m, (+x$4.hY(other)))`. 2 (Mat4) and 5 (Vec4) implementations of those
  accessors are linked.
- 34 call sites of math-op functions in an 806 KB bundle (110 KB gzipped). That
  number bounds the code-size cost of inlining the small ops (§4.2).

So today's sketches mostly get the fast form for immutable vector ops, by
accident of what they link. One tuple op added anywhere, by the sketch or by a
library function it calls, changes that for every Vec3 op in the program.

### 1.3 `StructRef` and `StructArray`

`StructRef[F]` and `StructArray[F]` are opaque types over

```scala
class BufferView(val dv: DataView, var off: Int) extends js.Object
```

Because it extends `js.Object`, Scala.js emits a JS class whose constructor
defines each field with `Object.defineProperty` (JS class-field semantics):

```js
constructor(arg, arg$2) {
  …
  super();
  Object.defineProperty(this, "dv", ({ "configurable": true, "enumerable": true, "writable": true, "value": null }));
  Object.defineProperty(this, "off", ({ … "value": 0 }));
  this.dv = dv;
  this.off = off;
}
```

`arr(i)` builds one `BufferView`, `arr(i)(0)` a second. The particle loop over
`StructArray[(Vec3Buffer, Vec3Buffer)]` therefore allocates four per particle
through this constructor, before any arithmetic. V8 does not optimize these
allocations away; JSC handles them better (§2.2).

The reads and writes themselves inline to `dv.getFloat32(off + k, true)`. F32
reads add `Math.fround`.

### 1.4 Minor findings in the emitted JS

- Given aliases (`given Vec3ImmutableOps[Vec3] = Vec3`) are lazy vals. Each op
  call fetches them through an accessor with an init check (`$m_…Vec3$().h()`).
  The JIT inlines it; no cost measured.
- `Mat4 * Vec4` with the class representations is clean, monomorphic code (16
  multiply-adds, one `new Vec4`) in both builds. Its cost in §2.3 comes from the
  storage, not the dispatch.
- `StructRef.set` from a tuple (`p.set0((x, y, z))`) compiles to plain
  `setFloat32` calls; the tuple is not allocated.
- **`final` on the math classes changes nothing**, and **`extends js.Object`
  would be the `BufferView` cost again**
  (`experiments/scala-math/spikes/class-kind/`). A `Vec2`-shaped class in three
  variants, allocating one per iteration, V8 / JSC:
  - plain: 5.5 / 3.0 ns;
  - `final`: 5.4 / 3.2 ns, the emitted JS identical up to field names. The
    optimizer already knows the class has no subclasses from the whole program.
    `final` is API intent only.
  - `extends js.Object`: 311 / 9.9 ns. The constructor takes `...rest`, checks
    each argument for `undefined` (default parameters), and runs
    `Object.defineProperty` per field. Reads become `(+r.x)`. The fields keep
    their names (`x`, `y`), which is the only gain.

### 1.5 Other `js.Object` classes in trivalibs

A `js.Object` class costs only when Scala.js has to construct it (§1.3, §1.4).
`@js.native` facades (`GPUDevice`, `GPUQueue`, …, the Preact bindings) and
non-native traits (`PixelFrames`, `PixelImage`, preact `Props`) describe objects
that come from JS or from object literals. They have no constructor and cost
nothing. That leaves four non-native classes in `src/`:

| Class                          | Why `js.Object`                                                                                | Created                                                                                     | Verdict                                                                                                       |
| ------------------------------ | ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `BufferView` (`bufferdata`)    | none visible                                                                                   | per `StructArray` element access                                                            | roadmap §7 removes it from the core                                                                           |
| `PanelBinding` (`panel.scala`) | none needed: every match on it is compile-time (`inline pair.value match`), no `instanceof`    | per `panel.binding(…)`, and per `bind("tex" := panel)` (wrapped in `PanelBinding(p)`), 29 call sites in sketches | **change**: plain `final class`. Also drops the prelude's type-alias workaround for it          |
| `BlendFn`, `BlendState` (`enums.scala`) | passed as is into the pipeline descriptor (`blend = blendState`), so the field names must be WebGPU's | presets once; user values at setup; read once per new pipeline (cached by `blendKeyStr`)    | **optional**: plain `final class`es converted with `Obj.literal` in `getPipeline`; the gain is the removed prelude workaround, not speed |

The prelude comment (`prelude/painter.scala`) explains the workaround: an
`export` of a JS type is rejected by the Scala.js plugin, so these three classes
are re-exported through hand-written aliases. Plain classes export normally.

Per-frame object literals: each `paint` builds its render-pass descriptors
(`Obj.literal(colorAttachments = …)`, clear values) and bind-group entries on a
cache miss. Literals do not run `defineProperty` and cost tens of nanoseconds,
far below the WebGPU calls they feed. They are not a candidate.

## 2. Measurements

ns per iteration; V8 = node, JSC = bun; "GCs" = node scavenges for the whole
process. Unless marked `out-class`, Scala figures are from the full build
`out/`. Squint names refer to `experiments/math-dispatch/src/bench/hot.cljs`.

### 2.1 `mix`: `out = a*(1-t) + b*t` on vec3

| Variant                                                     |    V8 |   JSC |    GCs |
| ----------------------------------------------------------- | ----: | ----: | -----: |
| squint `#cpu` into Float64Array                             |  1.77 |  1.52 |      4 |
| squint hand-written, Float64Array                           |  1.77 |  1.53 |      3 |
| Scala hand-written, Float64Array (`mixF64Hand`)             |  1.85 |  1.84 |      4 |
| Scala componentwise on `StructRef[Vec3dBuffer]` accessors   |  4.05 |  5.12 |      3 |
| Scala componentwise on Vec3 class fields (`mixClassHand`)   |  7.60 |  8.97 |    384 |
| squint typed `defop`, allocating                            |  7.47 |  1.90 |    776 |
| Scala `a.mix(b, t)` (`mixMethod`)                           | 13.35 | 11.44 |    388 |
| same, `out-class`                                           | 11.75 | 11.40 |    774 |
| Scala operators `a * (1 - t) + b * t` (`mixOps`)            | 15.06 | 21.98 |    395 |
| same, `out-class`                                           | 13.17 | 19.77 |    789 |
| Scala operators on `Vec3Tuple`                              | 17.69 | 20.16 |    392 |
| Scala in-place `mulS` / `+=` on the class (`mixSelf`)       | 17.22 | 27.82 |    384 |
| Scala in-place `mulS` / `+=` on `StructRef[Vec3Buffer]` F32 | 37.73 | 28.11 |    770 |
| Scala in-place on `StructRef[Vec3dBuffer]` F64              | 34.45 | 24.49 |    768 |
| squint generic `#gl`                                        | 38.34 | 31.59 | 14,884 |

### 2.2 Particles: 10,000 per frame, `vel += g*dt; vel *= damping; pos += vel*dt`

ns per particle step.

| Variant                                                                       |       V8 |   JSC |   GCs |
| ----------------------------------------------------------------------------- | -------: | ----: | ----: |
| Scala hand-written, one packed Float64Array (`partF64Packed`)                 |     5.66 |  2.77 |     5 |
| Scala hand-written, Vec3 class fields (`partClassHand`)                       |     5.12 |  4.18 |    11 |
| Scala hand-written, one packed Float32Array (GPU layout)                      |     6.65 |  5.01 |    10 |
| Scala hand-written, a Float64Array(3) per vector (`partF64Per`)               |     9.65 |  4.67 |    13 |
| squint `#cpu`, same storage as `partF64Per`                                   |    15.24 |  5.18 |    26 |
| squint hand-written, same storage                                             |    14.43 |  4.68 |    26 |
| Scala hand-written, packed F32 through a raw DataView                         |    14.90 | 21.21 |    10 |
| squint `class-inplace` (field writes, as Scala's `partClassHand`)             |    39.83 |  4.34 |   119 |
| squint monomorphic allocating calls                                           |    32.44 | 30.05 |   166 |
| Scala in-place, no allocation (`v += gdt; v *= d; v.mulS(dt, tmp); p += tmp`) |    37.47 | 28.48 |    10 |
| same, `out-class`                                                             |    29.17 | 29.36 |     9 |
| Scala in-place as a sketch writes it (`p += v * dt` allocates)                |    45.67 | 27.26 |   413 |
| Scala immutable operators (`partOps`)                                         |    86.35 | 52.72 |   398 |
| squint generic `#gl`                                                          |    64.86 | 83.53 |   355 |
| Scala `StructArray` F32, in-place library API (`partStructF32`)               | 1,299.80 | 66.53 | 2,913 |
| Scala `StructArray` F64, same                                                 | 1,281.60 | 62.46 | 2,912 |

### 2.3 One operand combination per call site, allocating (squint §4.1)

| Combination  | squint V8 | Scala V8 | Scala V8 `out-class` | squint JSC | Scala JSC |
| ------------ | --------: | -------: | -------------------: | ---------: | --------: |
| vec3 + vec3  |      7.60 |     9.72 |                10.31 |       3.84 |      8.36 |
| vec3 \* f32  |      7.45 |     9.60 |                 9.87 |       2.83 |      6.95 |
| vec4 \* vec4 |      9.56 |    12.09 |                13.25 |       4.89 |      9.08 |
| mat4 \* vec4 |      7.39 |    17.87 |                19.57 |       6.87 |     23.15 |

squint column: the dedicated (`mono`) functions. Squint's Mat4 keeps its 16
entries in a Float64Array; Scala's Mat4 has 16 `var` Double fields.

All combos in one process, in order (squint's "shared" setup), V8: `out-class`
9.8 / 8.8 / 10.5 / 17.7; `out` with a tuple combo first 17.1 / 9.6 / 10.6 /
17.0. Only vec3 + vec3, the op the tuple shares, slows down: 9.7 → 17.1 on V8,
8.4 → 10.2 on JSC.

### 2.4 Per-frame work from a real sketch

`rooms/canvases` `animate`: per painting, a TRS model matrix from a swayed
position written to its uniform buffer, and a Vec4 shadow rect (ns per
painting). The camera: `cam.viewProjMat` written to a uniform (ns per frame).

| Variant                                                        |    V8 |   JSC |    GCs |
| -------------------------------------------------------------- | ----: | ----: | -----: |
| sway as written (`Mat4.fromTranslationRotationScale(…)`, `:=`) | 179.2 | 123.3 | 13,250 |
| sway composed into Float32Arrays, no temporaries               |  42.3 |  31.8 |      7 |
| camera `viewProjMat` → uniform                                 | 292.6 | 274.6 |  6,926 |

64 paintings cost 11.5 µs per frame as written, 2.7 µs composed by hand. The
camera costs 0.3 µs. Against a 16.7 ms frame, both are noise. Only the garbage
counts: a Mat4, a Quat, two Vec3 and a Vec4 per painting per frame.

## 3. What the numbers say

- **Hand-written Scala equals squint `#cpu`.** On the same storage it is the
  same JS: 1.85 vs 1.77 ns for `mix`. The speed limit is the same in both
  languages. What differs is how close the idiomatic form gets to it: squint's
  `#cpu` _is_ the idiomatic form; in Scala the closest library form
  (`a.mix(b, t)`) is 7× off on V8, the in-place particle API 6.6×, the
  `StructArray` API 230×.
- **Link-time polymorphism costs 5–30 %, not more, in the pure-class case.**
  `out-class` vs `out` for the class workloads: 13.2 → 15.1 (`mixOps`), 82.7 →
  86.4 (`partOps`), 29.2 → 37.5 (in-place particles). The JIT recovers most of
  it as long as each call site only sees one class. It costs more where several
  representations meet in one function: the in-place `StructRef` ops (35–38 ns
  for work that is 4 ns written out) and the shared-process combo (9.7 → 17.1).
- **Calls that cannot be inlined box doubles.** `mixStructF32` allocates nothing
  in its source but shows 770 GCs. The values cross polymorphic calls
  (`x$2.b(v)` returning a double) and are boxed as heap numbers there. With
  inline ops (`pMixJRef`, §4) the same storage and arithmetic runs 9.5 ns with 6
  GCs.
- **Class fields with in-place writes are fine in Scala, not in squint.**
  `partClassHand` takes 5.1 ns on V8 with 11 GCs; squint's `class-inplace`, the
  same loop, 39.8 ns with 119 GCs. Scala.js initializes every field with a
  number in the constructor (`this.b = 0.0`); squint's `defclass` declares
  fields (`x;`), which starts them as `undefined`. That V8 then stores them
  boxed, and so allocates on every double write, is the likely reason. The
  squint document's statement in §5 ("V8 keeps double-valued fields boxed") is
  therefore too general: it depends on how the class initializes its fields. Not
  tested further here; it is a squint-side fix (initialize fields to `0.0`).
- **Not every class-field write is free.** `mixClassHand` (three field writes
  per iteration, nothing allocated in source) shows as many GCs as the
  allocating `mixOps`. A plain-JS copy of the same loop does not allocate, and
  `--no-maglev` does not change it. **Open**: the cause is not isolated.
  Typed-array storage (`mixF64Hand`, 4 GCs) avoids the question.
- **Storage matters as in squint.** For the same packed layout: Float64Array 5.7
  / 2.8, Float32Array 6.7 / 5.0, DataView F32 14.9 / 21.2 (V8 / JSC). Matrix
  storage: squint's Float64Array-backed `mat4 * vec4` is 2.4× (V8) to 3.4× (JSC)
  faster than Scala's 16 fields.
- **JSC removes allocations that Scala's code keeps.** squint's typed `defop`
  `mix` runs 1.9 ns on JSC (allocations removed); the Scala prototype with the
  same inlined arithmetic and allocations (`pMixOps`) runs 8.2. Possible cause:
  Scala's constructor writes every field twice (`0.0`, then the argument).
  Unmeasured.
- **The `StructArray` cost is allocation, not DataView.** The same packed F32
  layout costs 14.9 ns through a raw DataView and 1,300 ns through
  `StructArray`. JSC's 66 ns shows it can optimize the `defineProperty`
  constructor; V8 cannot.

## 4. Prototypes

`experiments/scala-math/bench/Proto.scala`, linked into `out/`. The prototype
traits have the shape of `Vec3Base` / `Vec3Mutable` / `Vec3ImmutableOps` /
`Vec3MutableOps`, cut down to `+`, `* s`, `mulS`, `+=`, `*=`. The only change:
the ops are `inline`.

```scala
trait PImm[V]:
  def create(x: Double, y: Double, z: Double): V
  extension (v: V)(using PBase[V])
    @targetName("addVec")
    inline def +(o: V): V = create(v.x + o.x, v.y + o.y, v.z + o.z)
```

They are implemented for a mutable class (`PV3`), a tuple (`PT3`), and four F32
buffer refs. Linked together, they give the traits more representations to
handle than `out/` does for the library.

| Ref type | Class                                            | Storage                 | Per element                    |
| -------- | ------------------------------------------------ | ----------------------- | ------------------------------ |
| `JRef`   | trivalibs `BufferView` (`js.Object`)             | DataView                | new ref                        |
| `SRef`   | `final class SView(dv, off)`                     | DataView                | new ref                        |
| `FRef`   | `final class FView(a, off)`                      | Float32Array            | new ref                        |
| cursors  | `BufferView` / `final class FCursor(a, var off)` | DataView / Float32Array | one ref per role, offset moved |

Emitted JS for `pa * (1.0 - t) + pb * t`, in the full build:

```js
var v$proxy1 = new $c_Lbench_PV3((…pa.f * s$proxy1), (…pa.g * s$proxy1), (…pa.h * s$proxy1));
var o$proxy1 = new $c_Lbench_PV3((…pb.f * t), (…pb.g * t), (…pb.h * t));
var r = new $c_Lbench_PV3((v$proxy1.f + o$proxy1.f), (v$proxy1.g + o$proxy1.g), (v$proxy1.h + o$proxy1.h));
```

No shared function, no given, no `create` call left; in-place ops on buffer refs
become straight `getFloat32` / `setFloat32` sequences.

### 4.1 Results

| Workload            | Library today (`out`)                  | Prototype                              |          V8 |         JSC |   GCs |
| ------------------- | -------------------------------------- | -------------------------------------- | ----------: | ----------: | ----: |
| mix, operators      | `mixOps` 15.06 / 21.98                 | inline ops, class (`pMixOps`)          |        7.51 |        8.17 |   395 |
| mix, operators      | `mixTuple` 17.69 / 20.16               | inline ops, tuple                      |        8.19 |        5.18 |   382 |
| mix, in-place       | `mixSelf` 17.22 / 27.82                | inline ops, class                      |       11.44 |       14.58 |   384 |
| mix, in-place       | `mixStructF32` 37.73 / 28.11           | inline ops, `JRef` (today's storage)   |        9.46 |        9.88 |     6 |
| mix, in-place       |                                        | inline ops, `SRef`                     |       11.98 |        9.96 |     5 |
| mix, in-place       |                                        | inline ops, `FRef` (Float32Array)      |        7.41 |        4.33 |     7 |
| particles, ops      | `partOps` 86.35 / 52.72                | inline ops, class                      |       60.52 |       29.27 |   255 |
| particles, in-place | `partSelfNoAlloc` 37.47 / 28.48        | inline ops, class                      |        7.90 |       12.27 |    12 |
| particles, packed   | `partStructF32` 1,299.80 / 66.53       | inline ops, `JRef`, new ref per access |      644.86 |       58.09 | 7,273 |
| particles, packed   |                                        | inline ops, `SRef`, new ref per access |       27.64 |       46.95 |    16 |
| particles, packed   |                                        | inline ops, `FRef`, new ref per access | 17.96–23.44 | 34.69–35.71 |    21 |
| particles, packed   |                                        | inline ops, `BufferView` cursor        |       17.51 |       12.28 |    11 |
| particles, packed   |                                        | inline ops, Float32Array cursor        |       18.42 |       10.06 |    13 |
| particles, packed   | hand-written Float32Array: 6.65 / 5.01 |                                        |             |             |       |

### 4.2 Reading

- **Inline ops remove the link-time effect completely and halve the class
  workloads.** In a program with more representations than `out/`, inline `mix`
  is faster (7.5) than the library in a program with only one (`out-class`,
  13.2). In-place particles drop from 37.5 to 7.9, near hand-written (5.1).
- **For `StructRef`, inline ops are the larger win:** in-place math on today's
  storage drops from 37.7 to 9.5 ns with no other change, and the boxing GCs
  disappear (770 → 6).
- **Per-element ref objects:** `defineProperty` construction is the 645 ns. A
  plain Scala class brings it to 28 ns, a cursor to 18 ns (V8). On JSC,
  allocating refs stay at 35–58 ns and only the cursor gets to 10–12 ns. The
  cursor needs no new class: `BufferView.off` is already a `var`.
- **The remaining gap to hand-written (18 vs 6.7 ns)** is the temporary: `mulS`
  into `tmp` writes three floats to memory and reads them back, where hand code
  keeps `v * dt` in registers. squint's `#cpu` removes it by scalar replacement
  at compile time (uniform-math-operators.md §8). Scala could get the same with
  inline ops that return an inline-only expression instead of a vector
  (`pos += vel * dt` expanding to three scalar updates). Not prototyped.
- **Code size**: inline ops grow each call site by its componentwise body (about
  3 statements per component in the emitted JS). With 34 math call sites in a
  large sketch, the bundle growth is small. Large ops (`inverse`, `matMul`,
  `perspective`, `lookAt`) should stay functions.

### 4.3 Spike: `@inline` ref classes

`experiments/scala-math/spikes/inline-class/Probe.scala`. Scala.js eliminates
the allocation of a class annotated `@inline` when no instance escapes: it
treats the instance as its fields. A vertex ref over a Float32Array,

```scala
@inline final class VRef(val a: Float32Array, val off: Int):
  @inline def set0(x: Double, y: Double): Unit =
    a(off) = x.toFloat
    a(off + 1) = y.toFloat

final class VArr(val a: Float32Array, val count: Int):
  @inline def apply(i: Int): VRef = new VRef(a, i * 4)

v(i).set0(i * 0.5, i * 0.25)
```

compiles to the hand-written form, with no object:

```js
var a = v.e;
var off = i$1 << 2;
a[off] = Math.fround(x);
a[(1 + off) | 0] = Math.fround(y);
```

So today's user syntax (`vertices(i).set0(…)`) can stay and cost nothing. The
condition is that the ref never escapes. Today's geometry builders pass
`verts(vi)` into a closure (`write(arr(si), verts(vi))`), which counts as
escaping. Also visible: `.toFloat` emits `Math.fround`. A store into a
Float32Array rounds anyway, so the call is redundant work.

**Double in, no `Math.fround`**
(`experiments/scala-math/spikes/inline-class/Float32Access.scala`). The standard
facade types the elements as `Float`, so every store needs a `.toFloat`. Two
ways around it, both measured to emit plain stores:

```scala
// 1. A second facade for the same JS class, with Double elements.
@js.native @JSGlobal("Float32Array")
class F32Array(len: Int) extends js.Object:
  @JSBracketAccess def apply(i: Int): Double = js.native
  @JSBracketAccess def update(i: Int, v: Double): Unit = js.native

// 2. An opaque type over the standard facade, Double access by cast.
opaque type F32 = Float32Array
extension (a: F32)
  inline def update(i: Int, v: Double): Unit =
    a.asInstanceOf[js.Array[Double]](i) = v
```

```js
a[i << 1] = 0.5 * i; // store: no Math.fround
a[(1 + (i << 1)) | 0] = +a[i << 1] + 0.25; // read: a unary-plus number hint
```

The facade (1) is the safer choice. The cast in (2) is erased in `jsMode full`,
but under `jsMode fast`'s checked casts a Float32Array is not a `js.Array` and
the cast throws. A cast to the facade (`x.asInstanceOf[F32Array]`) checks
`instanceof Float32Array` and passes, so the facade also converts to and from
the standard type. The user-facing API takes `Double` (JS numbers) throughout;
rounding to f32 happens in the store, as WebGPU expects.

## 5. Comparison with the squint results

Fastest form per workload, V8 / JSC, ns:

| Workload                | squint, fastest idiomatic          | Scala, fastest idiomatic today  | Scala prototype (§4; roadmap §7)                | Scala hand-written, best storage |
| ----------------------- | ---------------------------------- | ------------------------------- | ----------------------------------------------- | -------------------------------- |
| vec3 `mix`              | `#cpu` 1.77 / 1.52                 | `a.mix(b, t)` 13.35 / 11.44     | `FRef` in-place 7.41 / 4.33                     | 1.8 / 1.5–1.8                    |
| particles               | `#cpu` 15.24 / 5.18                | in-place on class 37.47 / 28.48 | class in-place 7.90 / 12.27; cursor 17.5 / 10.1 | 5.1–5.7 / 2.8–4.2                |
| particles, GPU layout   | (`#cpu` has no packed targets yet) | `StructArray` 1,300 / 67        | F32 cursor 18.4 / 10.1                          | 6.7 / 5.0                        |
| vec3 + vec3, allocating | `mono` 7.60 / 3.84                 | 9.72 / 8.36                     | —                                               | —                                |
| mat4 \* vec4            | `mono` 7.39 / 6.87                 | 17.87 / 23.15                   | — (§7, phase 6)                                 | —                                |

- **squint's `#cpu` is still the only idiomatic form at hand-written speed.** It
  scalarizes at compile time; the Scala prototypes keep vectors in memory, which
  costs 2–4× on V8 even when all dispatch is gone.
- **Scala's packed-buffer story is better once the F32 buffers of §7 replace
  `StructArray`.** Typed layouts with compile-time offsets exist today. The
  roadmap keeps them and drops the per-element objects and the DataView. `#cpu`
  has no buffer targets yet (uniform-math-operators.md §9.3). The packed
  hand-written loop (5.7 / 2.8) is the fastest particle variant measured on
  either side.
- **Allocation-heavy code is slower in Scala** (combos, `mixOps`), most on JSC.
  The field-initialization and matrix-storage points above are the candidates.

For the static-vs-live question
([static-vs-live-workflow.md](static-vs-live-workflow.md)) this adds one point:
static types do not give the Scala library fast code by themselves. Dispatch
goes through typeclasses, and the optimizer decides at link time, per program.
Annotations (`inline`) and storage choices make it fast, as macros and storage
choices do in squint.

## 6. Open

- The cause of the GCs in `mixClassHand` (§3).
- Whether Scala's double field initialization blocks JSC's allocation removal
  (§3), and whether squint `defclass` fields initialized to `0.0` close the
  `class-inplace` gap (§3).
- Uniform upload batching (§7, phase 3).
- Firefox, as in the squint document.
- An inline-expression form for Scala (§4.2, §7 "later"): feasibility and
  compile times.

## 7. Roadmap: F32 buffers and inline math for the Scala painter

A plan to turn §1–4 into changes in trivalibs-scala. Each phase becomes its own
plan document in the Scala repo (`documents/*.md`, with a Findings section),
written when the phase starts. Nothing here is decided until then; names in code
blocks are proposals.

### 7.1 Scope

What the library supports today, and what this roadmap keeps:

- **Uniforms are f32 only.** `Float`, `Double` (written as f32), `Vec2–4`,
  `Mat2–4`, and `UniformArray[T, N]` of those. The integer WGSL types (`Int`,
  `UInt`, `IVec*`, `UVec*`) already have no CPU buffer representation
  (`types.scala`: `UniformBuffer = EmptyTuple`), and `UniformArrayElem` rejects
  them. Custom uniform structs do not exist.
- **Vertex attributes are flat and f32 only.** `Float`, `Double`, `Vec2–4`
  (`float32`…`float32x4`), one interleaved buffer per form, no nesting. `Mat*`
  have no vertex format.
- **So every GPU buffer the painter writes is a flat run of f32 at offsets known
  at compile time.** That is a `Float32Array` with constant indices. The general
  machinery (`StructArray` / `StructRef`, tuple-typed layouts, `F32` / `U8` / …
  primitive tags, `DataView`, `BufferView`) is more than the painter needs, and
  it is where the costs of §1.3 come from.
- **`StructArray` leaves the core** (`graphics/buffers`, `graphics/geometry`,
  `graphics/painter`, `graphics/shader`, the `*Buffer` givens in `math/cpu`). It
  may stay in `utils/bufferdata.scala` as a general utility, unlinked by the
  painter (dead-code elimination removes it from sketch bundles). It comes back
  only with a use that needs mixed field types, most likely a compute-shader
  buffer API, which is a distant wish, not planned here.
- **CPU math keeps `Double` class fields.** Typed arrays appear only where a GPU
  buffer is created and written. Reasons:
  - Class fields with in-place writes match typed arrays on the particle loop
    (`partClassHand` 5.1 / 4.2 vs packed Float64Array 5.7 / 2.8, §2.2).
  - The large costs measured are dispatch and buffer refs, not field storage.
  - An F32 switch for CPU math (Float32Array-backed vectors and matrices) would
    lose precision. The rounding itself costs nothing: with the Double-typed
    facade (§4.3) it happens inside the store. But every value stored back is
    rounded to 24 bits of mantissa (about 7 decimal digits). That loss
    accumulates where state is updated in place frame after frame (camera
    position and rotation, animation time, particle integration). It also
    hurts where large and small values meet (world positions far from the
    origin, matrix inversion, long running `t`). The GPU computes in f32 anyway,
    but each frame receives a fresh rounding of exact CPU state, so errors do
    not compound. Against that, the switch would gain only the copy at upload,
    which phase 3 makes one store per float.
  - The one storage result against fields is `Mat4` (§2.3); phase 6 measures it
    separately.

### 7.2 Target design

**`WGSLType` is the single source of truth.** Today the same eight value types
(`Float`, `Double`, `Vec2–4`, `Mat2–4`) are described four times:

| Where                                              | What it holds                                                         | Used by                                           |
| -------------------------------------------------- | --------------------------------------------------------------------- | ------------------------------------------------- |
| `WGSLType` givens (`shader/types.scala`)           | `wgslName`, `vertexFormat`, `byteSize`, `alignment`                   | shader codegen; vertex layout (`byteSize`)        |
| `WGSLType.AttribBuffer` / `UniformBuffer` members  | `StructArray` tuple layouts (`Vec3Buffer`, `Mat3PaddedBuffer`, …)     | `allocateAttribs` (`UniformBuffer` is unused)     |
| `UniformValue` givens (`buffers/binding.scala`)    | uniform padding and write/read for the same types, again              | `BufferBinding`, `UniformArray`                   |
| `UniformArrayElem` givens (`uniform_array.scala`)  | lanes per 16-byte row                                                 | `UniformArray`                                    |

Beyond the repetition, the copies have drifted. `alignment` is read nowhere.
`WGSLType[Mat3].byteSize` is 36, the packed attribute size, while WGSL's
`mat3x3<f32>` occupies 48 bytes. That value works only because nothing uses
`byteSize` for uniforms.

The target keeps one given per type and moves everything there. Proposed shape:

```scala
/** Shader-side contract: every type a shade can name. */
trait WGSLType[T]:
  def wgslName: String
  def vertexFormat: String
  def isSampler: Boolean = false
  def isDepthTexture: Boolean = false

/** A WGSLType the CPU writes into GPU buffers, as f32 words. The only place
  * that knows a value type's sizes, padding and conversion. */
trait F32Type[T] extends WGSLType[T]:
  type Floats <: Int         // packed, as a vertex attribute: Vec3 = 3, Mat3 = 9
  type UniformFloats <: Int  // WGSL uniform layout incl. padding: Vec3 = 4, Mat3 = 12
  type UniformAlign <: Int   // in floats: Float = 1, Vec2 = 2, Vec3/Vec4/Mat* = 4
  type Lanes <: Int          // per 16-byte row in a uniform array: Float = 4, Vec2 = 2, else 1
  def write(a: F32Array, off: Int, v: T): Unit         // packed
  def writeUniform(a: F32Array, off: Int, v: T): Unit  // padded (Mat3: 3 × (3 + pad))
  def read(a: F32Array, off: Int): T
  def readUniform(a: F32Array, off: Int): T
```

- **One given per type.** `Float`, `Double`, `Vec2–4` and `Mat2–4` implement
  `F32Type`. Textures, samplers and the integer types stay plain `WGSLType`s,
  which matches today's rule that only f32 types have a CPU buffer
  representation. The uniform and attribute APIs require `F32Type[T]`; an
  `@implicitNotFound` message replaces `UniformArrayElem`'s for unsupported
  types.
- **Sizes are literal types**, read with `constValue`: `type Floats = 3`. Offsets
  and strides of a named-tuple schema are summed at compile time from them, as
  `TupleSize` / `FieldOffset` do now. Byte values for WebGPU descriptors are
  `4 × Floats`. `byteSize` and `alignment` either go or are derived, so they
  cannot disagree again.
- **Writers are ordinary methods on the given, marked `@inline`.** Each call
  site reaches its given as a known module (`summonInline[F32Type[Vec3]]`), so
  the Scala.js optimizer resolves and inlines it, whatever other types are
  linked. This differs from §1.1, where one trait body took the representation
  as a parameter. `binding.set(mat)` becomes 16 direct stores, with no
  `UniformValue` dispatch and no given accessors (§1.2). Phase 2 verifies this
  in the emitted JS; abstract `inline def`s on the trait are the fallback, at the
  cost of being callable only where the given is statically known.
- **What it replaces:**
  - in `types.scala`: `AttribBuffer` / `UniformBuffer`;
  - `UniformValue` and `UniformLayout` (`binding.scala`);
  - `UniformArrayElem`;
  - `AttribLayout` / `AttribLayoutHelper` (`attributes.scala`);
  - `FieldWriter` / `VertexLayout` (`geometry/buffers.scala`);
  - the runtime offset sums in `layouts.scala` (`fieldByteSizes`,
    `calculateOffsets`).

  All of them become walks over a named tuple, summoning `F32Type` per field.
- **Uniform wrappers** (`VertexUniform[T]`, `FragmentUniform[T]`,
  `SharedUniform[T]`) keep forwarding to `inner`; the type members forward as
  `inner.Floats`, …. A binding's value type is the unwrapped `T`, so the
  wrappers need no writers.
- **The buffer type is a Double-typed `Float32Array` facade** (§4.3, variant 1).
  Every write API takes `Double`; stores emit `a[i] = v` with no `Math.fround`,
  and the rounding to f32 happens in the store itself. `F32Type` and the refs
  use this facade; interop with code expecting the standard `Float32Array`
  (`writeBuffer`, `.buffer`) is a checked cast that passes in both link modes.
- **Refs are `@inline final class`es** over `(F32Array, offset)` (§4.3). They
  keep the `v(i).set0(…)` syntax, write through `F32Type.write`, and allocate
  nothing as long as no ref escapes.

### 7.3 Phases

**Phase 0: baseline and guard rails.**

- Port the workloads to `trivalibs-scala/bench/`, in the shape of
  `NoiseBench.scala` (`mix`, particles, combos, sway, camera from §2), plus
  three buffer workloads:
  - vertex fill: `allocateAttribs` + `set0` / `set1` for 100k vertices;
  - `toBufferedGeometry` of a mesh;
  - `line2d` stroke geometry, sized like a `paintings/` sketch.
- Record the bundle size of two sketches (`base-triangle`, `rooms/canvases`,
  gzipped).
- Add a check over a `smallestmodules` / `jsMode full` link of the examples. It
  lists op functions that call through `$thiz.` or a given accessor, and any
  `defineProperty` constructor reached from painter code. It is the Scala
  counterpart of uniform-math-operators.md §9.3's grep test.
- Exit: baseline numbers in the phase plan. The check reports today's findings
  (§1.1–1.3).

**Phase 1: inline the small math ops.**

- Mark `inline` the componentwise ops and scalar broadcasts of
  `Vec2–4ImmutableOps` / `Vec2–4MutableOps`, plus `mix`, `dot`, `length`,
  `normalize`, `set` / `:=`, and `Mat*MutableOps.set`.
- Large ops stay functions: `inverse`, `matMul`, `perspective`, `lookAt`,
  `fromTranslationRotationScale`.
- Declare the math classes `final` (`Vec2–4`, `Mat2–4`, `Quat`). It does not
  change the emitted JS (§1.4). It states that the classes are values, and it
  keeps users from subclassing them. They stay plain Scala classes, never
  `js.Object` (311 vs 5.5 ns per allocation on V8, §1.4).
- Independent of the buffer work and the largest measured CPU gain (2× class
  operators, 4–4.7× in-place, §4.1). It comes first.
- Exit: the phase 0 check finds no dispatching op function in the examples'
  link; benchmarks; bundle size within a few percent.

**Phase 2: F32 core.**

- `F32Type` on the existing `WGSLType` givens (the f32 ones change their parent
  trait; nothing else in them moves yet).
- Compile-time offsets for named-tuple schemas.
- The `@inline` ref class and the Double-typed `Float32Array` facade.
- munit tests for every size and padding rule against today's sources: `TupleSize`
  of `AttribBuffer` / `UniformValue` layouts, `UniformArrayElem` lanes, and
  `byteSize`, except where the test documents a fix (`Mat3` as a uniform: 48
  bytes).
- Spike first: the `@inline` writer reached through `summonInline` inlines in a
  program that links every `F32Type` (§7.2).
- Nothing uses it yet.
- Exit: tests green; the emitted JS of a test sketch-sized write shows direct
  stores.

**Phase 3: uniform bindings on Float32Array.**

- `BufferBinding[T]` (one type parameter; `F` is gone) holds an `F32Array`
  sized from `UniformFloats` (≥ 4). `set` writes through `F32Type.writeUniform`
  and uploads.
- `UniformArray` writes with a stride from `UniformFloats` / `Lanes`. `get`
  reads back.
- `UniformValue`, `UniformLayout` and `UniformArrayElem` are deleted.
- `PanelBinding` becomes a plain `final class` (§1.5). Optionally `BlendFn` and
  `BlendState` too, converted to a literal where the pipeline is created. Both
  remove a type-alias workaround in the prelude.
- `update(ref => …)` exposes a `StructRef`. No example or sketch calls it. Drop
  it, or replace it with a typed form.
- **Upload batching**: measure first how many `writeBuffer` calls a sketch makes
  per frame (`rooms/canvases`: 2 per painting). If it matters, mark bindings
  dirty on `set` and upload once per binding before the first `paint` that reads
  it.
- Exit:
  - `painter_typed_bindings`, `uniform_array_gradient` and all sketches compile;
  - `UniformArray` and headless painter tests green;
  - the emitted `binding.set(mat4)` is 16 stores and one upload.

**Phase 4: vertex attributes and geometry on Float32Array.**

- `allocateAttribs[A](n)` returns a `VertexArray[A]` (proposal): a
  `Float32Array` with a vertex count, stride from `A` at compile time,
  `v(i).set0(…)`… through the `@inline` ref. The examples' vertex code stays as
  it is.
- `BufferedGeometry` and `Form.upload` take it.
- The geometry builders (`geometry/buffers.scala`) write each field through
  `F32Type.write` at a compile-time offset. That replaces `FieldWriter`,
  `VertexLayout` and the `WriteOne` closures, so refs no longer escape into
  closures.
- `AttribLayout` goes. `layouts.scala`'s `vertexBufferLayout` takes its offsets
  and stride from the same compile-time sums, so the vertex descriptor and the
  buffer writes come from one computation.
- `line2d`'s `writeLineVert` writes its 8 floats directly.
- Type names that users see change:
  - `BufferedGeometry[LineAttribsBuffer]` in 4 sketches and 1 example: keep
    `LineAttribsBuffer` as an alias for one release, or rename it `LineAttribs`;
  - `RandomLines`' `StructArray[Vec2Buffer *: EmptyTuple]`.
- Optional, decide in the plan: type `Form` by its attribute schema, so a
  mismatch between a form and a shade's `Attribs` becomes a compile error. Today
  `Form` is untyped and the layout comes from the shade alone.
- Exit:
  - every example and sketch builds;
  - the vertex-fill and `line2d` benchmarks show the gain;
  - no `BufferView` constructor in the link of a geometry example.

**Phase 5: remove `StructArray` from the core.**

- Delete the `*Buffer` givens from `math/cpu` (`Vec2–4Buffer`, `Vec2–4dBuffer`,
  `Mat2–4Buffer`, `Mat3PaddedBuffer`) and the `Mat2Buffer.given` imports in two
  examples.
- Delete `WGSLType`'s `AttribBuffer` / `UniformBuffer` members, and `byteSize` /
  `alignment` if phase 2 derived them.
- This also removes the representations that made the ops traits polymorphic
  (§1.1); with phase 1 done it no longer matters for speed, but it removes code.
- `utils/bufferdata.scala` stays as a general utility with its tests, outside
  the painter's imports. If it stays, give `BufferView` the §4 fixes (plain or
  `@inline` class, typed-array views for 4-byte fields), so a later compute API
  starts from the fast form.
- Update the guides (`sketch-authoring-guide.md`, `gotchas.md`), the
  `write-sketch` skill and the API docs.
- Exit: `base-triangle` and `rooms/canvases` bundles no longer link
  `trivalibs.bufferdata`; bundle sizes against phase 0.

**Phase 6: CPU math storage, only if measured worth it.**

- With phases 1–5 done, rerun `mat4 * vec4`, `mat4 * mat4`, `inverse` and the
  camera path.
- If Mat4's 16 fields still cost 2× or more against a Float64Array-backed
  version (§2.3), back `Mat4` (and `Mat3`) by a Float64Array behind the same
  field API.
- Investigate the `mixClassHand` GCs (§3) here too.
- Exit: a decision with numbers, either way.

**Later, not planned:**

- an inline expression form for in-place updates (`pos += vel * dt` without a
  temporary, §4.2);
- fused writers into uniform refs (`setTRS(t, q, s)`): 4× on the sway loop, but
  9 µs per frame (§2.4);
- a compute-shader buffer API, which brings back a general struct layout,
  rebuilt on the §4 findings.

### 7.4 Order and risk

| Phase | Changes for users                                 | Measured gain (this document)                         | Risk                                      |
| ----- | ------------------------------------------------- | ----------------------------------------------------- | ----------------------------------------- |
| 0     | none                                              | —                                                     | none                                      |
| 1     | none (same API)                                   | 2× operators, 4–4.7× in-place (§4.1)                  | code size; compile time of inline bodies  |
| 2     | none                                              | — (enabler)                                           | padding rules: covered by tests           |
| 3     | `BufferBinding` loses `F`; `update` goes          | `StructRef` writes 38 → 9.5 ns with inline ops (§4.1) | std140 edge cases (`Mat3`, arrays)        |
| 4     | `StructArray` / `LineAttribsBuffer` in type names | 1,300 → hand-written speed per element (§2.2, §4.3)   | geometry builders are the largest rewrite |
| 5     | `*Buffer` givens gone                             | bundle size (to measure)                              | low                                       |
| 6     | maybe none (same field API)                       | up to 2.4–3.4× on `mat4 * vec4` (§2.3)                | to be measured                            |

Phases 1 and 3 change per-frame code; phase 4 changes setup code (geometry
building), which matters for load time in the stroke paintings. The sketches
repo builds against the submodule, so every phase ends with
`bun run sketches -k` in sketches-scala, besides the library's own tests and
examples.
