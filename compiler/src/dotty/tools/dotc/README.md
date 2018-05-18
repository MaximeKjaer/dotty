# Maxime writes Dotty code

## Introduction 

Dotty is Scala's next-generation, experimental compiler. Its goal is to have a solid theoretical foundation (see [DOT](http://lampwww.epfl.ch/~amin/dot/fool.pdf)), add support for [some cool features](http://dotty.epfl.ch/#so-features), and as much as possible, better performance and stability.


## Getting to know the code

Dotty's code is, by far, the largest code base I've ever worked with. It's very well written, but also quite complex at first. Thus, to get to know the code a little but, I've spent some time doing a little exercise on the compiler. My goal was simple: fork Dotty, and add a compiler phase that prints out case classes in the AST.

### Printing case classes

The entry of the compiler is in a file called `Compiler.scala`. It builds up the compiler pipeline by specifying which phases should run. This makes it fairly easy for me to add my own phase; I could just add it to the list of phases.

So I added a phase that I called `CaseClassPrinter`:

```scala
protected def frontendPhases: List[List[Phase]] =
    List(new FrontEnd) ::
    List(new CaseClassPrinter) ::        // My own phase!
    List(new sbt.ExtractDependencies) ::
    List(new PostTyper) ::
    List(new sbt.ExtractAPI) ::
    Nil
```

I placed it in second position, right after the `FrontEnd` phase. `FrontEnd` contains the lexer, parser and typer; after running those, we should have an AST that all other phases can act on.

As a first definition of the phase, let's just print out the full AST; this will allow us to dump the AST for different files, and thus see what case classes look like.

Let's define a `Phase` class and implement the abstract methods in `Phase`:

- `phaseName` lets us define a phase name that we can use as a command-line argument to pretty-print the AST after the phase has executed
- `run` is somewhat self-explanatory: it is called to run the phase, and should contain the phase's code

