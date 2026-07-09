package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*

object PackSpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-pack-")).orDie)(d =>
      ZIO.attempt {
        Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
      }.orDie
    )

  private def write(dir: Path, name: String, content: String): UIO[Path] =
    ZIO.attemptBlocking {
      val path = dir.resolve(name)
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      path
    }.orDie

  private val minimalManifest =
    """# Pack: cobol-springboot
      |
      |source: cobol
      |scaffold: ../fixtures/scaffolds/spring-boot-service
      |""".stripMargin

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Pack")(
    test("load parses name, source, and scaffold from a minimal manifest") {
      ZIO.scoped {
        for
          dir  <- tempDir
          _    <- write(dir, "pack.md", minimalManifest)
          pack <- Pack.load(dir)
        yield assertTrue(
          pack.name == "cobol-springboot",
          pack.source == "cobol",
          pack.scaffold.contains("../fixtures/scaffolds/spring-boot-service"),
          pack.dir == dir,
        )
      }
    },
    test("load parses named gate commands from the Gates section") {
      val manifest =
        s"""$minimalManifest
           |## Gates
           |
           |- build: mvn -q test-compile
           |- test: mvn -q test
           |""".stripMargin
      ZIO.scoped {
        for
          dir  <- tempDir
          _    <- write(dir, "pack.md", manifest)
          pack <- Pack.load(dir)
        yield assertTrue(
          pack.gate("build").contains(List("mvn", "-q", "test-compile")),
          pack.gate("test").contains(List("mvn", "-q", "test")),
          pack.gate("missing").isEmpty,
        )
      }
    },
    test("load parses judge dimensions with rubric and scale") {
      val manifest =
        s"""$minimalManifest
           |## Judge
           |
           |- completeness (0..2): Every business rule in the source appears in the spec.
           |- faithfulness (0..3): Nothing in the spec is invented.
           |""".stripMargin
      ZIO.scoped {
        for
          dir  <- tempDir
          _    <- write(dir, "pack.md", manifest)
          pack <- Pack.load(dir)
        yield assertTrue(
          pack.judgeDimensions == List(
            llm4zio.eval.Dimension("completeness", "Every business rule in the source appears in the spec.", 2),
            llm4zio.eval.Dimension("faithfulness", "Nothing in the spec is invented.", 3),
          )
        )
      }
    },
    test("load parses coverage rules from Coverage sections") {
      val manifest =
        s"""$minimalManifest
           |## Coverage: cobol-paragraph
           |
           |files: .*\\.cbl
           |unit: ^ {7}(\\d{4}-[A-Z0-9-]+)\\.
           |
           |## Coverage: jcl-step
           |
           |files: .*\\.jcl
           |unit: ^//(\\S+)\\s+EXEC
           |""".stripMargin
      ZIO.scoped {
        for
          dir  <- tempDir
          _    <- write(dir, "pack.md", manifest)
          pack <- Pack.load(dir)
        yield assertTrue(
          pack.coverage == List(
            CoverageRule("cobol-paragraph", """.*\.cbl""", """^ {7}(\d{4}-[A-Z0-9-]+)\."""),
            CoverageRule("jcl-step", """.*\.jcl""", """^//(\S+)\s+EXEC"""),
          )
        )
      }
    },
    test("load picks up prompts/*.md by slug and lessons.md when present") {
      ZIO.scoped {
        for
          dir  <- tempDir
          _    <- write(dir, "pack.md", minimalManifest)
          _    <- write(dir, "prompts/analysis.md", "Explore the legacy code.\n")
          _    <- write(dir, "prompts/spec.md", "Write the spec.\n")
          _    <- write(dir, "lessons.md", "Prefer BigDecimal for money.\n")
          pack <- Pack.load(dir)
        yield assertTrue(
          pack.prompt("analysis").contains("Explore the legacy code."),
          pack.prompt("spec").contains("Write the spec."),
          pack.prompt("missing").isEmpty,
          pack.lessons.contains("Prefer BigDecimal for money."),
        )
      }
    },
    test("load yields no prompts and no lessons when the files are absent") {
      ZIO.scoped {
        for
          dir  <- tempDir
          _    <- write(dir, "pack.md", minimalManifest)
          pack <- Pack.load(dir)
        yield assertTrue(pack.prompts.isEmpty, pack.lessons.isEmpty)
      }
    },
    test("load builds reviewer lenses from reviewers/*.md in the resource format") {
      val scoped =
        """---
          |files: .*\.java
          |---
          |Check COBOL arithmetic was ported with BigDecimal semantics.""".stripMargin
      ZIO.scoped {
        for
          dir  <- tempDir
          _    <- write(dir, "pack.md", minimalManifest)
          _    <- write(dir, "reviewers/cobol-fidelity.md", scoped)
          _    <- write(dir, "reviewers/traceability.md", "Every change must cite a spec item.")
          pack <- Pack.load(dir)
        yield assertTrue(
          pack.lenses.sortBy(_.name) == List(
            Reviewer(
              "cobol-fidelity",
              "Check COBOL arithmetic was ported with BigDecimal semantics.",
              Some(""".*\.java"""),
            ),
            Reviewer("traceability", "Every change must cite a spec item.", None),
          )
        )
      }
    },
    test("load parses sources and seed destinations, with defaults when absent") {
      val manifest =
        """# Pack: cobol-springboot
          |
          |source: cobol
          |sources: .*\.(cbl|cpy|jcl)
          |specs-dir: docs/specs
          |features-dir: src/test/resources/features
          |""".stripMargin
      ZIO.scoped {
        for
          dir      <- tempDir
          _        <- write(dir, "pack.md", manifest)
          pack     <- Pack.load(dir)
          _        <- write(dir, "pack.md", minimalManifest)
          defaults <- Pack.load(dir)
        yield assertTrue(
          pack.sources.contains(""".*\.(cbl|cpy|jcl)"""),
          pack.specsDir == "docs/specs",
          pack.featuresDir == "src/test/resources/features",
          defaults.sources.isEmpty,
          defaults.specsDir == "docs/specs",
          defaults.featuresDir == "features",
        )
      }
    },
    test("load fails typed on a missing manifest and on a malformed one") {
      ZIO.scoped {
        for
          dir      <- tempDir
          missing  <- Pack.load(dir).flip
          _        <- write(dir, "pack.md", "# Not A Pack\n")
          badHead  <- Pack.load(dir).flip
          _        <- write(dir, "pack.md", "# Pack: p\n\nscaffold: somewhere\n")
          noSource <- Pack.load(dir).flip
        yield assertTrue(
          missing.isInstanceOf[FlowError.Persistence],
          badHead.isInstanceOf[FlowError.PlanParse],
          noSource.isInstanceOf[FlowError.PlanParse],
        )
      }
    },
    test("appendLesson creates lessons.md, accumulates entries, and survives a reload") {
      ZIO.scoped {
        for
          dir  <- tempDir
          _    <- write(dir, "pack.md", minimalManifest)
          _    <- Pack.appendLesson(dir, "Prefer BigDecimal for money.")
          _    <- Pack.appendLesson(dir, "COMP-3 fields need scale-aware mapping.")
          pack <- Pack.load(dir)
          text  = pack.lessons.getOrElse("")
        yield assertTrue(
          text.contains("Prefer BigDecimal for money."),
          text.contains("COMP-3 fields need scale-aware mapping."),
        )
      }
    },
  )
