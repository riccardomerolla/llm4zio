ThisBuild / scalaVersion := "3.8.3"
ThisBuild / organization := "io.github.riccardomerolla"
ThisBuild / organizationName := "Riccardo Merolla"
ThisBuild / organizationHomepage := Some(url("https://github.com/riccardomerolla"))
// scalameta pulls in a Scala 2.13 sourcecode; keep the 3.x one excluded to avoid a conflict.
ThisBuild / excludeDependencies += ExclusionRule("com.lihaoyi", "sourcecode_3")
ThisBuild / dependencyOverrides += "com.lihaoyi" % "sourcecode_2.13" % "0.4.2"

addCommandAlias("fmt", " ; scalafixAll ; scalafmtAll")
addCommandAlias("check", "; scalafixAll --check; scalafmtCheckAll")

// ── Centralized version management ────────────────────────────────────────────
val zioVersion        = "2.1.25"
val zioProcessVersion = "0.8.0"
val zioJsonVersion    = "0.9.0"
val zioHttpVersion    = "3.10.1"
val zioLoggingVersion = "2.4.0"
val scalaMetaVersion  = "4.13.6"

// ── Dependency groups ─────────────────────────────────────────────────────────
val zioCoreDeps = Seq(
  "dev.zio" %% "zio"         % zioVersion,
  "dev.zio" %% "zio-streams" % zioVersion,
  "dev.zio" %% "zio-process" % zioProcessVersion,
)

val zioJsonDep = "dev.zio" %% "zio-json" % zioJsonVersion
val zioHttpDep = "dev.zio" %% "zio-http" % zioHttpVersion

val zioLoggingDeps = Seq(
  "dev.zio" %% "zio-logging" % zioLoggingVersion,
)

val zioTestDeps = Seq(
  "dev.zio" %% "zio-test"          % zioVersion % "test,it",
  "dev.zio" %% "zio-test-sbt"      % zioVersion % "test,it",
  "dev.zio" %% "zio-test-magnolia" % zioVersion % "test,it",
)

val llm4zioDeps = zioCoreDeps ++ Seq(
  zioJsonDep,
  zioHttpDep,
  "org.scalameta" %% "scalameta" % scalaMetaVersion,
) ++ zioLoggingDeps ++ zioTestDeps

// ── Publishing metadata ───────────────────────────────────────────────────────
inThisBuild(List(
  organization := "io.github.riccardomerolla",
  homepage     := Some(url("https://github.com/riccardomerolla/llm4zio")),
  licenses     := Seq("MIT" -> url("https://opensource.org/license/mit")),
  developers := List(
    Developer(
      id = "riccardomerolla",
      name = "Riccardo Merolla",
      email = "riccardo.merolla@gmail.com",
      url = url("https://github.com/riccardomerolla"),
    )
  ),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/riccardomerolla/llm4zio"),
      "scm:git@github.com:riccardomerolla/llm4zio.git",
    )
  ),
  versionScheme := Some("early-semver"),
  scalacOptions ++= Seq(
    "-language:existentials",
    "-explain",
    "-Wunused:all",
    "-Xmax-inlines",
    "128",
    // Scala 3.8 deprecated -Xfatal-warnings; tpolecat still emits it and the
    // deprecation warning itself fails under -Werror. Silence just that message.
    "-Wconf:msg=-Xfatal-warnings is a deprecated alias:silent",
  ),
  semanticdbEnabled := true,
))

lazy val It = config("it") extend Test

// ── The library ───────────────────────────────────────────────────────────────
// Phase 1 leaves a single module. Phase 2 splits this into llm4zio-core /
// llm4zio-flow / llm4zio-runner (see .claude/plans/orca-shaped-shedding.md).
lazy val llm4zio = (project in file("llm4zio"))
  .configs(It)
  .settings(inConfig(It)(Defaults.testSettings): _*)
  .settings(
    name        := "llm4zio",
    description := "ZIO-native LLM library",
    libraryDependencySchemes += "dev.zio" %% "zio-json" % VersionScheme.Always,
    libraryDependencies ++= llm4zioDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    It / testFrameworks ++= (Test / testFrameworks).value,
  )

lazy val root = (project in file("."))
  .aggregate(llm4zio)
  .settings(
    name           := "llm4zio-root",
    publish / skip := true,
  )
