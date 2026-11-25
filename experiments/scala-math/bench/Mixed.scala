package bench

// Workloads on Vec3Tuple and StructRef/StructArray, plus the per-frame scene
// workloads. Linking these next to ClassOps.scala makes the shared ops traits
// polymorphic, as in a program that uses more than one representation.

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.*

import trivalibs.bufferdata.*
import trivalibs.graphics.math.cpu.{*, given}
import trivalibs.graphics.scene.PerspectiveCamera
import trivalibs.utils.numbers.NumExt.given

private val ta: Vec3Tuple = (1.0, 2.0, 3.0)
private val tb: Vec3Tuple = (3.0, 2.0, 1.0)
private lazy val t3s = mkArr[Vec3Tuple](i => (i * 0.5, i * 0.25, 1.0))

/** Operators on Vec3Tuple (scala.Tuple3). */
@JSExportTopLevel("mixTuple", moduleID = "bench")
def mixTuple(n: Int): Double =
  var acc = 0.0
  var i = 0
  while i < n do
    val t = i * 1e-7
    val r = ta * (1.0 - t) + tb * t
    acc += r.x
    i += 1
  acc

/** In-place library API on StructRef[Vec3Buffer] (F32, DataView). */
@JSExportTopLevel("mixStructF32", moduleID = "bench")
def mixStructF32(n: Int): Double =
  val a = struct[Vec3Buffer]()
  val b = struct[Vec3Buffer]()
  val out = struct[Vec3Buffer]()
  val tmp = struct[Vec3Buffer]()
  a := ca
  b := cb
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

/** In-place library API on StructRef[Vec3dBuffer] (F64, DataView). */
@JSExportTopLevel("mixStructF64", moduleID = "bench")
def mixStructF64(n: Int): Double =
  val a = struct[Vec3dBuffer]()
  val b = struct[Vec3dBuffer]()
  val out = struct[Vec3dBuffer]()
  val tmp = struct[Vec3dBuffer]()
  a := ca
  b := cb
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

/** Componentwise accessors on StructRef[Vec3dBuffer]: what `#cpu` emits, on
  * DataView storage.
  */
@JSExportTopLevel("mixStructF64Hand", moduleID = "bench")
def mixStructF64Hand(n: Int): Double =
  val a = struct[Vec3dBuffer]()
  val b = struct[Vec3dBuffer]()
  val out = struct[Vec3dBuffer]()
  a := ca
  b := cb
  var acc = 0.0
  var i = 0
  while i < n do
    val t = i * 1e-7
    val s = 1.0 - t
    out.x = a.x * s + b.x * t
    out.y = a.y * s + b.y * t
    out.z = a.z * s + b.z * t
    acc += out.x
    i += 1
  acc

private type Particle = (Vec3Buffer, Vec3Buffer)
private type ParticleD = (Vec3dBuffer, Vec3dBuffer)

/** Packed StructArray of (pos, vel) F32, in-place library API. */
@JSExportTopLevel("partStructF32", moduleID = "bench")
def partStructF32(n: Int): Double =
  val arr = StructArray.allocate[Particle](np)
  var i = 0
  while i < np do
    val p = arr(i)
    initPos(i, (x, y, z) => p.set0((x.toFloat, y.toFloat, z.toFloat)))
    initVel(i, (x, y, z) => p.set1((x.toFloat, y.toFloat, z.toFloat)))
    i += 1
  val gdt = struct[Vec3Buffer]()
  gdt := gravity * dt
  val tmp = struct[Vec3Buffer]()
  frames(n): i =>
    val pos = arr(i)(0)
    val vel = arr(i)(1)
    vel += gdt
    vel *= damping
    vel.mulS(dt, tmp)
    pos += tmp
  (arr(0)(0).y: Double)

/** Packed StructArray of (pos, vel) F64, in-place library API. */
@JSExportTopLevel("partStructF64", moduleID = "bench")
def partStructF64(n: Int): Double =
  val arr = StructArray.allocate[ParticleD](np)
  var i = 0
  while i < np do
    val p = arr(i)
    initPos(i, (x, y, z) => p.set0((x, y, z)))
    initVel(i, (x, y, z) => p.set1((x, y, z)))
    i += 1
  val gdt = struct[Vec3dBuffer]()
  gdt := gravity * dt
  val tmp = struct[Vec3dBuffer]()
  frames(n): i =>
    val pos = arr(i)(0)
    val vel = arr(i)(1)
    vel += gdt
    vel *= damping
    vel.mulS(dt, tmp)
    pos += tmp
  arr(0)(0).y

/** Same packed F32 layout through a raw DataView, componentwise: separates
  * DataView cost from StructRef's wrapper objects.
  */
