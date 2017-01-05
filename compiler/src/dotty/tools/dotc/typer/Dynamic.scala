package dotty.tools
package dotc
package typer

import dotty.tools.dotc.ast.Trees._
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Names.{Name, TermName}
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.core.Decorators._
import core.Symbols._
import core.Definitions
import Inferencing._
import ErrorReporting._

object Dynamic {
  def isDynamicMethod(name: Name): Boolean =
    name == nme.applyDynamic || name == nme.selectDynamic || name == nme.updateDynamic || name == nme.applyDynamicNamed
}

/** Handles programmable member selections of `Dynamic` instances and values
 *  with structural types. Two functionalities:
 *
 * 1. Translates selection that does not typecheck according to the scala.Dynamic rules:
 *    foo.bar(baz) = quux                   ~~> foo.selectDynamic(bar).update(baz, quux)
 *    foo.bar = baz                         ~~> foo.updateDynamic("bar")(baz)
 *    foo.bar(x = bazX, y = bazY, baz, ...) ~~> foo.applyDynamicNamed("bar")(("x", bazX), ("y", bazY), ("", baz), ...)
 *    foo.bar(baz0, baz1, ...)              ~~> foo.applyDynamic(bar)(baz0, baz1, ...)
 *    foo.bar                               ~~> foo.selectDynamic(bar)
 *
 *  The first matching rule of is applied.
 *
 * 2. Translates member seclections on structural types by means of an implicit
 *    Projector instance. @See handleStructural.
 *
 */
trait Dynamic { self: Typer with Applications =>
  import Dynamic._
  import tpd._

  /** Translate selection that does not typecheck according to the normal rules into a applyDynamic/applyDynamicNamed.
   *    foo.bar(baz0, baz1, ...)                       ~~> foo.applyDynamic(bar)(baz0, baz1, ...)
   *    foo.bar[T0, ...](baz0, baz1, ...)              ~~> foo.applyDynamic[T0, ...](bar)(baz0, baz1, ...)
   *    foo.bar(x = bazX, y = bazY, baz, ...)          ~~> foo.applyDynamicNamed("bar")(("x", bazX), ("y", bazY), ("", baz), ...)
   *    foo.bar[T0, ...](x = bazX, y = bazY, baz, ...) ~~> foo.applyDynamicNamed[T0, ...]("bar")(("x", bazX), ("y", bazY), ("", baz), ...)
   */
  def typedDynamicApply(tree: untpd.Apply, pt: Type)(implicit ctx: Context): Tree = {
    def typedDynamicApply(qual: untpd.Tree, name: Name, targs: List[untpd.Tree]): Tree = {
      def isNamedArg(arg: untpd.Tree): Boolean = arg match { case NamedArg(_, _) => true; case _ => false }
      val args = tree.args
      val dynName = if (args.exists(isNamedArg)) nme.applyDynamicNamed else nme.applyDynamic
      if (dynName == nme.applyDynamicNamed && untpd.isWildcardStarArgList(args))
        errorTree(tree, "applyDynamicNamed does not support passing a vararg parameter")
      else {
        def namedArgTuple(name: String, arg: untpd.Tree) = untpd.Tuple(List(Literal(Constant(name)), arg))
        def namedArgs = args.map {
          case NamedArg(argName, arg) => namedArgTuple(argName.toString, arg)
          case arg => namedArgTuple("", arg)
        }
        val args1 = if (dynName == nme.applyDynamic) args else namedArgs
        typedApply(untpd.Apply(coreDynamic(qual, dynName, name, targs), args1), pt)
      }
    }

     tree.fun match {
      case Select(qual, name) if !isDynamicMethod(name) =>
        typedDynamicApply(qual, name, Nil)
      case TypeApply(Select(qual, name), targs) if !isDynamicMethod(name) =>
        typedDynamicApply(qual, name, targs)
      case TypeApply(fun, targs) =>
        typedDynamicApply(fun, nme.apply, targs)
      case fun =>
        typedDynamicApply(fun, nme.apply, Nil)
    }
  }

