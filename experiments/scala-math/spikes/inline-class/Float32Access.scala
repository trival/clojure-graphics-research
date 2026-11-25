package probe

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Float32Array

/** Facade over the global Float32Array with Double element types. */
@js.native
@JSGlobal("Float32Array")
class F32Array(len: Int) extends js.Object:
  @JSBracketAccess def apply(i: Int): Double = js.native
  @JSBracketAccess def update(i: Int, v: Double): Unit = js.native
  val length: Int = js.native

/** Opaque view over the standard facade, Double access by cast. */
object buf:
  opaque type F32 = Float32Array
  object F32:
    inline def apply(n: Int): F32 = new Float32Array(n)
  extension (a: F32)
    inline def apply(i: Int): Double = a.asInstanceOf[js.Array[Double]](i)
    inline def update(i: Int, v: Double): Unit = a.asInstanceOf[js.Array[Double]](i) = v
    inline def raw: Float32Array = a
import buf.*

@JSExportTopLevel("facade", moduleID = "probe")
def facade(n: Int): Double =
  val a = new F32Array(n * 2)
  var i = 0
  while i < n do
    a(i * 2) = i * 0.5
    a(i * 2 + 1) = a(i * 2) + 0.25
    i += 1
  a(5)

@JSExportTopLevel("cast", moduleID = "probe")
def cast(n: Int): Double =
  val a = F32(n * 2)
  var i = 0
  while i < n do
    a(i * 2) = i * 0.5
    a(i * 2 + 1) = a(i * 2) + 0.25
    i += 1
  a(5)
