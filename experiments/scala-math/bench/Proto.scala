package bench

// Prototypes of possible trivalibs changes, measured on the same workloads:
//
// 1. Ops traits with `inline` methods (same shape as Vec3Base / Vec3ImmutableOps
//    / Vec3MutableOps), so each call site gets its own monomorphic copy
//    instead of calling one shared `$f_…` function for every representation.
// 2. Buffer refs as a plain Scala class instead of `BufferView extends
//    js.Object` (whose constructor runs Object.defineProperty per field).
// 3. Buffer refs over a Float32Array instead of a DataView.
//
// Linked into the same program as Mixed.scala, and with a tuple
// representation of its own, so the ops traits here see five representations.

import scala.annotation.targetName
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.*

import trivalibs.bufferdata.BufferView

// =============================================================== traits

trait PBase[V]:
  extension (v: V)
    def x: Double
    def y: Double
    def z: Double

trait PMut[V] extends PBase[V]:
  extension (v: V)
    def x_=(a: Double): Unit
    def y_=(a: Double): Unit
    def z_=(a: Double): Unit

trait PImm[V]:
  def create(x: Double, y: Double, z: Double): V
  extension (v: V)(using PBase[V])
    @targetName("addVec")
    inline def +(o: V): V = create(v.x + o.x, v.y + o.y, v.z + o.z)
    @targetName("mulScalar")
    inline def *(s: Double): V = create(v.x * s, v.y * s, v.z * s)

trait PMutOps[V]:
  extension (v: V)(using PMut[V])
    inline def mulS(s: Double, out: V): V =
      out.x = v.x * s
      out.y = v.y * s
      out.z = v.z * s
      out
    @targetName("addAssign")
    inline def +=(o: V): Unit =
      v.x = v.x + o.x
      v.y = v.y + o.y
      v.z = v.z + o.z
    @targetName("mulAssign")
    inline def *=(s: Double): Unit =
      v.x = v.x * s
      v.y = v.y * s
      v.z = v.z * s

// =============================================================== representations

/** Mutable class, as trivalibs' Vec3. */
final class PV3(var x: Double, var y: Double, var z: Double)

object PV3 extends PImm[PV3]:
  inline def create(x: Double, y: Double, z: Double) = new PV3(x, y, z)
  given PMut[PV3]:
    extension (v: PV3)
      inline def x = v.x
      inline def y = v.y
      inline def z = v.z
      inline def x_=(a: Double) = v.x = a
      inline def y_=(a: Double) = v.y = a
      inline def z_=(a: Double) = v.z = a
  given PMutOps[PV3] = new PMutOps[PV3] {}

/** Immutable tuple, as Vec3Tuple. */
type PT3 = (Double, Double, Double)

object PT3 extends PImm[PT3]:
  inline def create(x: Double, y: Double, z: Double) = (x, y, z)
  given PBase[PT3]:
    extension (v: PT3)
      inline def x = v._1
      inline def y = v._2
      inline def z = v._3

/** F32 ref through trivalibs' BufferView (js.Object) and a DataView: the
  * current StructRef representation.
  */
opaque type JRef = BufferView

object JRef:
  inline def apply(dv: DataView, off: Int): JRef = new BufferView(dv, off)
  given PMut[JRef]:
    extension (v: JRef)
      inline def x = v.dv.getFloat32(v.off, true): Double
      inline def y = v.dv.getFloat32(v.off + 4, true): Double
      inline def z = v.dv.getFloat32(v.off + 8, true): Double
      inline def x_=(a: Double) = v.dv.setFloat32(v.off, a.toFloat, true)
      inline def y_=(a: Double) = v.dv.setFloat32(v.off + 4, a.toFloat, true)
      inline def z_=(a: Double) = v.dv.setFloat32(v.off + 8, a.toFloat, true)
  given PMutOps[JRef] = new PMutOps[JRef] {}

/** F32 ref through a plain Scala class and a DataView. */
final class SView(val dv: DataView, val off: Int)
opaque type SRef = SView

object SRef:
  inline def apply(dv: DataView, off: Int): SRef = new SView(dv, off)
  given PMut[SRef]:
    extension (v: SRef)
      inline def x = v.dv.getFloat32(v.off, true): Double
      inline def y = v.dv.getFloat32(v.off + 4, true): Double
      inline def z = v.dv.getFloat32(v.off + 8, true): Double
      inline def x_=(a: Double) = v.dv.setFloat32(v.off, a.toFloat, true)
      inline def y_=(a: Double) = v.dv.setFloat32(v.off + 4, a.toFloat, true)
      inline def z_=(a: Double) = v.dv.setFloat32(v.off + 8, a.toFloat, true)
  given PMutOps[SRef] = new PMutOps[SRef] {}

