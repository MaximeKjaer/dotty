package dotty.tools.dotc.ptyper

import dotty.tools.dotc.core.{Contexts, Flags, Names}
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.core.SymDenotations.NoDenotation
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.transform.SymUtils._

object ADTClassifier {
  /**
    * Classification -+- ADT -+- ProductType
    *                 |       +- SumType
    *                 +- CandidateADT
    *                 +- Other
    */
  sealed trait Classification {
    val symbol: Symbol
  }
  sealed trait ADT extends Classification
  case class ProductType(symbol: Symbol) extends ADT
  case class SumType(symbol: Symbol) extends ADT
  case class CandidateADT(symbol: Symbol) extends Classification
  case class Other(symbol: Symbol) extends Classification


  // Overriding the following breaks equality and thus ADT behavior:
  private val ADTSynthetics = List(
    StdNames.nme.equals_,  // We need equality to keep ADT behavior
    StdNames.nme.canEqual_ // .equals() relies on it in its default implementation
  )

  def classifySymbol(symbol: Symbol)(implicit ctx: Contexts.Context): Classification = {
    def breakADTBehavior(syms: List[Symbol]): Boolean = syms.exists(breaksADTBehavior)
    def breaksADTBehavior(sym: Symbol): Boolean = ADTSynthetics.exists(overriddenBy(sym, _))
    def overriddenBy(sym: Symbol, name: Names.TermName): Boolean = {
      val method = sym.info.findDecl(name, excluded = Flags.Synthetic)
      method match {
        case NoDenotation => false
        case _ => method.symbol.is(Flags.Override)
      }
    }

    def descendants(sym: Symbol): List[Symbol] = {
      val children = sym.children
      children ++ children.flatMap(descendants)
    }

    val isCaseClass = symbol.is(Flags.Case) && !symbol.is(Flags.Module) // see ../ast/Desugar.scala
    val isSealedTrait = symbol.is(Flags.Sealed) && symbol.is(Flags.Trait)
    val parents = symbol.info.baseClasses.map(_.symbol)
    val isProductType = !breaksADTBehavior(symbol) && !breakADTBehavior(parents)

    if (isCaseClass) {
      if (isProductType) ProductType(symbol)
      else CandidateADT(symbol)
    } else if (isSealedTrait) {
      val children = descendants(symbol)
      val isSumType = !breakADTBehavior(children)
      if (isProductType && isSumType) SumType(symbol)
      else CandidateADT(symbol)
    } else Other(symbol)
  }

  def isADT(symbol: Symbol)(implicit ctx: Contexts.Context): Boolean = classifySymbol(symbol) match {
    case _: ADT => true
    case _ => false
  }
}