  /** Translate selection that does not typecheck according to the normal rules into a selectDynamic.
   *    foo.bar          ~~> foo.selectDynamic(bar)
   *    foo.bar[T0, ...] ~~> foo.selectDynamic[T0, ...](bar)
   *
   *  Note: inner part of translation foo.bar(baz) = quux ~~> foo.selectDynamic(bar).update(baz, quux) is achieved
   *  through an existing transformation of in typedAssign [foo.bar(baz) = quux ~~> foo.bar.update(baz, quux)].
   */
  def typedDynamicSelect(tree: untpd.Select, targs: List[Tree], pt: Type)(implicit ctx: Context): Tree =
    typedApply(coreDynamic(tree.qualifier, nme.selectDynamic, tree.name, targs), pt)

  /** Translate selection that does not typecheck according to the normal rules into a updateDynamic.
   *    foo.bar = baz ~~> foo.updateDynamic(bar)(baz)
   */
  def typedDynamicAssign(tree: untpd.Assign, pt: Type)(implicit ctx: Context): Tree = {
    def typedDynamicAssign(qual: untpd.Tree, name: Name, targs: List[untpd.Tree]): Tree =
      typedApply(untpd.Apply(coreDynamic(qual, nme.updateDynamic, name, targs), tree.rhs), pt)
    tree.lhs match {
      case Select(qual, name) if !isDynamicMethod(name) =>
        typedDynamicAssign(qual, name, Nil)
      case TypeApply(Select(qual, name), targs) if !isDynamicMethod(name) =>
        typedDynamicAssign(qual, name, targs)
      case _ =>
        errorTree(tree, "reassignment to val")
    }
  }

  private def coreDynamic(qual: untpd.Tree, dynName: Name, name: Name, targs: List[untpd.Tree])(implicit ctx: Context): untpd.Apply = {
    val select = untpd.Select(qual, dynName)
    val selectWithTypes =
      if (targs.isEmpty) select
      else untpd.TypeApply(select, targs)
    untpd.Apply(selectWithTypes, Literal(Constant(name.toString)))
  }

  /** Handle reflection-based dispatch for members of structural types.
   *  Given `x.a`, where `x` is of (widened) type `T` and `x.a` is of type `U`:
   *
   *  If `U` is a value type, map `x.a` to the equivalent of:
   *
   *     implicitly[Projector[T]].get(x, "a").asInstanceOf[U]
   *
   *  If `U` is a method type (T1,...,Tn)R, map `x.a` to the equivalent of:
   *
   *     implicitly[Projector[T]].getMethod(x, "a")(CT1, ..., CTn).asInstanceOf[(T1,...,Tn) => R]
   *
   *  where CT1,...,CTn are the classtags representing the erasure of T1,...,Tn.
   *
   *  The small print: (1) T is forced to be fully defined. (2) It's an error if
   *  U is neither a value nor a method type, or a dependent method type, or of too
   *  large arity (limit is Definitions.MaxStructuralMethodArity).
   */
  def handleStructural(tree: Tree)(implicit ctx: Context): Tree = {
    val Select(qual, name) = tree

    def issueError(msgFn: String => String): Unit = ctx.error(msgFn("reflective call"), tree.pos)
    def implicitArg(tpe: Type) = inferImplicitArg(tpe, issueError, tree.pos.endPos)
    val projector = implicitArg(defn.ProjectorType.appliedTo(qual.tpe.widen))

    def structuralCall(getterName: TermName, formals: List[Tree]) = {
      val scall = untpd.Apply(
        untpd.TypedSplice(projector.select(getterName)),
        (qual :: Literal(Constant(name.toString)) :: formals).map(untpd.TypedSplice(_)))
      typed(scall)
    }
    def fail(reason: String) =
      errorTree(tree, em"Structural access not allowed on method $name because it $reason")
    fullyDefinedType(tree.tpe.widen, "structural access", tree.pos) match {
      case tpe: MethodType =>
        if (tpe.isDependent)
          fail(i"has a dependent method type")
        else if (tpe.paramNames.length > Definitions.MaxStructuralMethodArity)
          fail(i"takes too many parameters")
        val ctags = tpe.paramTypes.map(pt =>
          implicitArg(defn.ClassTagType.appliedTo(pt :: Nil)))
        structuralCall(nme.getMethod, ctags).asInstance(tpe.toFunctionType())
      case tpe: ValueType =>
        structuralCall(nme.get, Nil).asInstance(tpe)
      case tpe: PolyType =>
        fail("is polymorphic")
      case tpe =>
        fail(i"has an unsupported type: $tpe")
    }
  }
}
