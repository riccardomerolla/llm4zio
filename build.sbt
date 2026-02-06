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

lazy val It = config("it") extend Test

lazy val root = (project in file("."))
  .configs(It)
  .settings(inConfig(It)(Defaults.testSettings): _*)
  .settings(
    name := "zio-legacy-modernization-agent",
    description := "A ZIO Legacy to Modernization Agent built with ZIO and Scala 3",
    // Handle version conflicts - prefer newer versions
    libraryDependencySchemes += "dev.zio" %% "zio-json" % VersionScheme.Always,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.24",
      "dev.zio" %% "zio-streams" % "2.1.24",
      "dev.zio" %% "zio-json" % "0.9.0",
      "dev.zio" %% "zio-cli" % "0.7.5",
      "dev.zio" %% "zio-config" % "4.0.6",
      "dev.zio" %% "zio-config-typesafe" % "4.0.6",
      "dev.zio" %% "zio-config-magnolia" % "4.0.6",
      "dev.zio" %% "zio-logging" % "2.4.0",
      "dev.zio" %% "zio-logging-slf4j2" % "2.4.0",
      "ch.qos.logback" % "logback-classic" % "1.5.12",
      "dev.zio" %% "zio-opentelemetry" % "3.0.0",
      "dev.zio" %% "zio-opentelemetry-zio-logging" % "3.0.0",
      "io.opentelemetry" % "opentelemetry-sdk" % "1.44.1",
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % "1.44.1",
      "io.opentelemetry" % "opentelemetry-exporter-logging-otlp" % "1.44.1",
      "dev.zio" %% "zio-test" % "2.1.24" % "test,it",
      "dev.zio" %% "zio-test-sbt" % "2.1.24" % "test,it",
      "dev.zio" %% "zio-test-magnolia" % "2.1.24" % "test,it"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    It / testFrameworks ++= (Test / testFrameworks).value,
    coverageExcludedPackages := "<empty>;.*\\.example\\..*",
    coverageExcludedFiles := ".*Main\\.scala",
    coverageMinimumStmtTotal := 80,
    coverageFailOnMinimum := true,
    coverageHighlighting := true,
    run / javaOptions ++= Seq(
      "--enable-native-access=ALL-UNNAMED",
      "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    ),
  )
