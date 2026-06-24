//> using dep "io.github.riccardomerolla::llm4zio-runner:3.11.1"
//> using scala "3.8.3"
//> using jvm 21

/** Reverse-engineer an existing repository into documentation — a read-only comprehension
  * pipeline (the mirror image of pipeline.sc, which *builds* test-first):
  *
  *   Discover → Architecture → Domain model → ADRs → Reverse-spec → Review
  *
  * The read-only reasoning seat explores the repo and generates each artifact; the runtime —
  * not an agent — writes and commits the files, one commit per phase. Re-running skips artifacts
  * that already exist, so a crashed run resumes where it left off (no plan store needed).
  *
  *   - Discover (ProModel): an orientation brief — what the system is, its modules, entry points,
  *     tech stack, how to build/run.                                   → docs/discovery.md
  *   - Architecture (ProModel): components, responsibilities, data/control flow, key APIs, plus a
  *     C4-style Mermaid diagram.                                       → docs/architecture.md
  *   - Domain model (ProModel): bounded contexts, core domain types and relationships, ubiquitous
  *     language, with a Mermaid diagram.                               → docs/domain-model.md
  *   - ADRs (ProModel, structured): the significant architectural decisions inferred from the
  *     code, one file per decision.                                    → docs/adr/NNNN-title.md
  *   - Reverse-spec (ProModel): the behavioral contract as Given/When/Then capabilities — what a
  *     reimplementation would have to satisfy.                         → specs/reverse-spec.md
  *   - Review (FlashModel): an advisory completeness/accuracy audit of the docs vs the repo. It is
  *     advisory — it never fails the flow.                             → docs/review.md
  *
  * Each phase feeds the next (discovery informs architecture, etc.), so the docs stay coherent.
  *
  * Seats: default to all-gemini (Pro generates, Flash reviews). Set LLM4ZIO_CODER=claude|codex|pi
  * to run the whole flow on that provider instead (handy when gemini is unavailable). The coder
  * seat is unused — nothing here edits code.
  *
  * Excludes the tooling: this script seeds itself into the target repo, so every phase prompt tells
  * the agent to ignore `reverse-engineer.sc`, `.scala-build/`, and the llm4zio/ZIO/scala-cli context
  * — it documents the project, not the tool. The same paths are added to `.gitignore` so they never
  * land in the doc commits.
  *
  * Methodology lives in a library-defined `comprehensionSkill` bundled in this script (not an
  * ambient codex/claude/IDE skill): ground claims in code, cite repo-root-relative paths (never
  * absolute), cover the whole system (config, errors, observability, concurrency, utilities — not
  * just the happy path), don't overstate, and emit only the final artifact (no narration). Every
  * phase prompt prepends it; prose output is also defensively trimmed to its first heading.
  *
  * Seed a starter:  examples/seed.sh reverse-engineer
  * Run anywhere:    cd <any repo> && scala-cli run reverse-engineer.sc -- "for a new contributor"
  *
  * Requires `gemini` logged in (or the LLM4ZIO_CODER agent). No build tool needed — it only reads.
  */

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.json.JsonCodec
import zio.{ IO, ZIO }

import llm4zio.core.{ LlmService, SchemaDerivation }
import llm4zio.flow.*
import llm4zio.runner.*

val ProModel   = "gemini-3-pro-preview"
val FlashModel = "gemini-2.5-flash"

// Appended to every phase: the script seeds itself into the target repo, so the exploring agent
// would otherwise document the tool instead of the project. Keep the tool and its resolved
// dependencies out of the analysis.
val excludeNote =
  "\n\nEXCLUDE the documentation tooling from your analysis — it is NOT part of the system under " +
    "study. Do not read, describe, or reference the `reverse-engineer.sc` script, the `.scala-build/` " +
    "directory, or anything from the llm4zio library, ZIO, or scala-cli used to generate these docs. " +
    "Document only the actual project."

// A library-defined skill bundled with the example — NOT an ambient codex/claude/IDE skill. It
// rides inside the script (the only thing that travels when the example is copied into a repo), so
// the methodology is version-controlled and deterministic. Prepended to every phase prompt.
val comprehensionSkill =
  """SKILL — repository comprehension. Apply this in every phase:
    |- Work from evidence: inventory the repo, then read entry points, public APIs, and tests.
    |  Ground every claim in code — never rely on names, prose, or assumptions alone.
    |- Cite files with paths RELATIVE TO THE REPOSITORY ROOT (e.g. `src/foo/Bar.ext:12`). Never
    |  use absolute filesystem paths.
    |- Cover the whole system, not just the primary happy path: configuration, error handling,
    |  logging/metrics/observability, concurrency, persistence, public utility/library APIs, and
    |  extension points — not only the main feature flow.
    |- Distinguish what is actually wired/registered/used from what merely exists in the code;
    |  never overstate capabilities. Where behavior varies across implementations, present a small
    |  matrix instead of one blanket claim.
    |- Separate domain logic from infrastructure and side effects.
    |- Use ONLY the repository and these instructions — do not invoke any environment, user, or
    |  IDE-provided skill or tool beyond reading the repo.
    |- Output ONLY the requested deliverable: no narration, no "I'll use…/I'm reading…" preamble,
    |  no tool-call commentary. Begin directly with the document (or JSON).""".stripMargin

