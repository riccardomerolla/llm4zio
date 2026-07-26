//> using dep "io.github.riccardomerolla::llm4zio-runner:3.23.0"
//> using scala "3.8.3"
//> using jvm 21

/** Legacy-modernization phase 2 of 5: seed the target repo from the APPROVED spec pack.
  *
  *   modernize-extract.sc → (human approves) → modernize-seed.sc → modernize-implement.sc
  *     → modernize-verify.sc → modernize-review.sc
  *
  * Runs ROOTED AT THE TARGET REPO (`--repo <target>`), reading the spec pack out of the legacy
  * repo (`LLM4ZIO_LEGACY_REPO`). This phase is deliberately deterministic — no LLM calls:
  *
  *   1. `ApprovalGate.gate` REFUSES to run until a human has flipped `- [x] Approved` in the
  *      legacy repo's `docs/modernization/README.md` (extract.sc writes it unchecked).
  *   2. If the target repo is empty, the pack's scaffold is copied in (the bank-provided
  *      starter); an existing repo is used as-is.
  *   3. Specs / traceability / mapping land in the pack's `specs-dir`; .feature files land in
  *      the pack's `features-dir` (for cobol-springboot: src/test/resources/features, where the
  *      acceptance tests will read them).
  *   4. The proposed plan is materialized at `docs/modernization/plan.md` — extract.sc wrote it
  *      in the canonical `Plan.render` Markdown, so this phase re-parses it as a hard validation
  *      and modernize-implement.sc resumes it with `PlanStore`.
  *   5. The PROVENANCE MANIFEST (`docs/modernization/provenance.json`) is written and committed:
  *      the clean-room receipt — spec-pack file hashes (plain sha256, `shasum`-checkable), gate
  *      verdict digests, pack, llm4zio version, and the approver (`LLM4ZIO_APPROVER`, when set).
  *      modernize-verify.sc appends the equivalence-report hash to it later.
  *   6. Azure DevOps (optional): when ADO env vars are present (SYSTEM_COLLECTIONURI /
  *      LLM4ZIO_ADO_* + PAT), one Task work item is created per plan task, carrying the task
  *      description as acceptance criteria. Absent config = skipped with an Info event.
  *
  * Run:  LLM4ZIO_LEGACY_REPO=~/estates/meridian-legacy \
  *       scala-cli run modernize-seed.sc -- --repo ~/services/meridian-transfers
  */

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.json.*
import zio.{ IO, ZIO }

import llm4zio.flow.*
import llm4zio.runner.*

val ModDir         = "docs/modernization"
val Llm4zioVersion = "3.23.0" // keep in step with the `using dep` header pin

def writeFile(path: Path, content: String): IO[FlowError, Unit] =
  ZIO
    .attemptBlocking {
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      ()
    }
    .mapError(e => FlowError.Persistence(s"failed to write $path", Some(e)))

