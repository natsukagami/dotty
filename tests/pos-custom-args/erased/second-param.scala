def f(a: Int, erased b: Int) = a

erased val b: Int = 15

val v = f(29, b)
