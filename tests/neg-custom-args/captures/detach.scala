import language.experimental.captureChecking
import caps.*
import caps.scoped.*

class Mut extends Mutable:
  def read(): Int = 0
  update def write(): Unit = ()

class A extends Mut
class B extends Mut

def f1() =
  val a: (A^) @scoped = A()
  val b = B()
  b.bindCapturesTo(a)
  b.write() // error

def f2() =
  val a: (A^) @scoped = A()
  val b = B()
  val _ =
    val t = A()
    t.bindCapturesTo(a)
    t.write() // error

def f3() =
  val a: (A^) @scoped = A()
  val b = B()
  val _ = () =>
    b.bindCapturesTo(a) // error?
  b.write()

def f4() =
  val a: (A^) @scoped = A()
  val b = B()
  val f = (x: B^) => x.bindCapturesTo(a) // error?
  f(b)
  b.write()

def f5() =
  val a: (A^) @scoped = A()
  val b = B()
  val f = (@consume x: B^) => x.bindCapturesTo(a) // ok
  f(b)
  b.write() // error