The `ctx: Context` is ["passed basically everywhere"](https://github.com/lampepfl/dotty/blob/0.7.x/compiler/src/dotty/tools/dotc/core/Contexts.scala#L51). It contains a lot of information, among which the "compilation unit". As far as I understand, every file gives rise to a compilation unit being executed (since a compilation can involve compiling multiple files). Inside the compilation unit, we can find the typed AST `tdpTree`:

```scala
class CaseClassPrinter extends Phase {
  override def phaseName: String = "ccprinter"

  /** @pre `isRunnable` returns true */
  override def run(implicit ctx: Contexts.Context): Unit = {
    println(ctx.compilationUnit.tpdTree)
  }
}
```

Now, let's write a simple input file for our modified compiler:

```scala
case class Test(x: Int)
```

We should have all we need to run the following:

```
dotc -Xprint:ccprinter Test.scala
```

Running the above prints out the AST:

```
PackageDef(Ident(<empty>),List(TypeDef(Test,Template(DefDef(<init>,List(),List(List(ValDef(x,Ident(Int),EmptyTree))),TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class scala)),class Unit)],EmptyTree),List(Apply(Select(New(TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class lang)),class Object)]),<init>),List()), Select(Select(Ident(_root_),scala),Product)),ValDef(_,EmptyTree,EmptyTree),List(ValDef(x,TypeTree[TypeRef(TermRef(ThisType(TypeRef(NoPrefix,module class <root>)),module scala),class Int)],EmptyTree), DefDef(copy,List(),List(List(ValDef(x,TypeTree[TypeRef(TermRef(ThisType(TypeRef(NoPrefix,module class <root>)),module scala),class Int)],EmptyTree))),TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class <empty>)),class Test)],Apply(Select(New(TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class <empty>)),class Test)]),<init>),List(Ident(x)))), DefDef(copy$default$1,List(),List(),TypeTree[TypeRef(TermRef(ThisType(TypeRef(NoPrefix,module class <root>)),module scala),class Int)],Typed(Ident(x),TypeTree[AnnotatedType(TermRef(ThisType(TypeRef(ThisType(TypeRef(NoPrefix,module class <empty>)),class Test)),val x),ConcreteAnnotation(Apply(Select(New(TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class unchecked)),class uncheckedVariance)]),<init>),List())))])), DefDef(_1,List(),List(),TypeTree[TypeRef(TermRef(ThisType(TypeRef(NoPrefix,module class <root>)),module scala),class Int)],Select(This(Ident()),x))))), ValDef(Test,Ident(Test$),Apply(Select(New(Ident(Test$)),<init>),List())), TypeDef(Test$,Template(DefDef(<init>,List(),List(List()),TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class scala)),class Unit)],EmptyTree),List(Apply(Select(New(TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class lang)),class Object)]),<init>),List()), AppliedTypeTree(TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class scala)),class Function1)],List(Ident(Int), TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class <empty>)),class Test)]))),ValDef(_,SingletonTypeTree(Ident(Test)),EmptyTree),List(DefDef(apply,List(),List(List(ValDef(x,TypeTree[TypeRef(TermRef(ThisType(TypeRef(NoPrefix,module class <root>)),module scala),class Int)],EmptyTree))),TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class <empty>)),class Test)],Apply(Select(New(TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class <empty>)),class Test)]),<init>),List(Ident(x)))), DefDef(unapply,List(),List(List(ValDef(x$1,TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class <empty>)),class Test)],EmptyTree))),TypeTree[TypeRef(ThisType(TypeRef(NoPrefix,module class <empty>)),class Test)],Ident(x$1)))))))
```

This is a lot of stuff! The pretty-printed representation of the AST is the following:

```scala
package \<empty> {
  case class Test(x: Int) extends Object() with _root_.scala.Product { 
    val x: Int
    def copy(x: Int): Test = new Test(x)
    def copy$default$1: Int = Test.this.x: Int(Test.this.x) @uncheckedVariance
    def _1: Int = this.x
  }
  final lazy module val Test: Test$ = new Test$()
  final module class Test$() extends Object() with Function1[Int, Test] { 
    this: Test.type =>
   
    def apply(x: Int): Test = new Test(x)
    def unapply(x$1: Test): Test = x$1
  }
}
```

As we can see here, the compiler adds quite a bit of boilerplate code to represent case classes. And this is still at an early stage of compilation. If I place my `CaseClassPrinter` phase further down the list of phases, there are other phases that add a bunch more stuff: for instance, the `PostTyper` adds `equals` and `hashCode` implementations to the case class.

Next up, we need to filter AST nodes to print which ones are case classes.

My first intuition here was to traverse the AST and pick up `Apply` and `TypeDef` nodes. Looking at the parser, it was clear that the AST started with an `EmptyTree` or a `PackageDef` ([source](https://github.com/lampepfl/dotty/blob/97a6b4a0b8c1c35943a80514fc0e4b0048f87be1/compiler/src/dotty/tools/dotc/parsing/Parsers.scala#L2523-L2527)), and that top-level definitions of case classes were in the `stats` (shorthand for "statements") field of a `PackageDef`.

It works fine to manually pattern match through the AST, but there are many different node types, so this would involve a lot of code, and I wanted to find a simpler way to traverse the AST. It turns out that there are (at least) two ways of doing so:

- There is a [`TreeTraverser`](https://github.com/lampepfl/dotty/blob/0.7.x/compiler/src/dotty/tools/dotc/ast/Trees.scala#L1337) class, in which I would only have to redefine `traverse` using the provided `traverseChildren()` method.
- Alternatively, many of the further compiler phases extend `MiniPhase`. A bit of background on `MiniPhase`s: one of the core concepts of Dotty is its [ability to group phases](https://youtu.be/wCFbYu7xEJA?t=1h3m13s) together in a single traversal. As such, the `MiniPhase` class provides a mechanism in which you can register a hook when a certain kind of node is visited. This is done by defining a `transformNodeType`, where `NodeType` is the name of the AST node to transform. These methods take an AST node, and return the transformed AST node. I'm not transforming anything, so I can just return the node I was given, and log the fact that I saw it.

I went for the second option, as it would most likely be the easiest to write.

We know from the [parser code](https://github.com/lampepfl/dotty/blob/0.7.x/compiler/src/dotty/tools/dotc/parsing/Parsers.scala#L2168-L2169) that case class definitions are `TypeDef` nodes with a `Case` flag, so those are fairly easy to detect.

Detecting calls to a case class are a little more involved. You can count `apply` and `unapply` with [similar-looking syntaxes](https://docs.scala-lang.org/tour/extractor-objects.html), but we only want to check for application. Luckily, `InlineCaseIntrinsics.scala` has some code to detect synthetic applies on case classes, so I essentially just stole that snippet and put it into `transformApply`.

I'm also overriding `runOn` to add some `println`s after its usual execution (doing it on `runOn` instead of `run` allows me to print after *all* compilation units, instead of after each compilation unit).

```scala
class CaseClassPrinter extends MiniPhase {
  override def phaseName: String = "ccprinter"

  private val caseClasses = new mutable.MutableList[String]
  private val caseClassCalls = new mutable.MutableList[String]

  override def transformTypeDef(tree: tpd.TypeDef)(implicit ctx: Contexts.Context): tpd.Tree = {
    if (tree.symbol.is(Flags.Case)) {
      caseClasses += tree.name.toString
    }
    tree
  }

  override def transformApply(tree: tpd.Apply)(implicit ctx: Contexts.Context): tpd.Tree = {
    // Stolen from InlineCaseIntrinsics.scala:
    if (!tree.tpe.isInstanceOf[MethodicType]           &&
        tree.symbol.is(Synthetic)                      &&
        tree.symbol.owner.is(Module)                   &&
        tree.symbol.name == nme.apply                  &&
        tree.symbol.owner.companionClass.is(CaseClass) &&
        !tree.tpe.derivesFrom(defn.EnumClass)          &&
        (isPureExpr(tree.fun) || tree.fun.symbol.is(Synthetic))) {
      caseClassCalls += tree.symbol.owner.name.toString
    }
    tree
  }

  /** @pre `isRunnable` returns true */
  override def runOn(units: List[CompilationUnit])(implicit ctx: Contexts.Context): List[CompilationUnit] = {
    def list[T](items: Seq[T]): String = items.mkString(" - ", "\n - ", "\n")
    val res = super.runOn(units)
    println("Case classes:")
    println(list(caseClasses))
    println("Case class calls:")
    println(list(caseClassCalls))
    res
  }
}
```

This seems to do the job; with the following program:

```scala
object Test {
  case class Test(x: Int)
  case class Message(x: String)

  abstract class Term
  case class Var(name: String) extends Term
  case class Fun(arg: String, body: Term) extends Term
  case class App(f: Term, v: Term) extends Term
  case class Modified(x: Int) {
    override def equals(o: scala.Any): Boolean = true
  }

  def main(): Unit = {
    case class Test(x: Int)
    println(Message("Hello World"))
    println(Modified(1) equals Modified(1))
  }
}
```

The modified compiler now prints:

```
Case classes:
 - Test
 - Message
 - Var
 - Fun
 - App
 - Modified
 - Test

Case class calls:
 - Message$
 - Modified$
 - Modified$
```

### Printing ADTs

The next step is to distinguish case classes behaving like ADTs.

## Introduction to some Scala type constructs

`implicitly` is defined [as follows](https://github.com/scala/scala/blob/v2.12.5/src/library/scala/Predef.scala#L187):

```scala
def implicitly[T](implicit e: T) = e
```

It checks that an implicit value of type `T` exists, and returns it. If there is no implicit value, this will result in a *compile-time error*. This is useful to take implicit `Ordering` for instance:

```scala
implicitly[Ordering[(Int, Int)]].compare(1, 2)
```


`=:=` is a [generalized type constraint](http://blog.bruchez.name/2015/11/generalized-type-constraints-in-scala.html). The above link is an excellent, in-depth read, but for now let's just be consider this as an operator that checks at compile-time that two types are equal.

Now to PR #3887:

```scala
val x = 1
val y: {x} = x
implicitly[{x + 1} =:= {y + 1}]  // works
```

## Flags
Flags are represented internally by a bit vector (type `Long`).

The 2 least significant bits (LSB) indicate whether a `FlagSet` applies to terms (`01`), types (`10`) or both (`11`).

Bits 2-63 hold flags, and can be doubly used for terms and types. Flags are declared in the following way:

```scala
/** A method symbol. */
final val Method = termFlag(7, "<method>")
final val HigherKinded = typeFlag(7, "<higher kinded>")
```

Here, `Method` is a term flag placed at bit 7, and `HigherKinded` is a type flag at bit 7, meaning that we have the following internal representation:

```
Method:       0000000000000000000000000000000000000000010000001
HigherKinded: 0000000000000000000000000000000000000000010000010
```

Unions and intersections of flags are then simply implemented using the binary operators `&` and `|`; the `Flags` object then proceeds to define a host of nice utility methods and pretty-printing of flags, which I won't go into detail about.

A common error that can arise when adding flags to a `FlagSet` is that it raise an exception about "illegal flagset combination"; here's the code behind it:

```scala
/** The union of this flag set and the given flag set */
def | (that: FlagSet): FlagSet =
  if (bits == 0) that
  else if (that.bits == 0) this
  else {
    val tbits = bits & that.bits & KINDFLAGS
    if (tbits == 0)
      assert(false, s"illegal flagset combination: $this and $that")
    FlagSet(tbits | ((this.bits | that.bits) & ~KINDFLAGS))
  }
```

`KINDFLAGS` is a mask that is defined as `11` on the two LSB; this means that `tbits` ends with `00` if we try to add type flags and term flags together, which raises an exception.


## Scala objects

### Class
Regular classes link data and operations to provide mutability. This approach is closer to the object-oriented paradigm.

### Case class
Case classes are a representation of a data structure with the necessary methods. Do not code mutable state in these.

They can be considered product types, and are thus ADTs. We use the phrases “has a” and “and” when talking about Product types. `Pair` has a `a` and a `b` (meaning something like `Pair = a x b`).

- [Functional Programming, Simplified: Algebraic Data Types in Scala](https://alvinalexander.com/scala/fp-book/algebraic-data-types-adts-in-scala#toc_11)
- [Maxime's notes on funprog, § Case Classes](https://kjaer.io/funprog/#case-classes)

### Final case class
Defining a case class as `final` disallows it from being extended. This can be a good idea, as it disallows things like extending a `case class` with a `class`.

- [StackOverflow: Should I use the final modifier when declaring case classes?](https://stackoverflow.com/questions/34561614/should-i-use-the-final-modifier-when-declaring-case-classes)

### Object
An object is a value. It has singleton functionality: you don't create a new instance every time you use one. It is created lazily when it is referenced, like a lazy val.

- `object O` creates a singleton object `O` as instance of some anonymous class
- `object O extends T` makes the object `O` an instance of `trait T`

- [StackOverflow: difference between an object and a class](https://stackoverflow.com/questions/1755345/difference-between-object-and-class-in-scala)
- [Scala Docs: Singleton objects](https://docs.scala-lang.org/tour/singleton-objects.html)

### Case object
Like an `object`, but with added support for `unapply` (which is super useful for pattern matching), and also `Serializable`, `equals`, `hashCode`, `toString`. Unlike a case class, it does not take arguments.

### Companion object
> An object with the same name as a class is called a companion object. Conversely, the class is the object’s companion class. A companion class or object can access the private members of its companion. Use a companion object for methods and values which are not specific to instances of the companion class.

Commonly, the companion object is used for factory methods and other things that would be `static` in Java.

- [Scala Docs: Companion objects](https://docs.scala-lang.org/tour/singleton-objects.html#companion-objects)

### Trait
A "trait" is a generalization of Java's interface that can contain both abstract and concrete methods. Classes and objects can extend traits but traits cannot be instantiated and therefore have no parameters.

- [Martin Odersky, Unifying Functional and Object-Oriented Programming with Scala](https://cacm.acm.org/magazines/2014/4/173220-unifying-functional-and-object-oriented-programming-with-scala/fulltext)
- [Scala Documentation on Traits](https://docs.scala-lang.org/tour/traits.html)

### Sealed trait
A `sealed trait` can be extended only in the same file as its declaration.

They are often used to provide an alternative to `enums`. Since they can be only extended in a single file, the compiler knows every possible subtypes and can reason about it; this allows for exhaustiveness checking in pattern matching.

Subtypes of sealed traits can be considered sum types, and are thus ADTs. We use the phrases “is a” and “or” when talking about sum types. `Shape` is a `Circle` or a `Rectangle` (meaning something like `Shape = Circle + Rectangle`, or `Direction = Up + Down + Left + Right`).

Note that when we say "or", we mean "xor"; otherwise, the equality wouldn't hold.

- [Algebraic Data Types in Scala](https://tpolecat.github.io/presentations/algebraic_types.html#11)
- [StackOverflow: What is a sealed trait?](https://stackoverflow.com/questions/11203268/what-is-a-sealed-trait)
- [Functional Programming, Simplified: Algebraic Data Types in Scala](https://alvinalexander.com/scala/fp-book/algebraic-data-types-adts-in-scala#toc_9)

### Enum
In Scala 2, an Enumeration is a sum type of `Value`s. In Dotty, new syntactic sugar has been implemented to represent (G)ADTs as enums.

- [Dotty Reference: Enumerations](http://dotty.epfl.ch/docs/reference/enums/enums.html)
- [Scala Documentation: Enumeration](https://www.scala-lang.org/api/current/scala/Enumeration.html)
