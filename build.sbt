ThisBuild / scalaVersion := "3.5.2"
ThisBuild / organization := "io.github.riccardomerolla"
ThisBuild / organizationName := "Riccardo Merolla"
ThisBuild / organizationHomepage := Some(url("https://github.com/riccardomerolla"))

addCommandAlias("fmt", " ; scalafixAll ; scalafmtAll")
addCommandAlias("check", "; scalafixAll --check; scalafmtCheckAll")

// Centralized version management
val zioVersion = "2.1.24"
val zioJsonVersion = "0.9.0"
val zioHttpVersion = "3.8.1"
val zioLoggingVersion = "2.4.0"
val zioConfigVersion = "4.0.6"
val zioCliVersion = "0.7.5"
val zioOpentelemetryVersion = "3.0.0"
val scalatagsVersion = "0.13.1"
val logbackVersion = "1.5.12"
val logstashLogbackVersion = "7.4"
val opentelemetryVersion = "1.44.1"
val sqliteJdbcVersion = "3.47.2.0"
val proleapCobolVersion = "v2.4.0"

// Common dependencies shared across modules
val zioCoreDeps = Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-streams" % zioVersion,
)

val zioJsonDep = "dev.zio" %% "zio-json" % zioJsonVersion

val zioHttpDep = "dev.zio" %% "zio-http" % zioHttpVersion

val zioLoggingDeps = Seq(
  "dev.zio" %% "zio-logging" % zioLoggingVersion,
)

val zioTestDeps = Seq(
  "dev.zio" %% "zio-test" % zioVersion % "test,it",
  "dev.zio" %% "zio-test-sbt" % zioVersion % "test,it",
  "dev.zio" %% "zio-test-magnolia" % zioVersion % "test,it",
)

val llm4zioDeps = zioCoreDeps ++ Seq(
  zioJsonDep,
  zioHttpDep,
) ++ zioLoggingDeps ++ zioTestDeps

val rootDeps = zioCoreDeps ++ Seq(
  zioJsonDep,
  "dev.zio" %% "zio-cli" % zioCliVersion,
  "dev.zio" %% "zio-config" % zioConfigVersion,
  "dev.zio" %% "zio-config-typesafe" % zioConfigVersion,
  "dev.zio" %% "zio-config-magnolia" % zioConfigVersion,
  zioHttpDep,
  "com.lihaoyi" %% "scalatags" % scalatagsVersion,
  "dev.zio" %% "zio-logging-slf4j2" % zioLoggingVersion,
  "ch.qos.logback" % "logback-classic" % logbackVersion,
  "net.logstash.logback" % "logstash-logback-encoder" % logstashLogbackVersion,
  "dev.zio" %% "zio-opentelemetry" % zioOpentelemetryVersion,
  "dev.zio" %% "zio-opentelemetry-zio-logging" % zioOpentelemetryVersion,
  "io.opentelemetry" % "opentelemetry-sdk" % opentelemetryVersion,
  "io.opentelemetry" % "opentelemetry-exporter-otlp" % opentelemetryVersion,
  "io.opentelemetry" % "opentelemetry-exporter-logging-otlp" % opentelemetryVersion,
  "org.xerial" % "sqlite-jdbc" % sqliteJdbcVersion,
  "com.github.uwol" % "proleap-cobol-parser" % proleapCobolVersion,
) ++ zioLoggingDeps ++ zioTestDeps

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
    "-Xmax-inlines",
    "128",
  ),
  semanticdbEnabled                := true,
))

lazy val It = config("it") extend Test

resolvers += "jitpack" at "https://jitpack.io"

lazy val llm4zio = (project in file("llm4zio"))
  .configs(It)
  .settings(inConfig(It)(Defaults.testSettings): _*)
  .settings(
    name := "llm4zio",
    description := "ZIO-native LLM framework",
    // Handle version conflicts - prefer newer versions
    libraryDependencySchemes += "dev.zio" %% "zio-json" % VersionScheme.Always,
    libraryDependencies ++= llm4zioDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    It / testFrameworks ++= (Test / testFrameworks).value,
  )

lazy val root = (project in file("."))
  .dependsOn(llm4zio)
  .configs(It)
  .settings(inConfig(It)(Defaults.testSettings): _*)
  .settings(
    name := "zio-legacy-modernization-agent",
    description := "A ZIO Legacy to Modernization Agent built with ZIO and Scala 3",
    // Handle version conflicts - prefer newer versions
    libraryDependencySchemes += "dev.zio" %% "zio-json" % VersionScheme.Always,
    libraryDependencies ++= rootDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    It / testFrameworks ++= (Test / testFrameworks).value,
    coverageExcludedPackages := "<empty>;.*\\.example\\..*;web.views;web.controllers;web$;db;orchestration",
    coverageExcludedFiles := ".*Main\\.scala",
    coverageMinimumStmtTotal := 80,
    coverageFailOnMinimum := true,
    coverageHighlighting := true,
    run / fork := true,
    run / javaOptions ++= Seq(
      "--enable-native-access=ALL-UNNAMED",
      "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    ),
  )
