package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import scala.annotation.tailrec

import zio.*
import zio.test.*

/** End-to-end skeleton of the modernization pipeline (modernize-*.sc), exercised over the REAL
  * `cobol-springboot` pack and the REAL `legacy-bank` fixture with git in temp repos — no LLM, no network. What the
  * scripts compose deterministically must hold: pack loading, coverage enumeration of the estate, the layered
  * extraction gate, the human approval gate between extract and seed, and the seeded plan's resumability.
  */
object ModernizationPipelineSpec extends ZIOSpecDefault:

  /** The llm4zio checkout root — walks up from user.dir so it works from sbt root or module dir. */
  private lazy val repoRoot: Path =
    @tailrec def up(p: Path): Path =
      if Files.exists(p.resolve("examples/packs/cobol-springboot/pack.md")) then p
      else
        Option(p.getParent) match
          case Some(parent) => up(parent)
          case None         => throw new IllegalStateException("llm4zio repo root not found")
    up(Path.of(sys.props("user.dir")).toAbsolutePath)

  private val packDir    = () => repoRoot.resolve("examples/packs/cobol-springboot")
  private val fixtureDir = () => repoRoot.resolve("examples/fixtures/legacy-bank")

  private val tempDir: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("llm4zio-modernize-")).orDie)(d =>
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

  private def newRepo(dir: Path): IO[FlowError, GitTool] =
    val git = GitTool(dir)
    for
      _ <- git.init
      _ <- git.config("user.email", "test@example.com")
      _ <- git.config("user.name", "Test")
      _ <- git.config("commit.gpgsign", "false")
    yield git

  private given FlowEvents = FlowEvents.noop

  def spec = suite("modernization pipeline")(
    test("the cobol-springboot pack loads and its coverage rules enumerate the fixture estate") {
      for
        pack     <- Pack.load(packDir())
        units    <- SpecChecks.coverageUnits(fixtureDir(), pack.coverage)
        programs <- SpecChecks.matchingFiles(fixtureDir(), pack.programs.getOrElse(".*"))
      yield assertTrue(
        // programs excludes copybooks: one spec per program/job is what per-unit extraction iterates over.
        pack.programs.contains(""".*\.(cbl|CBL|jcl|JCL)"""),
        programs == List("cobol/ACCTXFR.cbl", "cobol/INTCALC.cbl", "jcl/XFERDLY.jcl"),
        pack.name == "cobol-springboot",
        pack.gate("test").contains(List("mvn", "-q", "-B", "test")),
        pack.judgeDimensions.map(_.name) == List("completeness", "faithfulness", "testability"),
        pack.lenses.map(_.name).sorted == List("cobol-fidelity", "pattern-conformance", "traceability"),
        pack.lessons.exists(_.contains("BigDecimal")),
        pack.scaffold.exists(rel => Files.exists(packDir().resolve(rel).normalize.resolve("pom.xml"))),
        units("cobol-paragraph").size == 25, // 21 in ACCTXFR + 8 in INTCALC, 4 names shared between the two
        units("cobol-paragraph").contains("2100-VALIDATE-XFER"),
        units("jcl-step") == List("STEP010", "STEP020"),
      )
    },
    test("every shipped pack loads and enumerates its slice of the fixture estate") {
      val estate = repoRoot.resolve("examples/fixtures/legacy-bank")
      for
        jspNext     <- Pack.load(repoRoot.resolve("examples/packs/jsp-nextjs"))
        jspBff      <- Pack.load(repoRoot.resolve("examples/packs/jsp-bff-nextjs"))
        ace         <- Pack.load(repoRoot.resolve("examples/packs/ace-integration"))
        jspUnits    <- SpecChecks.coverageUnits(estate, jspNext.coverage)
        aceUnits    <- SpecChecks.coverageUnits(estate, ace.coverage)
        acePrograms <- SpecChecks.matchingFiles(estate, ace.programs.orElse(ace.sources).getOrElse(".*"))
      yield assertTrue(
        // ace ships no `programs:` — the sources fallback IS its program list.
        ace.programs.isEmpty,
        acePrograms == List("ace/PaymentRouting.esql", "ace/PaymentRouting.msgflow"),
        jspNext.programs.contains(""".*\.(jsp|java)"""),
        List(jspNext, jspBff, ace).forall(p =>
          p.scaffold.exists(rel => Files.exists(p.dir.resolve(rel).normalize))
        ),
        jspUnits("servlet-url") == List("/accounts", "/transfer", "/transfer/confirm"),
        jspUnits("jsp-form").sorted == List("transfer", "transfer/confirm"),
        jspBff.coverage == jspNext.coverage,
        jspBff.gate("test").contains(List("bash", "scripts/test.sh")),
        jspNext.gate("test").contains(List("npm", "test")),
        aceUnits("flow-node") == List(
          "Payment Input",
          "Validate Payment",
          "Route Payment",
          "Mainframe Request",
          "SEPA Output",
          "Reject Output",
        ),
        aceUnits("esql-module") == List("ValidatePayment_Compute", "RoutePayment_Compute"),
        List(jspNext, jspBff, ace).forall(_.judgeDimensions.map(_.name) ==
          List("completeness", "faithfulness", "testability")),
        List(jspNext, jspBff, ace).forall(_.lenses.size == 2),
        List(jspNext, jspBff, ace).forall(_.lessons.nonEmpty),
      )
    },
    test("the extraction coverage gate fails on an incomplete traceability matrix and passes on a full one") {
      for
        pack    <- Pack.load(packDir())
        units   <- SpecChecks.coverageUnits(fixtureDir(), pack.coverage)
        all      = units.values.flatten.toList
        full     = all.map(u => s"$u — ACCTXFR.md R1").mkString("\n")
        partial  = all.filterNot(_ == "2300-CHECK-FUNDS").map(u => s"$u — ACCTXFR.md R1").mkString("\n")
        clean   <- SpecChecks.coverage(fixtureDir(), pack.coverage, full)
        dirty   <- SpecChecks.coverage(fixtureDir(), pack.coverage, partial)
      yield assertTrue(
        clean.isClean,
        dirty.issues.map(_.title) == List("uncovered cobol-paragraph: 2300-CHECK-FUNDS"),
      )
    },
    test("per-program extraction: completed programs are skipped on rerun and fragments rebuild a gate-clean traceability index") {
      ZIO.scoped {
        for
          root      <- tempDir
          pack      <- Pack.load(packDir())
          // Copy the fixture estate into a work dir, as extract.sc sees it.
          _         <- ZIO.attemptBlocking {
                         Files.walk(fixtureDir()).forEach { p =>
                           val dest = root.resolve(fixtureDir().relativize(p).toString)
                           if Files.isDirectory(p) then Files.createDirectories(dest)
                           else { Option(dest.getParent).foreach(Files.createDirectories(_)); Files.copy(p, dest) }
                           ()
                         }
                       }.orDie
          programs  <- SpecChecks.matchingFiles(root, pack.programs.orElse(pack.sources).getOrElse(".*"))
          modDir     = root.resolve("docs/modernization")

          // -- extract.sc's per-program loop, run 1: "extracts" only the first program then crashes.
          // The resume unit is the spec file: a program with specs/<NAME>.md is never re-extracted.
          name       = (rel: String) => rel.substring(rel.lastIndexOf('/') + 1).takeWhile(_ != '.')
          extracted <- Ref.make(List.empty[String])
          extractOne = (rel: String) =>
                         for
                           units <- SpecChecks.coverageUnits(root.resolve(rel).getParent, pack.coverage)
                           lines  = units.values.flatten.map(u => s"$u — ${name(rel)}.md R1").mkString("\n")
                           _     <- write(modDir, s"specs/${name(rel)}.md", s"# ${name(rel)}\nR1 ...")
                           _     <- write(modDir, s"traceability/${name(rel)}.md", lines)
                           _     <- extracted.update(rel :: _)
                         yield ()
          notDone    = (rel: String) => ZIO.attemptBlocking(!Files.exists(modDir.resolve(s"specs/${name(rel)}.md"))).orDie
          _         <- ZIO.foreachDiscard(programs.take(1))(extractOne)

          // -- run 2 (resume): only the remaining programs are extracted.
          _         <- ZIO.foreachDiscard(programs)(rel => ZIO.whenZIO(notDone(rel))(extractOne(rel)))
          runs      <- extracted.get

          // -- the deterministic index rebuild: fragments concatenate into the traceability the gate checks.
          fragments <- ZIO.foreach(programs)(rel =>
                         ZIO.attemptBlocking(Files.readString(modDir.resolve(s"traceability/${name(rel)}.md"))).orDie
                       )
          _         <- write(modDir, "traceability.md", fragments.mkString("\n"))
          trace     <- ZIO.attemptBlocking(Files.readString(modDir.resolve("traceability.md"))).orDie
          coverage  <- SpecChecks.coverage(root, pack.coverage, trace)
        yield assertTrue(
          programs == List("cobol/ACCTXFR.cbl", "cobol/INTCALC.cbl", "jcl/XFERDLY.jcl"),
          runs.size == programs.size, // each program extracted exactly once across both runs
          runs.distinct.size == programs.size,
          coverage.isClean,
        )
      }
    },
    test("extract→seed handoff: the approval gate blocks a draft pack, a human approves, the target seeds and resumes") {
      ZIO.scoped {
        for
          root      <- tempDir
          legacyDir  = root.resolve("legacy")
          targetDir  = root.resolve("target")
          _         <- ZIO.attemptBlocking(Files.createDirectories(legacyDir)).orDie
          _         <- ZIO.attemptBlocking(Files.createDirectories(targetDir)).orDie
          legacyGit <- newRepo(legacyDir)
          targetGit <- newRepo(targetDir)
          pack      <- Pack.load(packDir())

          // -- extract.sc's deterministic tail: spec pack + plan + UNCHECKED approval marker, committed.
          plan       = Plan(
                         "modernize-acctxfr",
                         List(
                           Task("Encode BDD scenarios as failing acceptance tests", "features → JUnit, must be RED"),
                           Task("Implement transfer validation chain", "covers R1-R4"),
                         ),
                       )
          modDir     = legacyDir.resolve("docs/modernization")
          _         <- write(modDir, "specs/ACCTXFR.md", "# ACCTXFR\n\n## Business rules\nR1 ...")
          _         <- write(modDir, "features/acctxfr.feature", "Feature: transfers\n\n  Scenario: s\n    Given a\n")
          _         <- write(modDir, "traceability.md", "2100-VALIDATE-XFER — R1")
          _         <- write(modDir, "plan.md", plan.render)
          _         <- write(modDir, "README.md", ApprovalGate.withDraftMarker("# Spec pack"))
          _         <- legacyGit.commitAll("modernize: spec pack (draft)")

          // -- seed.sc first refuses: the marker is unchecked and CI is non-interactive.
          blocked   <- ApprovalGate.gate(modDir.resolve("README.md"), Interaction.noninteractive).flip

          // -- a human approves; the gate now passes.
          readme    <- ZIO.attemptBlocking(Files.readString(modDir.resolve("README.md"))).orDie
          _         <- write(modDir, "README.md", ApprovalGate.approve(readme))
          _         <- ApprovalGate.gate(modDir.resolve("README.md"), Interaction.noninteractive)

          // -- seed.sc's copy contract: specs + features land where the pack says, plan re-parses.
          _         <- ZIO.foreachDiscard(List("specs/ACCTXFR.md" -> s"${pack.specsDir}/ACCTXFR.md",
                         "features/acctxfr.feature" -> s"${pack.featuresDir}/acctxfr.feature",
                         "plan.md"                  -> "docs/modernization/plan.md",
                       )) { (from, to) =>
                         write(targetDir, to, Files.readString(modDir.resolve(from)))
                       }
          _         <- targetGit.commitAll("modernize: seed")
          planFile   = targetDir.resolve("docs/modernization/plan.md")
          seeded    <- PlanStore.load(planFile).someOrFail(FlowError.PlanParse("seeded plan missing"))
          features  <- SpecChecks.features(targetDir.resolve(pack.featuresDir))

          // -- implement.sc's resumable loop: first run crashes on task 2, rerun finishes only task 2.
          _         <- targetGit.checkoutOrCreate(seeded.epicId)
          firstRun  <- Ref.make(List.empty[String])
          crashed   <- implementTaskLoop(planFile, seeded) { task =>
                         firstRun.update(task.title :: _) *>
                           ZIO.when(task.title.contains("validation"))(ZIO.fail(FlowError.Aborted("crash"))).unit
                       }.flip
          afterCrash <- PlanStore.load(planFile).someOrFail(FlowError.PlanParse("plan lost after crash"))
          secondRun <- Ref.make(List.empty[String])
          _         <- implementTaskLoop(planFile, afterCrash) { task =>
                         secondRun.update(task.title :: _).unit
                       }
          finished  <- PlanStore.load(planFile).someOrFail(FlowError.PlanParse("plan lost after finish"))
          reran     <- secondRun.get
        yield assertTrue(
          blocked.isInstanceOf[FlowError.Aborted],
          seeded == plan,
          features.isClean,
          crashed == FlowError.Aborted("crash"),
          afterCrash.tasks.count(_.completed) == 1,
          reran == List("Implement transfer validation chain"),
          finished.tasks.forall(_.completed),
        )
      }
    },
  )
