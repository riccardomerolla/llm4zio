package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*

object PatternsSpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-patterns-")).orDie)(d =>
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

  private val comp3Card =
    """---
      |match: COMP-3|COMPUTE .* ROUNDED
      |---
      |COBOL packed-decimal money maps to BigDecimal scale 2; COMPUTE ROUNDED is
      |RoundingMode.HALF_UP. Never float/double.""".stripMargin

  private val redefinesCard =
    """---
      |match: REDEFINES
      |---
      |A REDEFINES view of a record maps to a sealed interface with one variant per
      |interpretation — never two mutable views of one buffer.""".stripMargin

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Patterns")(
    test("load reads pattern cards from a directory — id from filename, match regex from frontmatter") {
      ZIO.scoped {
        for
          dir   <- tempDir
          _     <- write(dir, "PAT-COBOL-001-comp3-money.md", comp3Card)
          _     <- write(dir, "PAT-COBOL-002-redefines.md", redefinesCard)
          cards <- Patterns.load(dir)
        yield assertTrue(
          cards.map(_.id) == List("PAT-COBOL-001-comp3-money", "PAT-COBOL-002-redefines"),
          cards.head.body.contains("HALF_UP"),
          cards.head.matches == "COMP-3|COMPUTE .* ROUNDED",
        )
      }
    },
    test("load of an absent directory is an empty card set — patterns are optional") {
      ZIO.scoped {
        for
          dir   <- tempDir
          cards <- Patterns.load(dir.resolve("nope"))
        yield assertTrue(cards.isEmpty)
      }
    },
    test("matching selects exactly the cards whose regex hits the source text") {
      ZIO.scoped {
        for
          dir   <- tempDir
          _     <- write(dir, "PAT-COBOL-001-comp3-money.md", comp3Card)
          _     <- write(dir, "PAT-COBOL-002-redefines.md", redefinesCard)
          cards <- Patterns.load(dir)
          cobol  = "       05  WS-FEE  PIC S9(5)V99 COMP-3.\n           COMPUTE WS-FEE ROUNDED = X * Y\n"
        yield assertTrue(Patterns.matching(cobol, cards).map(_.id) == List("PAT-COBOL-001-comp3-money"))
      }
    },
    test("tagged extracts the PAT- ids cited anywhere in a spec text") {
      val spec = "Fee rounding follows PAT-COBOL-001-comp3-money; layout via PAT-COBOL-002-redefines."
      assertTrue(Patterns.tagged(spec) == List("PAT-COBOL-001-comp3-money", "PAT-COBOL-002-redefines"))
    },
  )
