package dotty.tools.dotc

import org.junit.Test
import org.junit.Ignore
import org.junit.Assert._

import dotty.tools.io._
import dotty.tools.dotc.util.ClasspathFromClassloader

import scala.quoted._

import java.io.File.pathSeparator
import java.io.File.separator

class BootstrappedStdLibTASYyTest:

  import BootstrappedStdLibTASYyTest._

  /** Test that we can load trees from TASTy */
  @Test def testTastyInspector: Unit =
    loadWithTastyInspector(loadBlacklisted)

  /** Test that we can load and compile trees from TASTy */
  @Test def testFromTasty: Unit =
    compileFromTasty(loadBlacklisted.union(compileBlacklisted))

  @Test def blacklistNoDuplicates =
    def testDup(name: String, list: List[String], set: Set[String]) =
      assert(list.size == set.size,
        list.diff(set.toSeq).mkString(s"`$name` has duplicate entries:\n  ", "\n  ", "\n\n"))
    testDup("loadBlacklist", loadBlacklist, loadBlacklisted)
    testDup("compileBlacklist", compileBlacklist, compileBlacklisted)

  @Test def blacklistsNoIntersection =
    val intersection = loadBlacklisted & compileBlacklisted
    assert(intersection.isEmpty,
      intersection.mkString(
        "`compileBlacklist` contains names that are already in `loadBlacklist`: \n  ", "\n  ", "\n\n"))

  @Test def blacklistsOnlyContainsClassesThatExist =
    val scalaLibJarTastyClassNamesSet = scalaLibJarTastyClassNames.toSet
    val intersection = loadBlacklisted & compileBlacklisted
    assert(loadBlacklisted.diff(scalaLibJarTastyClassNamesSet).isEmpty,
      loadBlacklisted.diff(scalaLibJarTastyClassNamesSet).mkString(
        "`loadBlacklisted` contains names that are not in `scalaLibJarTastyClassNames`: \n  ", "\n  ", "\n\n"))
    assert(compileBlacklisted.diff(scalaLibJarTastyClassNamesSet).isEmpty,
      compileBlacklisted.diff(scalaLibJarTastyClassNamesSet).mkString(
        "`loadBlacklisted` contains names that are not in `scalaLibJarTastyClassNames`: \n  ", "\n  ", "\n\n"))

  @Ignore
  @Test def testLoadBacklistIsMinimal =
    var shouldBeWhitelisted = List.empty[String]
    val size = loadBlacklisted.size
    for (notBlacklisted, i) <- loadBlacklist.zipWithIndex do
      val blacklist = loadBlacklisted - notBlacklisted
      println(s"Trying withouth $notBlacklisted in the blacklist  (${i+1}/$size)")
      try {
        loadWithTastyInspector(blacklist)
        shouldBeWhitelisted = notBlacklisted :: shouldBeWhitelisted
      }
      catch {
        case ex: Throwable => // ok
      }
    assert(shouldBeWhitelisted.isEmpty,
      shouldBeWhitelisted.mkString("Some classes do not need to be blacklisted in `loadBlacklisted`\n  ", "\n  ", "\n\n"))

  @Ignore
  @Test def testCompileBlacklistIsMinimal =
    var shouldBeWhitelisted = List.empty[String]
    val size = compileBlacklisted.size
    val blacklist0 = loadBlacklisted.union(compileBlacklisted)
    for (notBlacklisted, i) <- compileBlacklist.zipWithIndex do
      val blacklist = blacklist0 - notBlacklisted
      println(s"Trying withouth $notBlacklisted in the blacklist (${i+1}/$size)")
      try {
        compileFromTasty(blacklist)
        shouldBeWhitelisted = notBlacklisted :: shouldBeWhitelisted
      }
      catch {
        case ex: Throwable => // ok
      }
    assert(shouldBeWhitelisted.isEmpty,
      shouldBeWhitelisted.mkString("Some classes do not need to be blacklisted in `compileBlacklisted`\n  ", "\n  ", "\n\n"))

end BootstrappedStdLibTASYyTest

object BootstrappedStdLibTASYyTest:

  val scalaLibJarPath = System.getProperty("dotty.scala.library")

  val scalaLibJarTastyClassNames = {
    val scalaLibJar = Jar(new File(java.nio.file.Paths.get(scalaLibJarPath)))
    scalaLibJar.toList.map(_.toString).filter(_.endsWith(".tasty"))
      .map(_.stripSuffix(".tasty").replace("/", "."))
      .sorted
  }

  def loadWithTastyInspector(blacklisted: String => Boolean): Unit =
    val inspector = new scala.tasty.inspector.TastyInspector {
      def processCompilationUnit(using QuoteContext)(root: qctx.reflect.Tree): Unit =
        root.showExtractors // Check that we can traverse the full tree
        ()
    }
    val classNames = scalaLibJarTastyClassNames.filterNot(blacklisted)
    val hasErrors = inspector.inspectTastyFilesInJar(scalaLibJarPath)
    assert(!hasErrors, "Errors reported while loading from TASTy")

  def compileFromTasty(blacklisted: Iterable[String]): Unit = {
    val driver = new dotty.tools.dotc.Driver
    val yFromTastyBlacklist =
      blacklisted.map(x => x.replace(".", separator) + ".tasty").mkString("-Yfrom-tasty-blacklist:", ",", "")
    val args = Array(
      "-classpath", ClasspathFromClassloader(getClass.getClassLoader),
      "-from-tasty",
      "-nowarn",
      yFromTastyBlacklist,
      scalaLibJarPath,
    )
    val reporter = driver.process(args)
    assert(reporter.errorCount == 0, "Errors while re-compiling")
  }

  /** List of classes that cannot be loaded from TASTy */
  def loadBlacklist = List[String](
    // No issues :)
  )

  /** List of classes that cannot be recompilied from TASTy */
  def compileBlacklist = List[String](
    // See #10048
    // failed: java.lang.AssertionError: assertion failed: class Boolean
    //   at dotty.DottyPredef$.assertFail(DottyPredef.scala:17)
    //   at dotty.tools.backend.jvm.BCodeHelpers$BCInnerClassGen.assertClassNotArrayNotPrimitive(BCodeHelpers.scala:247)
    //   at dotty.tools.backend.jvm.BCodeHelpers$BCInnerClassGen.getClassBTypeAndRegisterInnerClass(BCodeHelpers.scala:265)
    //   at dotty.tools.backend.jvm.BCodeHelpers$BCInnerClassGen.getClassBTypeAndRegisterInnerClass$(BCodeHelpers.scala:210)
    //   at dotty.tools.backend.jvm.BCodeSkelBuilder$PlainSkelBuilder.getClassBTypeAndRegisterInnerClass(BCodeSkelBuilder.scala:62)
    //   at dotty.tools.backend.jvm.BCodeHelpers$BCInnerClassGen.internalName(BCodeHelpers.scala:237)
    "scala.Array",
    "scala.Boolean",
    "scala.Byte",
    "scala.Char",
    "scala.Double",
    "scala.Float",
    "scala.Int",
    "scala.Long",
    "scala.Short",
    "scala.Unit",
  )

  /** Set of classes that cannot be loaded from TASTy */
  def loadBlacklisted = loadBlacklist.toSet

  /** Set of classes that cannot be recompilied from TASTy */
  def compileBlacklisted = compileBlacklist.toSet

end BootstrappedStdLibTASYyTest
