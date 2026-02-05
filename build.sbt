ThisBuild / scalaVersion := "3.5.2"
ThisBuild / organization := "io.github.riccardomerolla"
ThisBuild / organizationName := "Riccardo Merolla"
ThisBuild / organizationHomepage := Some(url("https://github.com/riccardomerolla"))

addCommandAlias("fmt", " ; scalafixAll ; scalafmtAll")
addCommandAlias("check", "; scalafixAll --check; scalafmtCheckAll")

inThisBuild(List(
  organization := "io.github.riccardomerolla",
  homepage := Some(url("https://github.com/riccardomerolla/zio-legacy-modernization-agent")),
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
      url("https://github.com/riccardomerolla/zio-legacy-modernization-agent"),
      "scm:git@github.com:riccardomerolla/zio-legacy-modernization-agent.git"
    )
  ),
  versionScheme := Some("early-semver"),
  scalacOptions ++= Seq(
    "-language:existentials",
    "-explain",
    "-Wunused:all",
  ),
  semanticdbEnabled                := true,
))

lazy val root = (project in file("."))
  .settings(
    name := "zio-legacy-modernization-agent",
    description := "A ZIO Legacy to Modernization Agent built with ZIO and Scala 3",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.24",
      "dev.zio" %% "zio-streams" % "2.1.24",
      "dev.zio" %% "zio-json" % "0.9.0",
      "dev.zio" %% "zio-test" % "2.1.24" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.24" % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    coverageExcludedPackages := ".*\\.example\\..*",
    coverageMinimumStmtTotal := 70,
    coverageFailOnMinimum := true,
    coverageHighlighting := true,
    run / javaOptions ++= Seq(
      "--enable-native-access=ALL-UNNAMED",
      "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    ),
  )
