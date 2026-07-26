package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*

object SurveySpec extends ZIOSpecDefault:

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-survey-")).orDie)(d =>
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

  private val acctxfr =
    """       IDENTIFICATION DIVISION.
      |       COPY ACCTREC.
      |       PROCEDURE DIVISION.
      |       2100-VALIDATE.
      |           CALL 'INTCALC' USING WS-X.
      |""".stripMargin

  private val intcalc =
    """       IDENTIFICATION DIVISION.
      |       PROCEDURE DIVISION.
      |       1000-RATES.
      |           CONTINUE.
      |""".stripMargin

  private val jcl = "//STEP020 EXEC PGM=ACCTXFR\n"

  private val surveyRules = List(
    CoverageRule("calls", """.*\.cbl""", """CALL '([A-Z0-9]+)'"""),
    CoverageRule("copies", """.*\.cbl""", """COPY ([A-Z0-9]+)"""),
    CoverageRule("exec-pgm", """.*\.jcl""", """EXEC PGM=([A-Z0-9]+)"""),
  )

  private def estate(dir: Path): UIO[Unit] =
    (write(dir, "cobol/ACCTXFR.cbl", acctxfr) *>
      write(dir, "cobol/INTCALC.cbl", intcalc) *>
      write(dir, "cobol/copybooks/ACCTREC.cpy", "       01  ACCT-REC.\n") *>
      write(dir, "jobs/XFERDLY.jcl", jcl)).unit

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Survey")(
    test("graph enumerates nodes from the sources regex with line and unit counts") {
      ZIO.scoped {
        for
          dir   <- tempDir
          _     <- estate(dir)
          graph <- Survey.graph(
                     dir,
                     sources = """.*\.(cbl|cpy|jcl)""",
                     units = List(CoverageRule("paragraph", """.*\.cbl""", """^ {7}(\d{4}-[A-Z0-9-]+)\.""")),
                     edges = surveyRules,
                   )
        yield assertTrue(
          graph.nodes.map(_.name).sorted == List("ACCTREC", "ACCTXFR", "INTCALC", "XFERDLY"),
          graph.nodes.find(_.name == "ACCTXFR").exists(n => n.lines == 5 && n.units == 1),
        )
      }
    },
    test("graph resolves edges by scanning each file with the pack's survey regexes") {
      ZIO.scoped {
        for
          dir   <- tempDir
          _     <- estate(dir)
          graph <- Survey.graph(dir, """.*\.(cbl|cpy|jcl)""", Nil, surveyRules)
        yield assertTrue(
          graph.edges.contains(SurveyEdge("ACCTXFR", "INTCALC", "calls")),
          graph.edges.contains(SurveyEdge("ACCTXFR", "ACCTREC", "copies")),
          graph.edges.contains(SurveyEdge("XFERDLY", "ACCTXFR", "exec-pgm")),
        )
      }
    },
    test("renderInventory lists every node and flags unreferenced non-job nodes as retire candidates") {
      ZIO.scoped {
        for
          dir   <- tempDir
          _     <- estate(dir)
          _     <- write(dir, "cobol/ORPHAN.cbl", "       IDENTIFICATION DIVISION.\n")
          graph <- Survey.graph(dir, """.*\.(cbl|cpy|jcl)""", Nil, surveyRules)
          md     = Survey.renderInventory(graph)
        yield assertTrue(
          md.linesIterator.exists(l => l.contains("ACCTXFR")),
          md.linesIterator.exists(l => l.contains("ORPHAN") && l.contains("unreferenced")),
          !md.linesIterator.exists(l => l.contains("XFERDLY") && l.contains("unreferenced")),
        )
      }
    },
  )
