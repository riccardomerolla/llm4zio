//> using dep "io.github.riccardomerolla::llm4zio-runner:3.6.1"
//> using scala "3.8.3"
//> using jvm 21

/** Reverse-engineer THIS repository (llm4zio) into documentation — a read-only comprehension
  * pipeline. This file lives at the repo root and is meant to document llm4zio itself.
  *
  *   Discover → Architecture → Domain model → ADRs → Reverse-spec → Review → Address review
  *
  * The read-only reasoning seat explores the repo and generates each artifact; the runtime — not an
  * agent — writes and commits the files on a `docs/reverse-engineer` branch, one commit per phase.
  *
  *   - Discover       → docs/discovery.md
  *   - Architecture   → docs/architecture.md      (quality attributes + C4-style Mermaid)
  *   - Domain model   → docs/domain-model.md       (Mermaid)
  *   - ADRs           → docs/adr/NNNN-title.md      (status / context / decision / alternatives / consequences)
  *   - Reverse-spec   → specs/reverse-spec.md       (Given/When/Then capabilities)
  *   - Review         → docs/review.md              (advisory completeness/accuracy audit, then consumed below)
  *   - Address review → applies the audit's findings back to the prose docs, then DELETES docs/review.md
  *                      and commits — a self-correcting pass, so the final tree has corrected docs and
  *                      no leftover audit file.
  *
  * Resume vs update: by default a re-run SKIPS artifacts that already exist (a crashed run resumes).
  * Set LLM4ZIO_DOCS_UPDATE=1 to instead REGENERATE each doc from its previous version as a starting
  * point — refreshing in place to match the current code rather than starting from scratch.
  *
  * Provider: defaults to Claude Code — Opus 4.8 for generation + ADR reasoning, Sonnet for the
  * advisory review. As of llm4zio 3.6.1 the read-only seats answer directly (default permission mode
  * with edit tools disallowed), so they cannot try to write files themselves. Set
  * LLM4ZIO_CODER=codex|gemini|pi to run every seat on another provider's read-only twin.
  *
  * Scope: this documents llm4zio itself, so the repo's OWN source code is the system under study and
  * is fully included — there is no source exclusion. The build cache (`.scala-build/`, `.bsp/`) is
  * kept out of the doc commits via `.gitignore`; this script is a tracked tool but is still excluded
  * from the analysis (the agent is told to ignore it — it documents the product, not the tooling).
  *
  * Methodology lives in a bundled `comprehensionSkill` (not an ambient skill): ground claims in code,
  * cite repo-root-relative paths, cover the whole system, don't overstate, emit only the final
  * artifact. Prose output is also trimmed to its first heading.
  *
  * Run:  scala-cli run reverse-engineer.sc -- "for a new contributor"
  *       LLM4ZIO_DOCS_UPDATE=1 scala-cli run reverse-engineer.sc -- "for a new contributor"   # refresh
  *       (from the llm4zio repo root; requires the `claude` CLI logged in, or the LLM4ZIO_CODER agent)
  */

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import scala.jdk.CollectionConverters.*

import zio.json.JsonCodec
import zio.{ IO, ZIO }

import llm4zio.core.{ LlmService, SchemaDerivation }
import llm4zio.flow.*
import llm4zio.runner.*

// Appended to every phase. llm4zio is the subject, so its own source is documented — nothing about
// the product is excluded. Only the build cache and this tool script are out of scope.
val scopeNote =
  "\n\nScope: document the WHOLE repository, including all of llm4zio's own source code — it is the " +
    "system under study. Ignore only the build cache and this tool itself: do not read or describe " +
    "the `.scala-build/` or `.bsp/` directories or the `reverse-engineer.sc` script."

