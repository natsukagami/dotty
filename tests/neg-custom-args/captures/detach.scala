import language.experimental.captureChecking
import caps.*
import caps.scoped.*

class Mut extends Mutable:
  def read(): Int = 0
  update def write(): Unit = ()

class A extends Mut
class B extends Mut:
  val a = A()

def f1() =
  val a: (A^) @scoped = A()
  val b = B()
  bindCapturesTo(b, a)
  b.write() // error

def f2() =
  val a: (A^) @scoped = A()
  val b = B()
  val _ =
    val t = A()
    bindCapturesTo(t, a)
    t.write() // error

def f3() =
  val a: (A^) @scoped = A()
  val b = B()
  val _ = () =>
    bindCapturesTo(b, a) // ?
  b.write()

def f4() =
  val a: (A^) @scoped = A()
  val b = B()
  val f = (x: B^) => bindCapturesTo(x, a) // ?
  f(b)
  b.write()

def f5() =
  val a: (A^) @scoped = A()
  val b = B()
  // val f = (@consume x: B^) => bindCapturesTo(x, a) // ok
  // f(b)
  b.write() // not error

def f6() =
  val b = B()
  val _ =
    val a: (A^) @scoped = A()
    bindCapturesTo(b, a)
    b.write() // error

def f6_ok() =
  val b = B()
  val _ =
    val a: (A^) @scoped = A()
    bindCapturesTo(b, a)
  b.write() // ok
