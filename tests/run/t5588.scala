object Test {
  object MyEnum extends Enumeration {
    val Foo = Value(2000000000)
    val Bar = Value(-2000000000)
    val X   = Value(Integer.MAX_VALUE)
    val Y   = Value(Integer.MIN_VALUE)
  }

  import MyEnum.*
  def main(args: Array[String]): Unit = {
    println(Foo > Bar)
    println(X > Y)
  }
}
