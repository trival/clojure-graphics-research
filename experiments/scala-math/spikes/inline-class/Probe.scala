package probe

import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.*

@inline final class VRef(val a: Float32Array, val off: Int):
  @inline def set0(x: Double, y: Double): Unit =
    a(off) = x.toFloat
    a(off + 1) = y.toFloat
  @inline def set1(u: Double, v: Double): Unit =
    a(off + 2) = u.toFloat
    a(off + 3) = v.toFloat

final class VArr(val a: Float32Array, val count: Int):
  @inline def apply(i: Int): VRef = new VRef(a, i * 4)

@JSExportTopLevel("fill", moduleID = "probe")
def fill(n: Int): Double =
  val v = new VArr(new Float32Array(n * 4), n)
  var i = 0
  while i < n do
    v(i).set0(i * 0.5, i * 0.25)
    v(i).set1(0.0, 1.0)
    i += 1
  v.a(5)
