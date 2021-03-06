import collection.mutable

class MaxSizeMap[K, V](maxSize: Int)(using o: Ordering[K]) with
  val sortedMap: mutable.TreeMap[K, V] = mutable.TreeMap.empty[K, V](o)

  export sortedMap._
