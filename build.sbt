ThisBuild / scalaVersion := "3.8.3"
ThisBuild / organization := "io.github.riccardomerolla"
ThisBuild / organizationName := "Riccardo Merolla"
ThisBuild / organizationHomepage := Some(url("https://github.com/riccardomerolla"))
ThisBuild / excludeDependencies += ExclusionRule("com.lihaoyi", "sourcecode_3")
ThisBuild / dependencyOverrides += "com.lihaoyi" % "sourcecode_2.13" % "0.4.2"

addCommandAlias("fmt", " ; scalafixAll ; scalafmtAll")
addCommandAlias("check", "; scalafixAll --check; scalafmtCheckAll")
addCommandAlias(
  "bankmodDocs",
  "bankmodApp/runMain bankmod.app.DocsGenerator",
)
addCommandAlias(
  "bankmodSeedExample",
  "bankmodApp/runMain bankmod.app.SampleGraphSeeder",
)

// Centralized version management
val zioVersion = "2.1.25"
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
val zioEclipseStoreVersion = "2.2.2"
val zioSchemaVersion       = "1.8.0"
val scalaMetaVersion = "4.13.6"
val bot4sTelegramCoreVersion = "7.0.0"
val ironVersion            = "2.6.0"
val zioBlocksSchemaVersion = "0.0.33"

// Common dependencies shared across modules
val zioCoreDeps = Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-streams" % zioVersion,
  "dev.zio" %% "zio-process" % zioProcessVersion,
)

val zioJsonDep = "dev.zio" %% "zio-json" % zioJsonVersion

val zioHttpDep = "dev.zio" %% "zio-http" % zioHttpVersion

val zioCliDep = "dev.zio" %% "zio-cli" % zioCliVersion

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
    // Scala 3.8 deprecated -Xfatal-warnings in favor of -Werror; sbt-tpolecat still
    // emits the old flag, whose deprecation warning itself fails under -Werror.
    // Silence that specific message until tpolecat catches up.
    "-Wconf:msg=-Xfatal-warnings is a deprecated alias:silent",
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

// ── Shared services (infrastructure extracted from app.control) ──────────────

lazy val sharedServices = (project in file("modules/shared-services"))
  .dependsOn(sharedErrors, taskrunDomain, configDomain)
  .settings(foundationSettings)
  .settings(
    name := "shared-services",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      zioJsonDep,
      zioHttpDep,
    ),
  )

// ── Shared web core (domain-independent view infrastructure) ─────────────────

lazy val sharedWebCore = (project in file("modules/shared-web-core"))
  .dependsOn(sharedIds, sharedErrors)
  .settings(foundationSettings)
  .settings(
    name := "shared-web-core",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      zioJsonDep,
      zioHttpDep,
      "com.lihaoyi" %% "scalatags" % scalatagsVersion,
    ),
  )

// ── Domain modules (entity + control layers, no boundary/web dependencies) ──

val domainTestDeps = Seq(
  "dev.zio" %% "zio-test"     % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
)

val domainDeps = Seq(
  "dev.zio" %% "zio" % zioVersion,
  zioJsonDep,
  "dev.zio" %% "zio-schema"            % zioSchemaVersion,
  "dev.zio" %% "zio-schema-derivation" % zioSchemaVersion,
  "io.github.riccardomerolla" %% "zio-eclipsestore" % zioEclipseStoreVersion,
) ++ domainTestDeps

val domainBceDeps = domainDeps ++ Seq(
  zioHttpDep,
  "com.lihaoyi" %% "scalatags" % scalatagsVersion,
)