/** F32 ref through a plain Scala class and a Float32Array (offset in floats). */
final class FView(val a: Float32Array, val off: Int)
opaque type FRef = FView

object FRef:
  inline def apply(a: Float32Array, off: Int): FRef = new FView(a, off)
  given PMut[FRef]:
    extension (v: FRef)
      inline def x = v.a(v.off): Double
      inline def y = v.a(v.off + 1): Double
      inline def z = v.a(v.off + 2): Double
      inline def x_=(s: Double) = v.a(v.off) = s.toFloat
      inline def y_=(s: Double) = v.a(v.off + 1) = s.toFloat
      inline def z_=(s: Double) = v.a(v.off + 2) = s.toFloat
  given PMutOps[FRef] = new PMutOps[FRef] {}

// =============================================================== mix

private val pa = PV3(1.0, 2.0, 3.0)
private val pb = PV3(3.0, 2.0, 1.0)
private val qa: PT3 = (1.0, 2.0, 3.0)
private val qb: PT3 = (3.0, 2.0, 1.0)

@JSExportTopLevel("pMixOps", moduleID = "bench")
def pMixOps(n: Int): Double =
  var acc = 0.0
  var i = 0
  while i < n do
    val t = i * 1e-7
    val r = pa * (1.0 - t) + pb * t
    acc += r.x
    i += 1
  acc

@JSExportTopLevel("pMixTuple", moduleID = "bench")
def pMixTuple(n: Int): Double =
  import PT3.{*, given}
  var acc = 0.0
  var i = 0
  while i < n do
    val t = i * 1e-7
    val r = qa * (1.0 - t) + qb * t
    acc += r._1
    i += 1
  acc

@JSExportTopLevel("pMixSelf", moduleID = "bench")
def pMixSelf(n: Int): Double =
  val out = PV3(0.0, 0.0, 0.0)
  val tmp = PV3(0.0, 0.0, 0.0)
  var acc = 0.0
  var i = 0
  while i < n do
    val t = i * 1e-7
    pa.mulS(1.0 - t, out)
    pb.mulS(t, tmp)
    out += tmp
    acc += out.x
    i += 1
  acc

private inline def mixRefs[R](n: Int, a: R, b: R, out: R, tmp: R)(using
    PMut[R],
    PMutOps[R],
): Double =
  a.x = 1.0
  a.y = 2.0
  a.z = 3.0
  b.x = 3.0
  b.y = 2.0
  b.z = 1.0
  var acc = 0.0
  var i = 0
  while i < n do
    val t = i * 1e-7
    a.mulS(1.0 - t, out)
    b.mulS(t, tmp)
    out += tmp
    acc += out.x
    i += 1
  acc

private def dv12() = new DataView(new ArrayBuffer(12))

@JSExportTopLevel("pMixJRef", moduleID = "bench")
def pMixJRef(n: Int): Double =
  mixRefs(n, JRef(dv12(), 0), JRef(dv12(), 0), JRef(dv12(), 0), JRef(dv12(), 0))

@JSExportTopLevel("pMixSRef", moduleID = "bench")
def pMixSRef(n: Int): Double =
  mixRefs(n, SRef(dv12(), 0), SRef(dv12(), 0), SRef(dv12(), 0), SRef(dv12(), 0))

@JSExportTopLevel("pMixFRef", moduleID = "bench")
def pMixFRef(n: Int): Double =
  val f = new Float32Array(12)
  mixRefs(n, FRef(f, 0), FRef(f, 3), FRef(f, 6), FRef(f, 9))

// =============================================================== particles

private val pgravity = PV3(0.0, -9.81, 0.0)

@JSExportTopLevel("pPartOps", moduleID = "bench")
def pPartOps(n: Int): Double =
  val ps = js.Array[PV3]()
  val vs = js.Array[PV3]()
  var i = 0
  while i < np do
    initPos(i, (x, y, z) => ps.push(PV3(x, y, z)))
    initVel(i, (x, y, z) => vs.push(PV3(x, y, z)))
    i += 1
  frames(n): i =>
    val v = (vs(i) + pgravity * dt) * damping
    vs(i) = v
    ps(i) = ps(i) + v * dt
  ps(0).y

@JSExportTopLevel("pPartSelfNoAlloc", moduleID = "bench")
def pPartSelfNoAlloc(n: Int): Double =
  val ps = js.Array[PV3]()
  val vs = js.Array[PV3]()
  var i = 0
  while i < np do
    initPos(i, (x, y, z) => ps.push(PV3(x, y, z)))
    initVel(i, (x, y, z) => vs.push(PV3(x, y, z)))
    i += 1
  val gdt = pgravity * dt
  val tmp = PV3(0.0, 0.0, 0.0)
  frames(n): i =>
    val v = vs(i)
    v += gdt
    v *= damping
    v.mulS(dt, tmp)
    ps(i) += tmp
  ps(0).y

