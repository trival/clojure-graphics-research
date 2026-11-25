package kinds

import scala.scalajs.js
import scala.scalajs.js.annotation.*

class VPlain(var x: Double = 0.0, var y: Double = 0.0)
final class VFinal(var x: Double = 0.0, var y: Double = 0.0)
class VJs(var x: Double = 0.0, var y: Double = 0.0) extends js.Object

private val a1 = VPlain(1.0, 2.0)
private val a2 = VFinal(1.0, 2.0)
private val a3 = VJs(1.0, 2.0)

@JSExportTopLevel("plain", moduleID = "kinds")
def plain(n: Int): Double =
  var acc = 0.0
  var i = 0
  while i < n do
    val r = VPlain(a1.x + i * 1e-7, a1.y)
    acc += r.x
    i += 1
  acc

@JSExportTopLevel("fin", moduleID = "kinds")
def fin(n: Int): Double =
  var acc = 0.0
  var i = 0
  while i < n do
    val r = VFinal(a2.x + i * 1e-7, a2.y)
    acc += r.x
    i += 1
  acc

@JSExportTopLevel("jsobj", moduleID = "kinds")
def jsobj(n: Int): Double =
  var acc = 0.0
  var i = 0
  while i < n do
    val r = VJs(a3.x + i * 1e-7, a3.y)
    acc += r.x
    i += 1
  acc
