package bench

// Shared inputs and helpers for ClassOps.scala and Mixed.scala.

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.*

import trivalibs.bufferdata.*
import trivalibs.graphics.math.cpu.{*, given}
import trivalibs.graphics.scene.PerspectiveCamera
import trivalibs.utils.numbers.NumExt.given

// =============================================================== mix
// out = a*(1-t) + b*t on vec3, t = i * 1e-7, checksum sums out.x

private[bench] val ca = Vec3(1.0, 2.0, 3.0)
private[bench] val cb = Vec3(3.0, 2.0, 1.0)
// =============================================================== particles
// 10,000 particles; vel += g*dt; vel *= damping; pos += vel*dt.
// n / np frames; checksum is particle 0's pos.y. ns per particle step.

private[bench] inline val np = 10000
private[bench] inline val dt = 0.016
private[bench] inline val damping = 0.99

private[bench] inline def frames(n: Int)(inline step: Int => Unit): Unit =
  val fs = n / np
  var f = 0
  while f < fs do
    var i = 0
    while i < np do
      step(i)
      i += 1
    f += 1

private[bench] def initPos(i: Int, set: (Double, Double, Double) => Unit): Unit =
  set((i % 100).toDouble, 0.0, (i / 100).toDouble)

private[bench] def initVel(i: Int, set: (Double, Double, Double) => Unit): Unit =
  set(0.0, (i % 7).toDouble, 1.0)

private[bench] def classParticles(): js.Array[js.Array[Vec3]] =
  val ps = js.Array[Vec3]()
  val vs = js.Array[Vec3]()
  var i = 0
  while i < np do
    initPos(i, (x, y, z) => ps.push(Vec3(x, y, z)))
    initVel(i, (x, y, z) => vs.push(Vec3(x, y, z)))
    i += 1
  js.Array(ps, vs)

private[bench] val gravity = Vec3(0.0, -9.81, 0.0)
// =============================================================== combos
// One operand combination per loop over 1024-element operand arrays, results
// freshly allocated (as combos.cljs `mono`). Checksum sums the result's x.

private[bench] inline val nops = 1024

private[bench] def mkArr[A](f: Int => A): js.Array[A] =
  val a = js.Array[A]()
  var i = 0
  while i < nops do
    a.push(f(i))
    i += 1
  a

private[bench] lazy val v3s = mkArr(i => Vec3(i * 0.5, i * 0.25, 1.0))
private[bench] lazy val v4s = mkArr(i => Vec4(i * 0.5, i * 0.25, 1.0, 2.0))
private[bench] lazy val m4s = mkArr(i =>
  Mat4.fromTranslationRotationScale(
    Vec3(i, 1.0, 2.0),
    Quat.fromRotationY(i * 0.01),
    Vec3(1.0, 1.0, 1.0),
  ),
)

private[bench] inline def combo(n: Int)(inline f: Int => Double): Double =
  var acc = 0.0
  var i = 0
  while i < n do
    acc += f(i & (nops - 1))
    i += 1
  acc
