package dotty.tools.dotc.ptyper

import dotty.tools.dotc.core.{Contexts, Flags}
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.transform.SymUtils._

import scala.collection.mutable


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
  case class SumType(symbol: Symbol, children: List[ADT]) extends ADT {
    def descendants: List[ADT] = children ++ children.flatMap {
      case child: SumType => child.descendants
      case _              => Nil
    }
  }
  case class CandidateADT(symbol: Symbol) extends Classification
  case class Other(symbol: Symbol) extends Classification

  // Overriding the following breaks equality and thus ADT behavior:
  private val ADTSynthetics = List(
    StdNames.nme.equals_,  // We need equality to keep ADT behavior
    StdNames.nme.canEqual_ // .equals() relies on it in its default implementation
  )


  private val cache: mutable.Map[Symbol, Classification] = mutable.Map.empty

  def classify(symbol: Symbol)(implicit ctx: Contexts.Context): Classification = {
    if (cache.contains(symbol)) cache(symbol)
    else {
      val res = _classify(symbol)
      cache(symbol) = res
      res
    }
  }

  def isADT(symbol: Symbol)(implicit ctx: Contexts.Context): Boolean = isADT(classify(symbol))

  private def isADT(classification: Classification): Boolean = classification match {
    case _: ADT => true
    case _      => false
  }

  private def _classify(symbol: Symbol)(implicit ctx: Contexts.Context): Classification = {
    // Classes overriding ADT synthetics cannot be trusted to be ADTs.
    def breaksADTBehavior(symbol: Symbol): Boolean =
      ADTSynthetics.exists(method => symbol.overrides(method, excluded = Flags.Synthetic))

    // Assuming that we trust the parents don't override, classify:
    def classifyTrusted(symbol: Symbol): Classification = {
      val isCaseClass = symbol.is(Flags.Case) && !symbol.is(Flags.Module)
      val isSealedTrait = symbol.is(Flags.Sealed & Flags.Trait)
      val overrides = breaksADTBehavior(symbol)

      if (overrides && (isCaseClass || isSealedTrait))
        CandidateADT(symbol)
      else if (isCaseClass)
        ProductType(symbol)
      else if (isSealedTrait) {
        val children = symbol.children.map(classifyTrusted)
        if (children.forall(isADT)) {
          children.foreach(child => cache(child.symbol) = child)
          SumType(symbol, children.asInstanceOf[List[ADT]])
        }
        else
          CandidateADT(symbol)
      }
      else
        Other(symbol)
    }

    val isUntrusted = symbol.ancestors.exists(breaksADTBehavior)
    if (isUntrusted) CandidateADT(symbol)
    else classifyTrusted(symbol)
  }
}