/** Packed (pos, vel) F32 particles; a fresh ref per field access, as
  * `StructArray(i)(k)` does. `mk(byteOffset)` builds the ref.
  */
private inline def partRefs[R](n: Int, inline mk: Int => R, gdt: R, tmp: R)(using
    PMut[R],
    PMutOps[R],
): Double =
  var i = 0
  while i < np do
    val p = mk(i * 24)
    val v = mk(i * 24 + 12)
    p.x = (i % 100).toDouble
    p.y = 0.0
    p.z = (i / 100).toDouble
    v.x = 0.0
    v.y = (i % 7).toDouble
    v.z = 1.0
    i += 1
  gdt.x = 0.0
  gdt.y = -9.81 * dt
  gdt.z = 0.0
  frames(n): i =>
    val pos = mk(i * 24)
    val vel = mk(i * 24 + 12)
    vel += gdt
    vel *= damping
    vel.mulS(dt, tmp)
    pos += tmp
  mk(0).y

@JSExportTopLevel("pPartJRef", moduleID = "bench")
def pPartJRef(n: Int): Double =
  val dv = new DataView(new ArrayBuffer(np * 24))
  partRefs(n, o => JRef(dv, o), JRef(dv12(), 0), JRef(dv12(), 0))

@JSExportTopLevel("pPartSRef", moduleID = "bench")
def pPartSRef(n: Int): Double =
  val dv = new DataView(new ArrayBuffer(np * 24))
  partRefs(n, o => SRef(dv, o), SRef(dv12(), 0), SRef(dv12(), 0))

@JSExportTopLevel("pPartFRef", moduleID = "bench")
def pPartFRef(n: Int): Double =
  val f = new Float32Array(np * 6)
  val g = new Float32Array(6)
  partRefs(n, o => FRef(f, o >> 2), FRef(g, 0), FRef(g, 3))

// =============================================================== cursors
// One ref per role, its offset moved per element: no allocation per access.

/** Particles through two reused cursors. `seek(ref, byteOffset)` moves one. */
private inline def partCursors[R](
    n: Int,
    pos: R,
    vel: R,
    gdt: R,
    tmp: R,
    inline seek: (R, Int) => Unit,
)(using PMut[R], PMutOps[R]): Double =
  var i = 0
  while i < np do
    seek(pos, i * 24)
    seek(vel, i * 24 + 12)
    pos.x = (i % 100).toDouble
    pos.y = 0.0
    pos.z = (i / 100).toDouble
    vel.x = 0.0
    vel.y = (i % 7).toDouble
    vel.z = 1.0
    i += 1
  gdt.x = 0.0
  gdt.y = -9.81 * dt
  gdt.z = 0.0
  frames(n): i =>
    seek(pos, i * 24)
    seek(vel, i * 24 + 12)
    vel += gdt
    vel *= damping
    vel.mulS(dt, tmp)
    pos += tmp
  seek(pos, 0)
  pos.y

/** trivalibs' BufferView (js.Object, DataView) as a cursor: possible today. */
@JSExportTopLevel("pPartJCursor", moduleID = "bench")
def pPartJCursor(n: Int): Double =
  val dv = new DataView(new ArrayBuffer(np * 24))
  val p = new BufferView(dv, 0)
  val v = new BufferView(dv, 0)
  partCursors(n, JRef(p.dv, 0), JRef(v.dv, 0), JRef(dv12(), 0), JRef(dv12(), 0), (r, o) => r.asInstanceOf[BufferView].off = o)

final class FCursor(val a: Float32Array, var off: Int)
opaque type FCur = FCursor

object FCur:
  inline def apply(a: Float32Array, off: Int): FCur = new FCursor(a, off)
  extension (c: FCur) inline def seek(floatOffset: Int): Unit = c.off = floatOffset
  given PMut[FCur]:
    extension (v: FCur)
      inline def x = v.a(v.off): Double
      inline def y = v.a(v.off + 1): Double
      inline def z = v.a(v.off + 2): Double
      inline def x_=(s: Double) = v.a(v.off) = s.toFloat
      inline def y_=(s: Double) = v.a(v.off + 1) = s.toFloat
      inline def z_=(s: Double) = v.a(v.off + 2) = s.toFloat
  given PMutOps[FCur] = new PMutOps[FCur] {}

/** Float32Array cursor (plain Scala class). */
@JSExportTopLevel("pPartFCursor", moduleID = "bench")
def pPartFCursor(n: Int): Double =
  val f = new Float32Array(np * 6)
  val g = new Float32Array(6)
  partCursors(n, FCur(f, 0), FCur(f, 0), FCur(g, 0), FCur(g, 3), (c, o) => c.seek(o >> 2))
