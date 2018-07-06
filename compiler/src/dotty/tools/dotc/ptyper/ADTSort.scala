package dotty.tools.dotc.ptyper

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.transform.SymUtils._

case class ADTSort(sort: Symbol, constructors: Seq[Symbol])

object ADTSort {
  def fromSymbol(symbol: Symbol)(implicit ctx: Context): Option[ADTSort] = {
    val isAbstract = isAbstractADT(symbol)
    val isConstructor = isADTConstructor(symbol)
    val adtBreakingBehavior = hasADTBreakingBehavior(symbol) || inheritsADTBreakingBehavior(symbol)
    val isADT = symbol.isClass && (isAbstract || isConstructor) && !adtBreakingBehavior

    if (isADT) {
      if (isAbstract) {
        val sort = symbol
        val constructors = findConstructors(sort)
        constructors.map(ADTSort(sort, _))
      } else {
        val sort = findSort(symbol)
        val constructors = sort.flatMap(findConstructors)
        if (sort.isDefined && constructors.isDefined) {
          Some(ADTSort(sort.get, constructors.get))
        } else {
          Some(ADTSort(symbol, List(symbol)))
        }
      }
    } else None
  }

  def isADT(symbol: Symbol)(implicit ctx: Context): Boolean = ADTSort.fromSymbol(symbol).isDefined

  // Overriding the following breaks equality and thus ADT behavior:
  private val ADTSynthetics = List(
    StdNames.nme.equals_,  // We need equality to keep ADT behavior
    StdNames.nme.canEqual_ // .equals() relies on it in its default implementation
  )

  private def findSort(constructorSymbol: Symbol)(implicit ctx: Context): Option[Symbol] = {
    assert(isADTConstructor(constructorSymbol))
    val parents = constructorSymbol.asClass.classParents.map(_.classSymbol)
    parents.find(isAbstractADT)
  }

  private def findConstructors(sortSymbol: Symbol)(implicit ctx: Context): Option[List[Symbol]] = {
    assert(isAbstractADT(sortSymbol))
    val children = sortSymbol.children
    val adt = children.forall(child => !hasADTBreakingBehavior(child) && isADTConstructor(child))
    if (adt) Some(children)
    else None
  }

  private def hasADTBreakingBehavior(symbol: Symbol)(implicit ctx: Context): Boolean =
    ADTSynthetics.exists(method => symbol.overrides(method, excluded = Flags.Synthetic))

  private def inheritsADTBreakingBehavior(symbol: Symbol)(implicit ctx: Context): Boolean =
    symbol.ancestors.exists(hasADTBreakingBehavior)

  def isAbstractADT(symbol: Symbol)(implicit ctx: Context): Boolean =
    symbol.is(Flags.Sealed) && symbol.is(Flags.AbstractOrTrait)

  def isADTConstructor(symbol: Symbol)(implicit ctx: Context): Boolean =
    symbol.is(Flags.Case)
}
