object Test {

  def main(args: Array[String]): Unit = {
    val f: (erased x: Int) => Int =
      (erased x: Int) => { println("lambda"); 42 }
    f(foo)
  }

  def foo = {
    println("foo")
    42
  }
}
