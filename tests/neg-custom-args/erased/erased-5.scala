object Test {

  type UU[T] = (erased uuv1: T) => Int

  def trueErased(erased x: Int): Int = 0

  def main(args: Array[String]): Unit = {
    fun { x => // error: `Int => Int` not compatible with `(erased Int) => Int`
      x
    }

    fun {
      (x: Int) => x // error: `Int => Int` not compatible with `(erased Int) => Int`
    }
  }

  def fun(f: UU[Int]): Int = {
    f(35)
  }
}