val discoverInstructions =
  """You are a code archaeologist. Explore this repository and write an orientation brief for a
    |new contributor: what the system does, its main modules/packages and their responsibilities,
    |the entry points, the tech stack, and how to build/test/run it. Plain Markdown, grounded in
    |the actual code — cite real file paths.""".stripMargin + excludeNote

val architectureInstructions =
  """You are a software architect. Document this repository's architecture: the major components
    |and their responsibilities, how data and control flow between them, the key public APIs/types,
    |and external dependencies, noting the effect shape of key operations (pure, bounded change, or
    |preview-returns-a-plan). Include a Mermaid diagram (a C4-style container or component view) in
    |a ```mermaid block. Plain Markdown grounded in the code — cite file paths.""".stripMargin + excludeNote

val domainInstructions =
  """You are a domain modeller (DDD). Identify the core domain of this system: the bounded contexts
    |(if any), the central domain types/entities and their relationships, and the ubiquitous
    |language the code uses. Include a Mermaid class or ER diagram of the domain model. Plain
    |Markdown grounded in the code.""".stripMargin + excludeNote

val adrInstructions =
  """You are an architect recovering the significant architectural decisions embedded in this
    |codebase. Infer the decisions that shaped it (e.g. effect system, error model, persistence
    |approach, module boundaries, concurrency) and record each as an ADR grounded in real evidence
    |from the code. Use status "accepted" for decisions the code clearly reflects.""".stripMargin +
    excludeNote +
    "\n\nRespond ONLY with JSON of the form:\n" +
    """{"adrs":[{"number":1,"title":"...","status":"accepted","context":"...","decision":"...","consequences":"..."}]}"""

val reverseSpecInstructions =
  """You are reverse-engineering the behavioral specification of this system. From the code, write
    |what it does as a set of capabilities with testable acceptance criteria in Given/When/Then
    |form — the contract a reimplementation would have to satisfy. Group by feature/command. Plain
    |Markdown grounded in the actual behavior; do not invent features.""".stripMargin + excludeNote

val reviewInstructions =
  """You are auditing generated documentation against the repository for completeness and accuracy.
    |Re-read the repo and the docs below, then report: coverage gaps (modules or behaviors left
    |undocumented), inaccuracies (claims the code does not support), and a short completeness
    |verdict. Plain Markdown — this is an advisory review.""".stripMargin + excludeNote

final case class Adr(
  number: Int,
  title: String,
  status: String,
  context: String,
  decision: String,
  consequences: String,
) derives JsonCodec

final case class AdrSet(adrs: List[Adr]) derives JsonCodec

val adrSchema = SchemaDerivation.derive[AdrSet]

def writeIfAbsent(path: Path, content: String): IO[FlowError, Boolean] =
  ZIO
    .attemptBlocking {
      if Files.exists(path) then false
      else
        Option(path.getParent).foreach(Files.createDirectories(_))
        Files.write(path, content.getBytes(StandardCharsets.UTF_8))
        true
    }
    .mapError(e => FlowError.Persistence(s"failed to write $path", Some(e)))

def readFile(path: Path): IO[FlowError, String] =
  ZIO
    .attemptBlocking(Files.readString(path))
    .mapError(e => FlowError.Persistence(s"failed to read $path", Some(e)))

// Belt-and-suspenders for the "no preamble" rule: if the model still prefixed the artifact with
// narration, drop everything before the first Markdown heading. Falls back to the raw text if the
// document has no heading at all.
def stripNarration(markdown: String): String =
  val body = markdown.linesIterator.dropWhile(l => !l.trim.startsWith("#")).mkString("\n").strip
  if body.isEmpty then markdown.strip else body

// Add any missing `entries` to `.gitignore` so `git add -A` never sweeps the tooling (the script
// and its `.scala-build/` deps) into the doc commits — which would also pollute later phases.
def ensureGitignored(gitignore: Path, entries: List[String]): IO[FlowError, Unit] =
  ZIO
    .attemptBlocking {
      val existing = if Files.exists(gitignore) then Files.readString(gitignore) else ""
      val present  = existing.linesIterator.map(_.trim).toSet
      val missing  = entries.filterNot(present.contains)
      if missing.nonEmpty then
        val prefix = if existing.nonEmpty && !existing.endsWith("\n") then existing + "\n" else existing
        Files.write(gitignore, (prefix + missing.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8))
    }
    .mapError(e => FlowError.Persistence("failed to update .gitignore", Some(e)))