lazy val activityDomain = (project in file("modules/activity-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, sharedWebCore)
  .settings(foundationSettings)
  .settings(
    name := "activity-domain",
    libraryDependencies ++= domainBceDeps ++ Seq(
      "io.github.riccardomerolla" %% "zio-eclipsestore-gigamap" % zioEclipseStoreVersion % Test,
    ),
  )

lazy val memoryDomain = (project in file("modules/memory-domain"))
  .dependsOn(sharedIds, sharedStoreCore, sharedWebCore, configDomain, llm4zio)
  .settings(foundationSettings)
  .settings(
    name := "memory-domain",
    libraryDependencies ++= domainBceDeps ++ Seq(
      "io.github.riccardomerolla" %% "zio-eclipsestore-gigamap" % zioEclipseStoreVersion,
    ),
  )

lazy val governanceDomain = (project in file("modules/governance-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, sharedWebCore, workspaceDomain)
  .settings(foundationSettings)
  .settings(
    name := "governance-domain",
    libraryDependencies ++= domainBceDeps,
  )

lazy val agentDomain = (project in file("modules/agent-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, sharedWebCore, configDomain)
  .settings(foundationSettings)
  .settings(
    name := "agent-domain",
    libraryDependencies ++= domainBceDeps,
  )

lazy val decisionDomain = (project in file("modules/decision-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, sharedWebCore, issuesDomain, activityDomain, configDomain)
  .settings(foundationSettings)
  .settings(
    name := "decision-domain",
    libraryDependencies ++= domainBceDeps,
  )

lazy val specificationDomain = (project in file("modules/specification-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, sharedWebCore, issuesDomain)
  .settings(foundationSettings)
  .settings(
    name := "specification-domain",
    libraryDependencies ++= domainBceDeps,
  )

lazy val planDomain = (project in file("modules/plan-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, governanceDomain)
  .settings(foundationSettings)
  .settings(
    name := "plan-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val taskrunDomain = (project in file("modules/taskrun-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, sharedJson, sharedWebCore, configDomain, activityDomain)
  .settings(foundationSettings)
  .settings(
    name := "taskrun-domain",
    libraryDependencies ++= domainBceDeps ++ Seq(
      "io.github.riccardomerolla" %% "zio-eclipsestore-gigamap" % zioEclipseStoreVersion % Test,
    ),
  )

lazy val boardDomain = (project in file("modules/board-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, sharedWebCore, workspaceDomain)
  .settings(foundationSettings)
  .settings(
    name := "board-domain",
    libraryDependencies ++= domainBceDeps,
  )

lazy val knowledgeDomain = (project in file("modules/knowledge-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, sharedWebCore, analysisDomain,
    memoryDomain, workspaceDomain)
  .settings(foundationSettings)
  .settings(
    name := "knowledge-domain",
    libraryDependencies ++= domainBceDeps ++ Seq(
      "io.github.riccardomerolla" %% "zio-eclipsestore-gigamap" % zioEclipseStoreVersion % Test,
    ),
  )

lazy val projectDomain = (project in file("modules/project-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, sharedWebCore, workspaceDomain)
  .settings(foundationSettings)
  .settings(
    name := "project-domain",
    libraryDependencies ++= domainBceDeps,
  )

lazy val configDomain = (project in file("modules/config-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, sharedWebCore, llm4zio)
  .settings(foundationSettings)
  .settings(
    name := "config-domain",
    libraryDependencies ++= domainBceDeps ++ Seq(
      "dev.zio" %% "zio-config" % zioConfigVersion,
      "dev.zio" %% "zio-config-typesafe" % zioConfigVersion,
      "dev.zio" %% "zio-config-magnolia" % zioConfigVersion,
      "io.github.riccardomerolla" %% "zio-eclipsestore-gigamap" % zioEclipseStoreVersion % Test,
    ),
  )

lazy val conversationDomain = (project in file("modules/conversation-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "conversation-domain",
    libraryDependencies ++= domainDeps ++ Seq(
      "io.github.riccardomerolla" %% "zio-eclipsestore-gigamap" % zioEclipseStoreVersion % Test,
    ),
  )

lazy val daemonDomain = (project in file("modules/daemon-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, sharedWebCore, orchestrationDomain,
    activityDomain, governanceDomain, issuesDomain, projectDomain, workspaceDomain, configDomain)
  .settings(foundationSettings)
  .settings(
    name := "daemon-domain",
    libraryDependencies ++= domainBceDeps,
  )

lazy val analysisDomain = (project in file("modules/analysis-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "analysis-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val workspaceDomain = (project in file("modules/workspace-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, sharedWebCore, analysisDomain)
  .settings(foundationSettings)
  .settings(
    name := "workspace-domain",
    libraryDependencies ++= domainBceDeps ++ Seq(
      "dev.zio" %% "zio-streams" % zioVersion,
      "dev.zio" %% "zio-process" % zioProcessVersion,
    ),
  )

lazy val gatewayDomain = (project in file("modules/gateway-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedWebCore)
  .settings(foundationSettings)
  .settings(
    name := "gateway-domain",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      zioJsonDep,
      zioHttpDep,
      "com.lihaoyi" %% "scalatags" % scalatagsVersion,
      "com.bot4s" %% "telegram-core" % bot4sTelegramCoreVersion,
    ),
  )

lazy val orchestrationDomain = (project in file("modules/orchestration-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, gatewayDomain, configDomain, planDomain,
    activityDomain, issuesDomain, taskrunDomain, workspaceDomain, sharedServices, conversationDomain,
    memoryDomain, llm4zio, boardDomain, projectDomain, governanceDomain)
  .settings(foundationSettings)
  .settings(
    name := "orchestration-domain",
    libraryDependencies ++= domainDeps,
  )

lazy val evolutionDomain = (project in file("modules/evolution-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, daemonDomain, governanceDomain, configDomain,
    orchestrationDomain, decisionDomain)
  .settings(foundationSettings)
  .settings(
    name := "evolution-domain",
    libraryDependencies ++= domainDeps ++ Seq(
      "io.github.riccardomerolla" %% "zio-eclipsestore-gigamap" % zioEclipseStoreVersion % Test,
    ),
  )

lazy val sharedWeb = (project in file("modules/shared-web"))
  .dependsOn(sharedIds, sharedErrors, sharedWebCore,
    activityDomain, agentDomain, boardDomain, configDomain, conversationDomain,
    daemonDomain, decisionDomain, demoDomain, evolutionDomain, gatewayDomain,
    governanceDomain, issuesDomain, knowledgeDomain, memoryDomain,
    planDomain, projectDomain, specificationDomain, taskrunDomain, workspaceDomain,
    orchestrationDomain, sdlcDomain, llm4zio)
  .settings(foundationSettings)
  .settings(
    name := "shared-web",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      zioJsonDep,
      zioHttpDep,
      "com.lihaoyi" %% "scalatags" % scalatagsVersion,
    ),
  )

lazy val issuesDomain = (project in file("modules/issues-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedStoreCore, boardDomain, workspaceDomain,
    taskrunDomain, analysisDomain, configDomain)
  .settings(foundationSettings)
  .settings(
    name := "issues-domain",
    libraryDependencies ++= domainBceDeps ++ Seq(
      "io.github.riccardomerolla" %% "zio-eclipsestore-gigamap" % zioEclipseStoreVersion % Test,
    ),
  )

lazy val demoDomain = (project in file("modules/demo-domain"))
  .dependsOn(sharedIds, sharedWebCore, boardDomain)
  .settings(foundationSettings)
  .settings(
    name := "demo-domain",
    libraryDependencies ++= domainBceDeps,
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

lazy val sdlcDomain = (project in file("modules/sdlc-domain"))
  .dependsOn(sharedIds, sharedErrors, sharedWebCore, activityDomain, configDomain, daemonDomain,
    decisionDomain, evolutionDomain, governanceDomain, issuesDomain, planDomain, specificationDomain)
  .settings(foundationSettings)
  .settings(
    name := "sdlc-domain",
    libraryDependencies ++= domainBceDeps,
  )

lazy val cli = (project in file("modules/cli"))
  .dependsOn(
    sharedJson, sharedIds, sharedErrors, sharedStoreCore, sharedServices,
    configDomain,
    boardDomain,
    workspaceDomain,
    projectDomain,
    activityDomain,
    conversationDomain,
    taskrunDomain,
  )
  .settings(foundationSettings)
  .settings(
    name := "llm4zio-cli",
    libraryDependencies ++= zioCoreDeps ++ Seq(
      zioCliDep,
      zioHttpDep,
      zioJsonDep,
      "dev.zio" %% "zio-logging-slf4j2" % zioLoggingVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      "io.github.riccardomerolla" %% "zio-eclipsestore" % zioEclipseStoreVersion,
      "dev.zio" %% "zio-schema"            % zioSchemaVersion,
      "dev.zio" %% "zio-schema-derivation" % zioSchemaVersion,
    ) ++ zioLoggingDeps ++ zioTestDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    run / fork := true,
    run / javaOptions ++= Seq(
      "--enable-native-access=ALL-UNNAMED",
      "--add-opens", "java.base/java.lang=ALL-UNNAMED",
      "--add-opens", "java.base/java.util=ALL-UNNAMED",
      "--add-opens", "java.base/java.nio=ALL-UNNAMED",
    ),
  )

// ── Bankmod parallel assembly ─────────────────────────────────────────────────

val bankmodDeps = Seq(
  "io.github.iltotore"  %% "iron"             % ironVersion,
  "dev.zio"             %% "zio-blocks-schema" % zioBlocksSchemaVersion,
) ++ domainTestDeps

lazy val bankmodGraphModel = (project in file("modules/bankmod-graph-model"))
  .settings(foundationSettings)
  .settings(
    name := "bankmod-graph-model",
    libraryDependencies ++= bankmodDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  )

lazy val bankmodGraphValidate = (project in file("modules/bankmod-graph-validate"))
  .dependsOn(bankmodGraphModel)
  .settings(foundationSettings)
  .settings(
    name := "bankmod-graph-validate",
    libraryDependencies ++= domainDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  )

lazy val bankmodGraphRender = (project in file("modules/bankmod-graph-render"))
  .dependsOn(bankmodGraphModel)
  .settings(foundationSettings)
  .settings(
    name := "bankmod-graph-render",
    libraryDependencies ++= bankmodDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  )

lazy val bankmodMcpTools = (project in file("modules/bankmod-mcp-tools"))
  .dependsOn(bankmodGraphModel, bankmodGraphValidate, bankmodGraphRender, sharedIds, sharedErrors)
  .settings(foundationSettings)
  .settings(
    name := "bankmod-mcp-tools",
    libraryDependencies ++= bankmodDeps ++ Seq(
      "com.jamesward" %% "zio-http-mcp"          % "0.0.6",
      zioHttpDep,
      "dev.zio"       %% "zio-schema"            % zioSchemaVersion,
      "dev.zio"       %% "zio-schema-derivation" % zioSchemaVersion,
      "dev.zio"       %% "zio-schema-json"       % zioSchemaVersion,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  )

lazy val bankmodApp = (project in file("modules/bankmod-app"))
  .dependsOn(bankmodGraphModel, bankmodGraphValidate, bankmodGraphRender, bankmodMcpTools, sharedIds, sharedErrors, sharedStoreCore)
  .settings(foundationSettings)
  .settings(
    name := "bankmod-app",
    libraryDependencies ++= domainBceDeps,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Compile / mainClass := Some("bankmod.app.BankmodMain"),
    run / fork := true,
    run / javaOptions ++= Seq(
      "--enable-native-access=ALL-UNNAMED",
      "--add-opens", "java.base/java.lang=ALL-UNNAMED",
      "--add-opens", "java.base/java.util=ALL-UNNAMED",
      "--add-opens", "java.base/java.nio=ALL-UNNAMED",
    ),
  )

lazy val bankmod = (project in file("modules/bankmod"))
  .aggregate(bankmodGraphModel, bankmodGraphValidate, bankmodGraphRender, bankmodMcpTools, bankmodApp)
  .settings(
    name := "bankmod",
    publish / skip := true,
  )

lazy val allModules = Seq(
  llm4zio, sharedJson, sharedIds, sharedErrors, sharedStoreCore, sharedServices, sharedWebCore,
  activityDomain, memoryDomain, governanceDomain, agentDomain, decisionDomain, specificationDomain,
  planDomain, taskrunDomain, boardDomain, knowledgeDomain, projectDomain, configDomain,
  conversationDomain, daemonDomain, analysisDomain, workspaceDomain, gatewayDomain,
  orchestrationDomain, evolutionDomain, issuesDomain, demoDomain, sharedWeb,
  sdlcDomain,
  bankmodGraphModel, bankmodGraphValidate, bankmodGraphRender, bankmodMcpTools, bankmodApp,
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
