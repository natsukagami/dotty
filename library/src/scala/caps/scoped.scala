package scala.caps.scoped

import language.experimental.captureChecking
import annotation.experimental

/** An opaque type over `t` stating that the hidden set of `T` is extended by the calling function.
 */
@experimental
opaque type scoped[+T] = T

@experimental
object scoped:
  extension[T](s: scoped[T]^)
    def underlying: T^{s} = s
  /** Consumes `value`, allowing it to be used in a strictly scoped way. */
  def apply[T, U](consume value: T)(f: scoped[T]^ => U) = f(value)
  /** Binds all captures of the value `x` into the hidden set of the scoped variable `scope`.
   *  The returned value captures instead only `scope`.
   *  This call is handled specially by the compiler.
   */
  def capture[T](value: T^)(scope: scoped[Any^]): T^{scope} = ???