// A library-defined skill bundled in this script (not an ambient codex/claude/IDE skill): the
// methodology, version-controlled and deterministic. Prepended to every phase prompt.
val comprehensionSkill =
  """SKILL — repository comprehension. Apply this in every phase:
    |- Work from evidence: inventory the repo, then read entry points, public APIs, and tests.
    |  Ground every claim in code — never rely on names, prose, or assumptions alone.
    |- Cite files with paths RELATIVE TO THE REPOSITORY ROOT (e.g. `modules/foo/Bar.scala:12`). Never
    |  use absolute filesystem paths.
    |- Cover the whole system, not just the primary happy path: configuration, error handling,
    |  logging/metrics/observability, concurrency, persistence, public utility/library APIs, and
    |  extension points — not only the main feature flow.
    |- Distinguish what is actually wired/registered/used from what merely exists in the code;
    |  never overstate capabilities. Where behavior varies across implementations, present a small
    |  matrix instead of one blanket claim.
    |- Separate domain logic from infrastructure and side effects.
    |- Cross-reference: where it aids navigation, point from a claim to the related decision or
    |  component and cite the file, so the docs form a connected set rather than islands.
    |- Use ONLY the repository and these instructions — do not invoke any environment, user, or
    |  IDE-provided skill or tool beyond reading the repo.
    |- You CANNOT write or save files and must not try — the runtime saves your reply for you.
    |  Produce the COMPLETE document as your reply text; never offer to "write the file" or ask for
    |  approval to save. Output ONLY the deliverable: no narration, no "I'll use…/I'm reading…"
    |  preamble, no tool-call commentary. For a prose document, begin with a single top-level
    |  `# ` heading that names it, then the body. For JSON, output only the JSON object.""".stripMargin

val discoverInstructions =
  """You are a code archaeologist. Explore this repository and write an orientation brief for a
    |new contributor: what the system does, its main modules/packages and their responsibilities,
    |the entry points, the tech stack, and how to build/test/run it. Plain Markdown, grounded in
    |the actual code — cite real file paths.""".stripMargin + scopeNote

val architectureInstructions =
  """You are a software architect. Document this repository's architecture: the major components
    |and their responsibilities, how data and control flow between them, the key public APIs/types,
    |and external dependencies, noting the effect shape of key operations (pure, bounded change, or
    |preview-returns-a-plan). Call out the key quality attributes the design optimises for (e.g.
    |testability, resilience, extensibility) and the cross-cutting concerns (error handling,
    |concurrency, observability, configuration) with any notable trade-offs. Include a Mermaid
    |diagram and, where useful, more than one C4 level (system context, then containers/components).
    |Plain Markdown grounded in the code — cite file paths.""".stripMargin + scopeNote

val domainInstructions =
  """You are a domain modeller (DDD). Identify the core domain of this system: the bounded contexts
    |(if any), the central domain types/entities and their relationships, and the ubiquitous
    |language the code uses. Include a Mermaid class or ER diagram of the domain model. Plain
    |Markdown grounded in the code.""".stripMargin + scopeNote

val adrInstructions =
  """You are an architect recovering the significant architectural decisions embedded in this
    |codebase. Infer the decisions that shaped it (e.g. effect system, error model, persistence
    |approach, module boundaries, concurrency) and record each as an ADR grounded in real evidence
    |from the code. Each ADR has: a status (Proposed | Accepted | Deprecated | Superseded — use
    |"Accepted" for decisions the code clearly reflects), the context/forces, the decision, at least
    |two alternatives that were considered with why they were not taken, and the consequences.""".stripMargin +
    scopeNote +
    "\n\nOutput the JSON object as your ENTIRE response — do not describe a plan, ask for approval, " +
    "or add any prose before or after it. Respond ONLY with JSON of the form:\n" +
    """{"adrs":[{"number":1,"title":"...","status":"Accepted","context":"...","decision":"...","alternatives":"...","consequences":"..."}]}"""

val reverseSpecInstructions =
  """You are reverse-engineering the behavioral specification of this system. From the code, write
    |what it does as a set of capabilities with testable acceptance criteria in Given/When/Then
    |form — the contract a reimplementation would have to satisfy. Group by feature/command. Plain
    |Markdown grounded in the actual behavior; do not invent features.""".stripMargin + scopeNote

val reviewInstructions =
  """You are auditing the generated documentation against the repository for completeness and
    |accuracy. Re-read the repo and the docs below, then report: coverage gaps (modules or behaviors
    |left undocumented), inaccuracies (claims the code does not support), any place a document mixes
    |distinct purposes (reference vs explanation vs how-to) that should be split, and an overall
    |verdict — Approved / Needs-revision / Restructure-required — with a one-line justification.
    |Plain Markdown — this is an advisory review.""".stripMargin + scopeNote

