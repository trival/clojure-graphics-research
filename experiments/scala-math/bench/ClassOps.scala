package bench

// Workloads on the Vec3/Vec4/Mat4 classes and plain typed arrays only. Built
// alone (scala-cli package bench/Common.scala bench/ClassOps.scala …) this is a
// program in which the math ops traits see one implementation each.

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.*

import trivalibs.bufferdata.*
import trivalibs.graphics.math.cpu.{*, given}
import trivalibs.graphics.scene.PerspectiveCamera
import trivalibs.utils.numbers.NumExt.given


/** Operators on the Vec3 class: three allocations per iteration. */
@JSExportTopLevel("mixOps", moduleID = "bench")
def mixOps(n: Int): Double =
  var acc = 0.0
  var i = 0
  while i < n do
    val t = i * 1e-7
    val r = ca * (1.0 - t) + cb * t
    acc += r.x
    i += 1
  acc

/** The library's own `mix`: one allocation per iteration. */
@JSExportTopLevel("mixMethod", moduleID = "bench")
def mixMethod(n: Int): Double =
  var acc = 0.0
  var i = 0
  while i < n do
    val r = ca.mix(cb, i * 1e-7)
    acc += r.x
    i += 1
  acc

/** In-place library API on the Vec3 class (`mulS` into out/tmp, `+=`). */
@JSExportTopLevel("mixSelf", moduleID = "bench")
def mixSelf(n: Int): Double =
  val out = Vec3.zero
  val tmp = Vec3.zero
  var acc = 0.0
  var i = 0
  while i < n do
    val t = i * 1e-7
    ca.mulS(1.0 - t, out)
    cb.mulS(t, tmp)
    out += tmp
    acc += out.x
    i += 1
  acc

/** Componentwise on Vec3 class fields. */
@JSExportTopLevel("mixClassHand", moduleID = "bench")
def mixClassHand(n: Int): Double =
  val out = Vec3.zero
  var acc = 0.0
  var i = 0
  while i < n do
    val t = i * 1e-7
    val s = 1.0 - t
    out.x = ca.x * s + cb.x * t
    out.y = ca.y * s + cb.y * t
    out.z = ca.z * s + cb.z * t
    acc += out.x
    i += 1
  acc

/** Componentwise on Float64Array: the same code `#cpu` emits. */
@JSExportTopLevel("mixF64Hand", moduleID = "bench")
def mixF64Hand(n: Int): Double =
  val a = Float64Array.of(1.0, 2.0, 3.0)
  val b = Float64Array.of(3.0, 2.0, 1.0)
  val out = new Float64Array(3)
  var acc = 0.0
  var i = 0
  while i < n do
    val t = i * 1e-7
    val s = 1.0 - t
    out(0) = a(0) * s + b(0) * t
    out(1) = a(1) * s + b(1) * t
    out(2) = a(2) * s + b(2) * t
    acc += out(0)
    i += 1
  acc


/** Immutable operators, allocating: the readable Scala form. */
@JSExportTopLevel("partOps", moduleID = "bench")
def partOps(n: Int): Double =
  val pv = classParticles()
  val ps = pv(0)
  val vs = pv(1)
  frames(n): i =>
    val v = (vs(i) + gravity * dt) * damping
    vs(i) = v
    ps(i) = ps(i) + v * dt
  ps(0).y

/** In-place operators as a sketch would write them; `vel * dt` still
  * allocates.
  */
@JSExportTopLevel("partSelf", moduleID = "bench")
def partSelf(n: Int): Double =
  val pv = classParticles()
  val ps = pv(0)
  val vs = pv(1)
  val gdt = gravity * dt
  frames(n): i =>
    val v = vs(i)
    v += gdt
    v *= damping
    ps(i) += v * dt
  ps(0).y

/** In-place library API, no allocation (`mulS` into a temp). */
@JSExportTopLevel("partSelfNoAlloc", moduleID = "bench")
def partSelfNoAlloc(n: Int): Double =
  val pv = classParticles()
  val ps = pv(0)
  val vs = pv(1)
  val gdt = gravity * dt
  val tmp = Vec3.zero
  frames(n): i =>
    val v = vs(i)
    v += gdt
    v *= damping
    v.mulS(dt, tmp)
    ps(i) += tmp
  ps(0).y