@JSExportTopLevel("partDataViewF32", moduleID = "bench")
def partDataViewF32(n: Int): Double =
  val dv = new DataView(new ArrayBuffer(np * 24))
  inline def g(o: Int): Double = dv.getFloat32(o, true)
  inline def s(o: Int, v: Double): Unit = dv.setFloat32(o, v.toFloat, true)
  var i = 0
  while i < np do
    initPos(i, (x, y, z) => { s(i * 24, x); s(i * 24 + 4, y); s(i * 24 + 8, z) })
    initVel(i, (x, y, z) => { s(i * 24 + 12, x); s(i * 24 + 16, y); s(i * 24 + 20, z) })
    i += 1
  frames(n): i =>
    val o = i * 24
    s(o + 16, g(o + 16) + -9.81 * dt)
    s(o + 12, g(o + 12) * damping)
    s(o + 16, g(o + 16) * damping)
    s(o + 20, g(o + 20) * damping)
    s(o, g(o) + g(o + 12) * dt)
    s(o + 4, g(o + 4) + g(o + 16) * dt)
    s(o + 8, g(o + 8) + g(o + 20) * dt)
  g(4)


@JSExportTopLevel("comboT3AddT3", moduleID = "bench")
def comboT3AddT3(n: Int): Double =
  val a = t3s
  combo(n)(k => (a(k) + a((k + 1) & (nops - 1))).x)

// =============================================================== sway
// rooms/canvases `animate`: per painting, a TRS model matrix from a swayed
// position, written to its uniform buffer (StructRef[Mat4Buffer], DataView F32),
// plus a Vec4 shadow rect. ns per painting.

private val up = Vec3(0.0, 1.0, 0.0)
private inline val nobj = 64

private final class Painting(
    val basePos: Vec3,
    val rotY: Double,
    val baseRect: Vec4,
    val model: StructRef[Mat4Buffer],
    val shadowRect: StructRef[Vec4Buffer],
)

private def paintings(): js.Array[Painting] =
  val a = js.Array[Painting]()
  var i = 0
  while i < nobj do
    a.push(
      Painting(
        Vec3(i * 1.5, 1.6, -4.0),
        i * 0.3,
        Vec4(0.1, 0.2, 0.3, 0.4),
        struct[Mat4Buffer](),
        struct[Vec4Buffer](),
      ),
    )
    i += 1
  a

/** As written in Canvases.scala: Mat4/Quat/Vec3/Vec4 temporaries, then `:=`. */
@JSExportTopLevel("swayOps", moduleID = "bench")
def swayOps(n: Int): Double =
  val ps = paintings()
  var acc = 0.0
  var i = 0
  while i < n do
    val pt = ps(i & (nobj - 1))
    val s = 0.05 * (i * 1e-4 + pt.rotY).sin
    pt.model := Mat4.fromTranslationRotationScale(
      pt.basePos + up * s,
      Quat.fromRotationY(pt.rotY),
      Vec3(1.0, 1.0, 1.0),
    )
    pt.shadowRect := Vec4(
      pt.baseRect.x,
      pt.baseRect.y - s / 3.0,
      pt.baseRect.z,
      pt.baseRect.w,
    )
    acc += pt.model.m31
    i += 1
  acc

/** Same result, composed into a Float32Array without temporaries (what a
  * scalarizing `#cpu`-style form would emit for this code).
  */
@JSExportTopLevel("swayHandF32", moduleID = "bench")
def swayHandF32(n: Int): Double =
  val ps = paintings()
  val models = new Float32Array(nobj * 16)
  val rects = new Float32Array(nobj * 4)
  var acc = 0.0
  var i = 0
  while i < n do
    val k = i & (nobj - 1)
    val pt = ps(k)
    val s = 0.05 * (i * 1e-4 + pt.rotY).sin
    val h = pt.rotY * 0.5
    val qy = h.sin
    val qw = h.cos
    val y2 = qy + qy
    val yy = qy * y2
    val wy = qw * y2
    val o = k * 16
    models(o) = (1 - yy).toFloat
    models(o + 1) = 0.0f
    models(o + 2) = (-wy).toFloat
    models(o + 3) = 0.0f
    models(o + 4) = 0.0f
    models(o + 5) = 1.0f
    models(o + 6) = 0.0f
    models(o + 7) = 0.0f
    models(o + 8) = wy.toFloat
    models(o + 9) = 0.0f
    models(o + 10) = (1 - yy).toFloat
    models(o + 11) = 0.0f
    models(o + 12) = pt.basePos.x.toFloat
    models(o + 13) = (pt.basePos.y + s).toFloat
    models(o + 14) = pt.basePos.z.toFloat
    models(o + 15) = 1.0f
    val r = k * 4
    rects(r) = pt.baseRect.x.toFloat
    rects(r + 1) = (pt.baseRect.y - s / 3.0).toFloat
    rects(r + 2) = pt.baseRect.z.toFloat
    rects(r + 3) = pt.baseRect.w.toFloat
    acc += models(o + 13)
    i += 1
  acc

/** Camera: `cam.viewProjMat` written to a uniform, once per "frame". */
@JSExportTopLevel("camViewProj", moduleID = "bench")
def camViewProj(n: Int): Double =
  val cam = PerspectiveCamera(aspect = 1.6, pos = Vec3(0.0, 1.6, 4.0))
  val vp = struct[Mat4Buffer]()
  var acc = 0.0
  var i = 0
  while i < n do
    cam.rotH = i * 1e-5
    vp := cam.viewProjMat
    acc += vp.m00
    i += 1
  acc