def slugify(s: String): String =
  s.toLowerCase.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-)|(-$)", "").take(50)

def renderAdr(a: Adr): String =
  s"""# ${a.number}. ${a.title}
     |
     |- Status: ${a.status}
     |
     |## Context
     |${a.context}
     |
     |## Decision
     |${a.decision}
     |
     |## Consequences
     |${a.consequences}
     |""".stripMargin

// Seats default to all-gemini (Pro generates, Flash reviews). Set LLM4ZIO_CODER=claude|codex|gemini|pi
// to run the whole flow on that one provider (its own default model); reasoning + reviewer read-only.
val (coderCfg, reasoningCfg, reviewerCfg) =
  sys.env.get("LLM4ZIO_CODER").map(_.trim.toLowerCase).filter(_.nonEmpty) match
    case None | Some("gemini") =>
      (
        gemini.withModel(FlashModel),
        gemini.withModel(ProModel).copy(readOnly = true),
        gemini.withModel(FlashModel).copy(readOnly = true),
      )
    case Some(_) =>
      val agent = Connectors.coderFromEnv()
      (agent, agent.copy(readOnly = true), agent.copy(readOnly = true))

flow(
  args,
  coder = coderCfg,
  reasoning = Some(reasoningCfg),
  reviewers = List(reviewerCfg),
  defaultPrompt = Some("Document this repository for a new contributor."),
):
  val reviewSvc = reviewers.headOption.getOrElse(reasoning)
  val branch    = "docs/reverse-engineer"

  // Generate one prose artifact (or reuse it on resume), write and commit it, and return its text
  // so later phases can build on it.
  def proseDoc(svc: LlmService, stageName: String, rel: String, instructions: String, input: String)
    : IO[FlowError, String] =
    stage(stageName) {
      val path = workDir.resolve(rel)
      ZIO.attemptBlocking(Files.exists(path)).orElseSucceed(false).flatMap {
        case true  => readFile(path) // resume — reuse the existing artifact
        case false =>
          for
            raw <- Planner.brief(svc, input, s"$comprehensionSkill\n\n$instructions")
            text = stripNarration(raw)
            _   <- writeIfAbsent(path, text)
            _   <- git.commitAll(s"docs: ${rel}").unit
          yield text
      }
    }

  for
    _         <- stage("Branch")(git.checkoutOrCreate(branch))
    _         <- stage("Ignore tooling") {
                   ensureGitignored(
                     workDir.resolve(".gitignore"),
                     List(".scala-build/", ".bsp/", "reverse-engineer.sc"),
                   ) *> git.commitAll("docs: ignore reverse-engineer tooling").unit
                 }
    discovery <- proseDoc(reasoning, "Discover", "docs/discovery.md", discoverInstructions, userPrompt)
    architect <- proseDoc(
                   reasoning,
                   "Architecture",
                   "docs/architecture.md",
                   architectureInstructions,
                   s"Focus: $userPrompt\n\nDiscovery brief:\n$discovery",
                 )
    domain    <- proseDoc(
                   reasoning,
                   "Domain model",
                   "docs/domain-model.md",
                   domainInstructions,
                   s"Architecture:\n$architect",
                 )
    _         <- stage("ADRs") {
                   ZIO.attemptBlocking(Files.exists(workDir.resolve("docs/adr"))).orElseSucceed(false).flatMap {
                     case true  => ZIO.unit // resume — ADRs already written
                     case false =>
                       for
                         set <- reasoning
                                  .executeStructured[AdrSet](
                                    s"$comprehensionSkill\n\n$adrInstructions\n\nArchitecture:\n$architect\n\nDomain model:\n$domain",
                                    adrSchema,
                                  )
                                  .mapError(e => FlowError.Llm(e.toString))
                         _   <- ZIO.foreachDiscard(set.adrs) { a =>
                                  writeIfAbsent(
                                    workDir.resolve(f"docs/adr/${a.number}%04d-${slugify(a.title)}.md"),
                                    renderAdr(a),
                                  )
                                }
                         _   <- git.commitAll("docs: adrs").unit
                       yield ()
                   }
                 }
    spec      <- proseDoc(
                   reasoning,
                   "Reverse-spec",
                   "specs/reverse-spec.md",
                   reverseSpecInstructions,
                   s"Discovery:\n$discovery\n\nArchitecture:\n$architect",
                 )
    _         <- proseDoc(
                   reviewSvc,
                   "Review",
                   "docs/review.md",
                   reviewInstructions,
                   s"Discovery:\n$discovery\n\nArchitecture:\n$architect\n\nDomain model:\n$domain\n\nReverse-spec:\n$spec",
                 )
  yield ()