/** Vec3 class fields, componentwise. Same as `class-inplace` in hot.cljs. */
@JSExportTopLevel("partClassHand", moduleID = "bench")
def partClassHand(n: Int): Double =
  val pv = classParticles()
  val ps = pv(0)
  val vs = pv(1)
  frames(n): i =>
    val p = ps(i)
    val v = vs(i)
    v.y = v.y + -9.81 * dt
    v.x = v.x * damping
    v.y = v.y * damping
    v.z = v.z * damping
    p.x = p.x + v.x * dt
    p.y = p.y + v.y * dt
    p.z = p.z + v.z * dt
  ps(0).y

/** One Float64Array(3) per vector, componentwise. Same storage and code as
  * `cpu` / `hand` in hot.cljs.
  */
@JSExportTopLevel("partF64Per", moduleID = "bench")
def partF64Per(n: Int): Double =
  val ps = js.Array[Float64Array]()
  val vs = js.Array[Float64Array]()
  var i = 0
  while i < np do
    initPos(i, (x, y, z) => ps.push(Float64Array.of(x, y, z)))
    initVel(i, (x, y, z) => vs.push(Float64Array.of(x, y, z)))
    i += 1
  frames(n): i =>
    val p = ps(i)
    val v = vs(i)
    v(1) = v(1) + -9.81 * dt
    v(0) = v(0) * damping
    v(1) = v(1) * damping
    v(2) = v(2) * damping
    p(0) = p(0) + v(0) * dt
    p(1) = p(1) + v(1) * dt
    p(2) = p(2) + v(2) * dt
  ps(0)(1)

/** One packed Float64Array, 6 doubles per particle, componentwise. */
@JSExportTopLevel("partF64Packed", moduleID = "bench")
def partF64Packed(n: Int): Double =
  val b = new Float64Array(np * 6)
  var i = 0
  while i < np do
    initPos(i, (x, y, z) => { b(i * 6) = x; b(i * 6 + 1) = y; b(i * 6 + 2) = z })
    initVel(i, (x, y, z) => { b(i * 6 + 3) = x; b(i * 6 + 4) = y; b(i * 6 + 5) = z })
    i += 1
  frames(n): i =>
    val o = i * 6
    b(o + 4) = b(o + 4) + -9.81 * dt
    b(o + 3) = b(o + 3) * damping
    b(o + 4) = b(o + 4) * damping
    b(o + 5) = b(o + 5) * damping
    b(o) = b(o) + b(o + 3) * dt
    b(o + 1) = b(o + 1) + b(o + 4) * dt
    b(o + 2) = b(o + 2) + b(o + 5) * dt
  b(1)

/** One packed Float32Array, 6 floats per particle (the GPU layout). */
@JSExportTopLevel("partF32Packed", moduleID = "bench")
def partF32Packed(n: Int): Double =
  val b = new Float32Array(np * 6)
  var i = 0
  while i < np do
    initPos(i, (x, y, z) => { b(i * 6) = x.toFloat; b(i * 6 + 1) = y.toFloat; b(i * 6 + 2) = z.toFloat })
    initVel(i, (x, y, z) => { b(i * 6 + 3) = x.toFloat; b(i * 6 + 4) = y.toFloat; b(i * 6 + 5) = z.toFloat })
    i += 1
  frames(n): i =>
    val o = i * 6
    b(o + 4) = (b(o + 4) + -9.81 * dt).toFloat
    b(o + 3) = (b(o + 3) * damping).toFloat
    b(o + 4) = (b(o + 4) * damping).toFloat
    b(o + 5) = (b(o + 5) * damping).toFloat
    b(o) = (b(o) + b(o + 3) * dt).toFloat
    b(o + 1) = (b(o + 1) + b(o + 4) * dt).toFloat
    b(o + 2) = (b(o + 2) + b(o + 5) * dt).toFloat
  b(1)


@JSExportTopLevel("comboV3AddV3", moduleID = "bench")
def comboV3AddV3(n: Int): Double =
  val a = v3s
  combo(n)(k => (a(k) + a((k + 1) & (nops - 1))).x)

@JSExportTopLevel("comboV3MulS", moduleID = "bench")
def comboV3MulS(n: Int): Double =
  val a = v3s
  combo(n)(k => (a(k) * (k * 0.5)).x)

@JSExportTopLevel("comboV4MulV4", moduleID = "bench")
def comboV4MulV4(n: Int): Double =
  val a = v4s
  combo(n)(k => (a(k) * a((k + 1) & (nops - 1))).x)

@JSExportTopLevel("comboM4MulV4", moduleID = "bench")
def comboM4MulV4(n: Int): Double =
  val m = m4s
  val a = v4s
  combo(n)(k => (m(k) * a(k)).x)