/** Copy a directory tree, skipping build/VCS litter. */
def copyTree(src: Path, dst: Path, skip: Set[String] = Set(".git", "target", "node_modules")): IO[FlowError, Int] =
  ZIO
    .attemptBlocking {
      val stream = Files.walk(src)
      val files  =
        try stream.iterator().asScala.filter(Files.isRegularFile(_)).toList
        finally stream.close()
      val kept   = files.filterNot(f => src.relativize(f).iterator().asScala.exists(p => skip(p.toString)))
      kept.foreach { f =>
        val to = dst.resolve(src.relativize(f).toString)
        Option(to.getParent).foreach(Files.createDirectories(_))
        Files.copy(f, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      }
      kept.size
    }
    .mapError(e => FlowError.Persistence(s"failed to copy $src -> $dst", Some(e)))

/** Copy one file if it exists, creating parent directories; false when the source is absent. */
def copyFile(src: Path, dst: Path): IO[FlowError, Boolean] =
  ZIO
    .attemptBlocking {
      if !Files.exists(src) then false
      else
        Option(dst.getParent).foreach(Files.createDirectories(_))
        Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        true
    }
    .mapError(e => FlowError.Persistence(s"failed to copy $src -> $dst", Some(e)))

/** Files directly under `dir`, `.git` aside — empty means "fresh repo, scaffold me". */
def nonGitEntries(dir: Path): IO[FlowError, List[Path]] =
  ZIO
    .attemptBlocking {
      val stream = Files.list(dir)
      try stream.iterator().asScala.filterNot(_.getFileName.toString == ".git").toList
      finally stream.close()
    }
    .mapError(e => FlowError.Persistence(s"failed to list $dir", Some(e)))

flow(
  args,
  defaultPrompt = Some("Seed the target repository from the approved spec pack"),
):
  val packDir    = workspace.resolve(sys.env.getOrElse("LLM4ZIO_PACK", "packs/cobol-springboot"))
  val legacyRepo = sys.env.get("LLM4ZIO_LEGACY_REPO").map(p => workspace.resolve(p).normalize)
  val events     = summon[FlowEvents]

  for
    pack     <- stage("Pack")(Pack.load(packDir))
    legacy   <- ZIO
                  .fromOption(legacyRepo)
                  .orElseFail(FlowError.Aborted("set LLM4ZIO_LEGACY_REPO to the legacy repo holding the spec pack"))
    specPack  = legacy.resolve(ModDir)
    _        <- stage("Approval")(ApprovalGate.gate(specPack.resolve("README.md"), Interaction.noninteractive))
    _        <- stage("Scaffold") {
                  nonGitEntries(workDir).flatMap {
                    case Nil =>
                      pack.scaffold match
                        case Some(rel) =>
                          val scaffold = pack.dir.resolve(rel).normalize
                          copyTree(scaffold, workDir).flatMap(n =>
                            events.publish(FlowEvent.Info(s"scaffolded $n file(s) from $scaffold"))
                          )
                        case None      =>
                          ZIO.fail(FlowError.Aborted(s"target repo is empty and pack '${pack.name}' has no scaffold"))
                    case _   => events.publish(FlowEvent.Info("target repo is not empty — using it as-is"))
                  }
                }
    seeded   <- stage("Seed") {
                  for
                    n1 <- copyTree(specPack.resolve("specs"), workDir.resolve(pack.specsDir))
                    n2 <- copyTree(specPack.resolve("features"), workDir.resolve(pack.featuresDir))
                    _  <- ZIO.foreachDiscard(List("traceability.md", "mapping.md", "rules.txt")) { f =>
                            copyFile(specPack.resolve(f), workDir.resolve(pack.specsDir).resolve(f))
                          }
                  yield n1 + n2
                }
    plan     <- stage("Plan") {
                  for
                    text   <- ZIO
                                .attemptBlocking(Files.readString(specPack.resolve("plan.md")))
                                .mapError(e =>
                                  FlowError.Persistence(s"spec pack has no plan.md — did extract.sc pass its gate?", Some(e))
                                )
                    parsed <- ZIO.fromEither(Plan.parse(text)).mapError(FlowError.PlanParse(_))
                    _      <- writeFile(workDir.resolve(ModDir).resolve("plan.md"), parsed.render)
                  yield parsed
                }
    _        <- stage("Provenance") {
                  for
                    specFiles <- SpecChecks.matchingFiles(workDir.resolve(pack.specsDir), """.*\.md""")
                    hashes    <- Provenance.hashFiles(workDir, specFiles.map(f => s"${pack.specsDir}/$f"))
                    gateFiles <- SpecChecks
                                   .matchingFiles(specPack.resolve("gate"), """.*\.json""")
                                   .orElseSucceed(Nil) // pre-3.13 spec packs have no gate/ directory
                    verdicts  <- Provenance.hashFiles(specPack.resolve("gate"), gateFiles)
                    seats     <- ZIO
                                   .attemptBlocking(Files.readString(specPack.resolve("seats.json")))
                                   .map(_.fromJson[Map[String, String]].getOrElse(Map.empty))
                                   .orElseSucceed(Map.empty) // pre-3.23 spec packs have no seats sidecar
                    now       <- zio.Clock.instant
                    _         <- Provenance.write(
                                   workDir.resolve(ModDir).resolve("provenance.json"),
                                   Provenance(
                                     schema = 1,
                                     pack = pack.name,
                                     llm4zioVersion = Llm4zioVersion,
                                     createdAt = now.toString,
                                     approvedBy = sys.env.get("LLM4ZIO_APPROVER"),
                                     seats = seats,
                                     specs = hashes,
                                     gateVerdicts = verdicts.map((f, h) => f.stripSuffix(".json") -> h),
                                     equivalenceReport = None,
                                     fixSpecs = Nil,
                                   ),
                                 )
                  yield ()
                }
    _        <- stage("Commit")(
                  git.commitAll(s"modernize(${pack.name}): seed specs, features, plan, and provenance " +
                    s"($seeded file(s))").unit
                )
    _        <- stage("Boards") {
                  Ado.configFrom(sys.env) match
                    case Left(_)  =>
                      events.publish(FlowEvent.Info("Azure DevOps not configured — skipping work items"))
                    case Right(_) =>
                      Ado.withTool() { ado =>
                        ZIO.foreachDiscard(plan.tasks) { task =>
                          ado
                            .createWorkItem(
                              "Task",
                              s"${plan.epicId}: ${task.title}",
                              Map(
                                "System.Description"                        -> task.description,
                                "Microsoft.VSTS.Common.AcceptanceCriteria" -> task.description,
                              ),
                            )
                            .flatMap(id => events.publish(FlowEvent.Info(s"work item $id: ${task.title}")))
                        }
                      }
                }
    _        <- events.publish(FlowEvent.Info(s"target seeded — run modernize-implement.sc with the same --repo"))
  yield plan.epicId
