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

// ── llm4zio-core ──────────────────────────────────────────────────────────────
// The LLM plumbing: Connector/LlmService, providers (API + CLI), streaming,
// tool-calling, structured output, observability. llm4zio-flow and
// llm4zio-runner join this module list in later phases
// (see .claude/plans/orca-shaped-shedding.md).
lazy val llm4zioCore = (project in file("modules/llm4zio-core"))
  .configs(It)
  .settings(inConfig(It)(Defaults.testSettings): _*)
  .settings(
    name        := "llm4zio-core",
    description := "ZIO-native LLM library — provider plumbing, streaming, tools, structured output",
    libraryDependencySchemes += "dev.zio" %% "zio-json" % VersionScheme.Always,
    libraryDependencies ++= llm4zioDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    It / testFrameworks ++= (Test / testFrameworks).value,
  )

// ── llm4zio-flow ──────────────────────────────────────────────────────────────
// The orca-shaped flow layer (ZIO-native): Plan/Task + resumable plans, git/gh
// tools over zio-process, reviewAndFixLoop, stage/event stream. Reasoning runs
// over API connectors; code-editing runs over CLI connectors.
lazy val llm4zioFlow = (project in file("modules/llm4zio-flow"))
  .dependsOn(llm4zioCore)
  .configs(It)
  .settings(inConfig(It)(Defaults.testSettings): _*)
  .settings(
    name        := "llm4zio-flow",
    description := "ZIO-native agentic flow layer for llm4zio — plan, review, git/gh, resumable runs",
    libraryDependencySchemes += "dev.zio" %% "zio-json" % VersionScheme.Always,
    libraryDependencies ++= zioCoreDeps ++ Seq(zioJsonDep) ++ zioLoggingDeps ++ zioTestDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    It / testFrameworks ++= (Test / testFrameworks).value,
  )

// ── llm4zio-runner ────────────────────────────────────────────────────────────
// Entry point + terminal progress renderer + a worked example flow. Depends on
// flow (and transitively core). Real connectors are wired by the user via core's
// ConnectorRegistry; the runner stays thin.
lazy val llm4zioRunner = (project in file("modules/llm4zio-runner"))
  .dependsOn(llm4zioFlow, llm4zioCore)
  .configs(It)
  .settings(inConfig(It)(Defaults.testSettings): _*)
  .settings(
    name        := "llm4zio-runner",
    description := "Entry point, terminal renderer, and example flows for llm4zio",
    libraryDependencySchemes += "dev.zio" %% "zio-json" % VersionScheme.Always,
    libraryDependencies ++= zioCoreDeps ++ Seq(zioJsonDep) ++ zioLoggingDeps ++ zioTestDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    It / testFrameworks ++= (Test / testFrameworks).value,
  )

lazy val root = (project in file("."))
  .aggregate(llm4zioCore, llm4zioFlow, llm4zioRunner)
  .settings(
    name           := "llm4zio",
    publish / skip := true,
  )
