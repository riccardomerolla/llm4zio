ThisBuild / scalaVersion := "3.3.1"
ThisBuild / organization := "io.github.riccardomerolla"
ThisBuild / organizationName := "Riccardo Merolla"
ThisBuild / organizationHomepage := Some(url("https://github.com/riccardomerolla"))

inThisBuild(List(
  organization := "io.github.riccardomerolla",
  homepage := Some(url("https://github.com/riccardomerolla/zio-quickstart")),
  licenses := Seq(
    "MIT" -> url("https://opensource.org/license/mit")
  ),
  developers := List(
    Developer(
      id = "riccardomerolla",
      name = "Riccardo Merolla",
      email = "riccardo.merolla@gmail.com",
      url = url("https://github.com/riccardomerolla")
    )
  ),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/riccardomerolla/zio-quickstart"),
      "scm:git@github.com:riccardomerolla/zio-quickstart.git"
    )
  ),
  versionScheme := Some("early-semver")
))

lazy val root = (project in file("."))
  .settings(
    name := "zio-quickstart",
    description := "Template repository for starting new Scala 3 / ZIO 2.x libraries",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.22",
      "dev.zio" %% "zio-streams" % "2.1.22",
      "dev.zio" %% "zio-schema" % "1.7.5",
      "dev.zio" %% "zio-schema-derivation" % "1.7.5",
      "dev.zio" %% "zio-schema-json" % "1.7.5",
      "dev.zio" %% "zio-json" % "0.7.45",
      "dev.zio" %% "zio-test" % "2.1.22" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.22" % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
