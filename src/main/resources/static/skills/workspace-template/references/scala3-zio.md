# Scala 3 + ZIO Scaffolding Reference

## Directory Structure

```
{name}/
├── build.sbt
├── project/
│   ├── build.properties
│   └── plugins.sbt
├── src/
│   ├── main/
│   │   ├── scala/{package}/
│   │   │   └── Main.scala
│   │   └── resources/
│   │       └── application.conf
│   └── test/
│       └── scala/{package}/
│           └── MainSpec.scala
├── .gitignore
├── .scalafmt.conf
├── CLAUDE.md
└── README.md
```

**Package derivation:** Convert project name to a valid Scala package. Example: `my-cool-api` → `mycoolapi` or ask the user for a preferred base package like `com.example.mycoolapi`.

---

## File Templates

### build.sbt

```scala
val scala3Version = "3.6.4"
val zioVersion    = "2.1.16"

lazy val root = project
  .in(file("."))
  .settings(
    name         := "{name}",
    version      := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"          % zioVersion,
      "dev.zio" %% "zio-streams"  % zioVersion,
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  )
```

**If the project needs HTTP (REST API):**

Add to `libraryDependencies`:
```scala
"dev.zio" %% "zio-http" % "3.2.0",
```

**If the project needs JSON:**

Add to `libraryDependencies`:
```scala
"dev.zio" %% "zio-json" % "0.7.39",
```

**If the project needs database access:**

Add to `libraryDependencies`:
```scala
"dev.zio" %% "zio-jdbc" % "0.1.2",
```

### project/build.properties

```
sbt.version=1.10.11
```

### project/plugins.sbt

```scala
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"  % "2.5.4")
addSbtPlugin("ch.epfl.scala"  % "sbt-scalafix"  % "0.14.2")
```

### .scalafmt.conf

```hocon
version = 3.8.6
runner.dialect = scala3
maxColumn = 120
align.preset = more
```

### src/main/scala/{package}/Main.scala

```scala
package {package}

import zio.*

object Main extends ZIOAppDefault:

  val run: ZIO[Any, Nothing, Unit] =
    Console.printLine("Hello from {name}!").orDie
```

**If HTTP project, use instead:**

```scala
package {package}

import zio.*
import zio.http.*

object Main extends ZIOAppDefault:

  val app: Routes[Any, Nothing] =
    Routes(
      Method.GET / "health" -> handler(Response.text("ok")),
    )

  val run: ZIO[Any, Nothing, Nothing] =
    Server.serve(app).provide(Server.default)
```

### src/main/resources/application.conf

```hocon
# {name} configuration
```

### src/test/scala/{package}/MainSpec.scala

```scala
package {package}

import zio.*
import zio.test.*

object MainSpec extends ZIOSpecDefault:

  def spec = suite("{name}")(
    test("placeholder") {
      assertTrue(1 + 1 == 2)
    }
  )
```

### .gitignore

```
# sbt
target/
project/target/
project/project/

# IDE
.idea/
.bsp/
.metals/
.vscode/

# OS
.DS_Store
Thumbs.db

# ADE board
/.board/
```

### CLAUDE.md

```markdown
# CLAUDE.md — {name}

## Build Commands

\`\`\`bash
sbt compile       # compile all sources
sbt test          # run tests
sbt run           # run the application
sbt fmt           # format code (if sbt-scalafmt configured)
\`\`\`

## Architecture

This project uses Scala 3 with ZIO 2.x for effect-oriented programming.

### Key Conventions

- **No `var`** — use `Ref`, `Queue`, `Hub` for mutable state
- **No `Throwable`** — use typed error ADTs in error channels
- **No side effects outside ZIO** — wrap with `ZIO.attempt` / `ZIO.attemptBlocking`
- **ZLayer for DI** — define services as traits with `ZLayer` constructors
- **ZIO Test** — use `ZIOSpecDefault` with `assertTrue` assertions

### Service Pattern

\`\`\`scala
trait MyService:
  def doThing(id: String): IO[MyError, Result]

object MyService:
  val live: ZLayer[Dependency, Nothing, MyService] =
    ZLayer.fromFunction(MyServiceLive.apply)

final case class MyServiceLive(dep: Dependency) extends MyService:
  def doThing(id: String): IO[MyError, Result] = ???
\`\`\`
```

### README.md

```markdown
# {name}

{description}

## Getting Started

\`\`\`bash
sbt compile
sbt test
sbt run
\`\`\`

## Tech Stack

- Scala 3
- ZIO 2.x
```