final case class Adr(
  number: Int,
  title: String,
  status: String,
  context: String,
  decision: String,
  alternatives: String,
  consequences: String,
) derives JsonCodec

final case class AdrSet(adrs: List[Adr]) derives JsonCodec

val adrSchema = SchemaDerivation.derive[AdrSet]

// Regenerate from the previous docs directory rather than skipping existing artifacts.
val updateMode = sys.env.get("LLM4ZIO_DOCS_UPDATE").exists(v => Set("1", "true", "yes").contains(v.trim.toLowerCase))

def writeFile(path: Path, content: String): IO[FlowError, Unit] =
  ZIO
    .attemptBlocking {
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      ()
    }
    .mapError(e => FlowError.Persistence(s"failed to write $path", Some(e)))

def readFile(path: Path): IO[FlowError, String] =
  ZIO
    .attemptBlocking(Files.readString(path))
    .mapError(e => FlowError.Persistence(s"failed to read $path", Some(e)))

def exists(path: Path): IO[FlowError, Boolean] =
  ZIO.attemptBlocking(Files.exists(path)).orElseSucceed(false)

// Markdown files directly under `dir`, sorted; empty if the dir is absent.
def listMd(dir: Path): IO[FlowError, List[Path]] =
  ZIO
    .attemptBlocking {
      if !Files.exists(dir) then Nil
      else
        val tree = Files.list(dir)
        try tree.iterator().asScala.filter(_.toString.endsWith(".md")).toList.sorted
        finally tree.close()
    }
    .mapError(e => FlowError.Persistence(s"failed to list $dir", Some(e)))

def deleteFile(path: Path): IO[FlowError, Unit] =
  ZIO.attemptBlocking(Files.deleteIfExists(path)).unit.mapError(e => FlowError.Persistence(s"failed to delete $path", Some(e)))

// Belt-and-suspenders for the "no preamble" rule: if the model still prefixed the artifact with
// narration, drop everything before the first Markdown heading. Falls back to the raw text if the
// document has no heading at all.
def stripNarration(markdown: String): String =
  val body = markdown.linesIterator.dropWhile(l => !l.trim.startsWith("#")).mkString("\n").strip
  if body.isEmpty then markdown.strip else body

