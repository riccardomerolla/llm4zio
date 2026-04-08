ThisBuild / scalaVersion := "3.5.2"
ThisBuild / organization := "io.github.riccardomerolla"
ThisBuild / organizationName := "Riccardo Merolla"
ThisBuild / organizationHomepage := Some(url("https://github.com/riccardomerolla"))
ThisBuild / excludeDependencies += ExclusionRule("com.lihaoyi", "sourcecode_3")
ThisBuild / dependencyOverrides += "com.lihaoyi" % "sourcecode_2.13" % "0.4.2"

addCommandAlias("fmt", " ; scalafixAll ; scalafmtAll")
addCommandAlias("check", "; scalafixAll --check; scalafmtCheckAll")

// Centralized version management
val zioVersion = "2.1.24"
val zioProcessVersion = "0.8.0"
val zioJsonVersion = "0.9.0"
val zioHttpVersion = "3.10.1"
val zioLoggingVersion = "2.4.0"
val zioConfigVersion = "4.0.6"
val zioCliVersion = "0.7.5"
val zioOpentelemetryVersion = "3.0.0"
val scalatagsVersion = "0.13.1"
val logbackVersion = "1.5.12"
val logstashLogbackVersion = "7.4"
val opentelemetryVersion = "1.44.1"
val zioEclipseStoreVersion = "2.1.9"
val zioSchemaVersion       = "1.8.0"
val scalaMetaVersion = "4.13.6"
val bot4sTelegramCoreVersion = "7.0.0"

// Common dependencies shared across modules
val zioCoreDeps = Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-streams" % zioVersion,
  "dev.zio" %% "zio-process" % zioProcessVersion,
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
  "org.scalameta" %% "scalameta" % scalaMetaVersion,
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
  "io.github.riccardomerolla" %% "zio-eclipsestore" % zioEclipseStoreVersion,
  "io.github.riccardomerolla" %% "zio-eclipsestore-gigamap" % zioEclipseStoreVersion,
  "dev.zio" %% "zio-schema"            % zioSchemaVersion,
  "dev.zio" %% "zio-schema-derivation" % zioSchemaVersion,
  "com.bot4s" %% "telegram-core" % bot4sTelegramCoreVersion,
) ++ zioLoggingDeps ++ zioTestDeps

inThisBuild(List(
  organization := "io.github.riccardomerolla",
  homepage := Some(url("https://github.com/riccardomerolla/llm4zio")),
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
      url("https://github.com/riccardomerolla/llm4zio"),
      "scm:git@github.com:riccardomerolla/llm4zio.git"
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

// ── Foundation modules ────────────────────────────────────────────────────────

val foundationSettings = Seq(
  publish / skip := true,
  libraryDependencySchemes += "dev.zio" %% "zio-json" % VersionScheme.Always,
)

lazy val sharedJson = (project in file("modules/shared-json"))
  .settings(foundationSettings)
  .settings(
    name := "shared-json",
    libraryDependencies ++= Seq(zioJsonDep),
  )

lazy val sharedIds = (project in file("modules/shared-ids"))
  .settings(foundationSettings)
  .settings(
    name := "shared-ids",
    libraryDependencies ++= Seq(
      zioJsonDep,
      "dev.zio" %% "zio-schema" % zioSchemaVersion,
    ),
  )

lazy val sharedErrors = (project in file("modules/shared-errors"))
  .dependsOn(sharedJson)
  .settings(foundationSettings)
  .settings(
    name := "shared-errors",
    libraryDependencies ++= Seq(
      zioJsonDep,
      "dev.zio" %% "zio-schema"            % zioSchemaVersion,
      "dev.zio" %% "zio-schema-derivation" % zioSchemaVersion,
    ),
  )

lazy val sharedStoreCore = (project in file("modules/shared-store-core"))
  .dependsOn(sharedErrors)
  .settings(foundationSettings)
  .settings(
    name := "shared-store-core",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "io.github.riccardomerolla" %% "zio-eclipsestore" % zioEclipseStoreVersion,
    ),
  )

// ── Domain modules (entity + control layers, no boundary/web dependencies) ──

val domainDeps = Seq(
  "dev.zio" %% "zio" % zioVersion,
  zioJsonDep,
  "dev.zio" %% "zio-schema"            % zioSchemaVersion,
  "dev.zio" %% "zio-schema-derivation" % zioSchemaVersion,
  "io.github.riccardomerolla" %% "zio-eclipsestore" % zioEclipseStoreVersion,
)

lazy val activityDomain = (project in file("modules/activity-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "activity-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val memoryDomain = (project in file("modules/memory-domain"))
  .dependsOn(sharedIds, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "memory-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val governanceDomain = (project in file("modules/governance-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "governance-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val agentDomain = (project in file("modules/agent-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "agent-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val decisionDomain = (project in file("modules/decision-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "decision-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val specificationDomain = (project in file("modules/specification-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "specification-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val planDomain = (project in file("modules/plan-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, governanceDomain)
  .settings(foundationSettings)
  .settings(
    name := "plan-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val taskrunDomain = (project in file("modules/taskrun-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "taskrun-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val boardDomain = (project in file("modules/board-domain"))
  .dependsOn(sharedIds, sharedErrors)
  .settings(foundationSettings)
  .settings(
    name := "board-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val knowledgeDomain = (project in file("modules/knowledge-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "knowledge-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val projectDomain = (project in file("modules/project-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "project-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val configDomain = (project in file("modules/config-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "config-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val conversationDomain = (project in file("modules/conversation-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "conversation-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val daemonDomain = (project in file("modules/daemon-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "daemon-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val analysisDomain = (project in file("modules/analysis-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "analysis-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val workspaceDomain = (project in file("modules/workspace-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "workspace-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val gatewayDomain = (project in file("modules/gateway-domain"))
  .dependsOn(sharedIds)
  .settings(foundationSettings)
  .settings(
    name := "gateway-domain",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      zioJsonDep,
    ),
  )

lazy val orchestrationDomain = (project in file("modules/orchestration-domain"))
  .dependsOn(sharedIds)
  .settings(foundationSettings)
  .settings(
    name := "orchestration-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val evolutionDomain = (project in file("modules/evolution-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, daemonDomain, governanceDomain, configDomain)
  .settings(foundationSettings)
  .settings(
    name := "evolution-domain",
    libraryDependencies ++= domainDeps,
  )

// ── LLM library ──────────────────────────────────────────────────────────────

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

lazy val allModules = Seq(
  llm4zio, sharedJson, sharedIds, sharedErrors, sharedStoreCore,
  activityDomain, memoryDomain, governanceDomain, agentDomain, decisionDomain, specificationDomain,
  planDomain, taskrunDomain, boardDomain, knowledgeDomain, projectDomain, configDomain,
  conversationDomain, daemonDomain, analysisDomain, workspaceDomain, gatewayDomain,
  orchestrationDomain, evolutionDomain,
)

lazy val root = (project in file("."))
  .aggregate(allModules.map(_.project): _*)
  .dependsOn(allModules.map(m => m: ClasspathDep[ProjectReference]): _*)
  .configs(It)
  .settings(inConfig(It)(Defaults.testSettings): _*)
  .settings(
    name := "llm4zio-gateway",
    publish / skip := true,
    description := "A LLM 4 ZIO Agent Gateway and Dashboard",
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
      "--add-opens", "java.base/java.lang=ALL-UNNAMED",
      "--add-opens", "java.base/java.util=ALL-UNNAMED",
      "--add-opens", "java.base/java.nio=ALL-UNNAMED",
    ),
  )
