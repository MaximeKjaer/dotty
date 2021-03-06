ThisBuild / scalaVersion := sys.props("plugin.scalaVersion")
ThisBuild / organization := "org.example"

lazy val a = project
  .settings(
    name := "a",
    version := "0.1.1-SNAPSHOT",
    libraryDependencies := Seq(),  // don't depend on scala-library
  )

lazy val b = project
  .settings(onlyThisTestResolverSettings)
  .settings(
    name := "b",
    libraryDependencies := Seq(organization.value %% "a" % "0.1.0-SNAPSHOT"),
  )