// Add any missing `entries` to `.gitignore` so `git add -A` never sweeps the build cache into the
// doc commits. Commit hygiene only — not an analysis exclusion of source.
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
     |## Alternatives considered
     |${a.alternatives}
     |
     |## Consequences
     |${a.consequences}
     |""".stripMargin

// Models: Opus 4.8 for generation + ADR reasoning, Sonnet for the cheaper advisory review.
val OpusModel   = "opus"
val SonnetModel = "sonnet"

// Read-only analysis seats. As of llm4zio 3.6.1, claude's read-only flag answers directly (default
// permission mode + edit tools disallowed) instead of entering plan mode, so the structured ADR step
// works and the agent cannot try to write files itself. Provider defaults to claude (Opus reasoning,
// Sonnet review); LLM4ZIO_CODER=codex|gemini|pi switches every seat to that provider's read-only twin.
val provider = sys.env.getOrElse("LLM4ZIO_CODER", "claude").trim.toLowerCase
val (coderCfg, reasoningCfg, reviewerCfg) =
  if provider == "claude" then
    (claude, claude.withModel(OpusModel).copy(readOnly = true), claude.withModel(SonnetModel).copy(readOnly = true))
  else
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

  // Generate one prose artifact, write and commit it, and return its text so later phases build on
  // it. Resume: if it exists and we're not updating, reuse it. Update (LLM4ZIO_DOCS_UPDATE): feed the
  // previous version back in and regenerate in place.
  def proseDoc(svc: LlmService, stageName: String, rel: String, instructions: String, input: String)
    : IO[FlowError, String] =
    stage(stageName) {
      val path = workDir.resolve(rel)
      exists(path).flatMap { present =>
        if present && !updateMode then readFile(path)
        else
          for
            prior <- if present then readFile(path) else ZIO.succeed("")
            ctx    =
              if present then
                s"$input\n\nPREVIOUS VERSION OF THIS DOCUMENT — update it to match the current code: keep what is" +
                  s" still accurate, correct what is stale, fill gaps, and do not drop useful detail:\n$prior"
              else input
            raw   <- Planner.brief(svc, ctx, s"$comprehensionSkill\n\n$instructions")
            text   = stripNarration(raw)
            _     <- writeFile(path, text)
            _     <- git.commitAll(s"docs: ${if present then "update" else "add"} $rel").unit
          yield text
      }
    }

  // Apply the review's findings to one prose document, in place (no commit — the Address stage
  // commits once for the whole pass). A document the review names nothing for comes back unchanged.
  def reviseDoc(rel: String, instructions: String, review: String): IO[FlowError, Unit] =
    val path = workDir.resolve(rel)
    exists(path).flatMap {
      case false => ZIO.unit
      case true  =>
        for
          current <- readFile(path)
          raw     <- Planner.brief(
                       reasoning,
                       "Apply the review findings to the document below: correct every inaccuracy and fill" +
                         " every coverage gap the review names for THIS document, keep what is already correct," +
                         " and add no claim that isn't grounded in the code (re-verify against the repo). If" +
                         " the review names nothing for this document, return it unchanged.\n\nREVIEW FINDINGS:\n" +
                         review + s"\n\nDOCUMENT ($rel):\n$current",
                       s"$comprehensionSkill\n\n$instructions",
                     )
          _       <- writeFile(path, stripNarration(raw))
        yield ()
    }

  for
    _         <- stage("Branch")(git.checkoutOrCreate(branch))
    _         <- stage("Ignore build cache") {
                   ensureGitignored(
                     workDir.resolve(".gitignore"),
                     List(".scala-build/", ".bsp/"),
                   ) *> git.commitAll("docs: ignore scala-cli build cache").unit
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
                   val adrDir = workDir.resolve("docs/adr")
                   exists(adrDir).flatMap { present =>
                     if present && !updateMode then ZIO.unit // resume — ADRs already written
                     else
                       for
                         priorFiles <- listMd(adrDir)
                         priors     <- ZIO.foreach(priorFiles)(readFile)
                         priorCtx    =
                           if priors.nonEmpty then
                             "\n\nPREVIOUS ADRs — keep stable decisions and their numbers, revise stale ones, add" +
                               s" new ones:\n${priors.mkString("\n\n")}"
                           else ""
                         set        <- reasoning
                                         .executeStructured[AdrSet](
                                           s"$comprehensionSkill\n\n$adrInstructions\n\nArchitecture:\n$architect" +
                                             s"\n\nDomain model:\n$domain$priorCtx",
                                           adrSchema,
                                         )
                                         .mapError(e => FlowError.Llm(e.toString))
                         _          <- ZIO.foreachDiscard(priorFiles)(deleteFile) // clear stale before rewrite
                         _          <- ZIO.foreachDiscard(set.adrs) { a =>
                                         writeFile(adrDir.resolve(f"${a.number}%04d-${slugify(a.title)}.md"), renderAdr(a))
                                       }
                         _          <- git.commitAll(if present then "docs: update adrs" else "docs: adrs").unit
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
    // Self-correct: read the audit, apply its findings to the prose docs, then drop the audit so the
    // final tree carries corrected docs and no review file. One commit closes the loop.
    _         <- stage("Address review") {
                   val reviewPath = workDir.resolve("docs/review.md")
                   exists(reviewPath).flatMap {
                     case false => ZIO.unit
                     case true  =>
                       for
                         review <- readFile(reviewPath)
                         _      <- ZIO.foreachDiscard(
                                     List(
                                       "docs/discovery.md"     -> discoverInstructions,
                                       "docs/architecture.md"  -> architectureInstructions,
                                       "docs/domain-model.md"  -> domainInstructions,
                                       "specs/reverse-spec.md" -> reverseSpecInstructions,
                                     )
                                   ) { case (rel, instr) => reviseDoc(rel, instr, review) }
                         _      <- deleteFile(reviewPath)
                         _      <- git.commitAll("docs: address review findings, drop the audit").unit
                       yield ()
                   }
                 }
  yield ()
