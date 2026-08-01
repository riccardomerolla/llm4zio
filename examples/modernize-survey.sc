//> using dep "io.github.riccardomerolla::llm4zio-runner:4.3.0"
//> using scala "3.8.3"
//> using jvm 21

/** Legacy-modernization phase 0 of 5: survey the estate — inventory, dependency graph, triage,
  * and a human-approved wave plan with a cost projection.
  *
  *   modernize-survey.sc → (human approves waves) → modernize-extract.sc → … → modernize-review.sc
  *
  * Runs ROOTED AT THE LEGACY REPO (`--repo <legacy>`). Banks stall before extraction because
  * nobody can answer "what do we have, what depends on what, in what order do we migrate" —
  * this phase answers all three, deterministic-first:
  *
  *   1. GRAPH (deterministic, `flow.Survey` — no LLM): one node per file matching the pack's
  *      `sources:` regex (size = lines + coverage units), one edge per `## Survey:` rule match
  *      (CALL / COPY / EXEC PGM=…). Written as `docs/modernization/inventory.md` (auditable
  *      Markdown) + `graph.json` (machine-readable), committed.
  *   2. GRAPH REFINE (LLM, evidence-gated): complex estates defeat the regexes — dynamic CALLs
  *      (`CALL WS-PGM`), JCL symbolic parameters (`EXEC PGM=&PGM`), PROC expansions. `reasoning`
  *      (read-only, rooted at the repo) reads the sources and proposes the edges the regexes
  *      missed, each with the source statement as evidence; `Survey.merge` validates them
  *      (known units only, no duplicates, no self-loops) and tags survivors `llm-…` so they
  *      stay distinguishable from regex-derived edges. Audit trail (kept AND dropped, with
  *      evidence) in `docs/modernization/graph-refine.md`. Skip with LLM4ZIO_GRAPH_REFINE=off.
  *   3. TRIAGE (LLM, bounded to the graph): `reasoning` classifies each unit
  *      rewrite | retire | wrap (wrap = keep and front with an API — classified here,
  *      implementing wraps is out of scope) and proposes dependency-coherent WAVES.
  *   4. WAVE PLAN (human-gated): `docs/modernization/wave-plan.md` carries the waves, the
  *      per-unit dispositions, and — when `bench-results.jsonl` exists next to the scripts — a
  *      per-wave cost/duration projection from real measured runs (`bench-report --project`).
  *      The plan gets an UNCHECKED approval marker: a person reviews, flips it, and only then
  *      does extraction start. Scope extraction to one wave with LLM4ZIO_WAVE=<name>.
  *
  * Run:  scala-cli run modernize-survey.sc -- --repo ~/estates/meridian-legacy
  */

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.json.*
import zio.{ IO, ZIO }

import llm4zio.core.SchemaDerivation
import llm4zio.flow.*
import llm4zio.runner.*

// The runtime capability mint for a script: `flow(...)`'s own `Caps.All` given is scoped to the lambda passed to it,
// so top-level `def`s in this file (which call `git.*`) need their own. Static witness only — the ambient `Grants`
// FiberRef still gates every call at runtime, so this widens nothing. `Caps.grantAll` is package-private, so a
// script uses the documented public hatch `Caps.unsafe.all` — deliberately loud and greppable.
given llm4zio.flow.Caps.All = zio.Unsafe.unsafe(implicit u => llm4zio.flow.Caps.unsafe.all)


val ProModel = "gemini-2.5-pro" // point these at whatever your `gemini` CLI offers
val ModDir   = "docs/modernization"

val (coderCfg, reasoningCfg) =
  sys.env.get("LLM4ZIO_CODER").map(_.trim.toLowerCase).filter(_.nonEmpty) match
    case None | Some("gemini") => (gemini, gemini.withModel(ProModel).copy(readOnly = true))
    case Some(_)               =>
      val agent = Connectors.coderFromEnv()
      (agent, agent.copy(readOnly = true))

/** One unit's disposition: rewrite (full modernization), retire (dead — decommission), or wrap
  * (keep on the mainframe, front with an API; implementing the wrap is out of scope here).
  */
case class NodeTriage(name: String, disposition: String, rationale: String) derives JsonCodec

case class WaveSlice(name: String, programs: List[String], rationale: String) derives JsonCodec

case class SurveyOutcome(triage: List[NodeTriage], waves: List[WaveSlice], notes: List[String]) derives JsonCodec

/** One regex-missed dependency the LLM found by reading the sources: endpoints must be inventory units and `evidence`
  * cites the statement that establishes the link (`Survey.merge` drops anything else).
  */
case class RefinedEdge(from: String, to: String, kind: String, evidence: String) derives JsonCodec

case class GraphRefinement(edges: List[RefinedEdge], notes: List[String]) derives JsonCodec

def writeFile(path: Path, content: String): IO[FlowError, Unit] =
  ZIO
    .attemptBlocking {
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      ()
    }
    .mapError(e => FlowError.Persistence(s"failed to write $path", Some(e)))

def refinePrompt(graph: SurveyGraph): String =
  s"""You are refining the dependency graph of a legacy estate. The graph below was built
     |deterministically — one node per source file, one edge per regex match (CALL / COPY /
     |EXEC PGM=…). Regexes miss links in complex sources: dynamic CALLs (CALL WS-PROGRAM),
     |JCL symbolic parameters (EXEC PGM=&PGM), PROC expansions, control cards naming programs.
     |You have read-only access to the estate — read the sources (each node's "path" names its
     |file) and find the dependency edges the regexes missed. Prioritise the suspicious shapes:
     |jobs with fewer outgoing edges than steps, programs nothing references, units with
     |degree 0.
     |
     |Produce:
     |- "edges": ONLY links the graph does not already have, and ONLY between the units listed
     |  below (use the exact unit names). Each edge: "from" (the referencing unit), "to" (the
     |  referenced unit), "kind" (how the link is made: dynamic-call, proc-step, control-card,
     |  …), and "evidence" (file, line, and the statement that establishes the link — no
     |  evidence, no edge). Calls to external/system programs are NOT edges; put them in
     |  "notes".
     |- "notes": references you could not resolve to a unit — dynamic variables whose value you
     |  could not trace, external systems. Empty if none.
     |
     |Units: ${graph.nodes.map(_.name).sorted.mkString(", ")}
     |
     |Graph (JSON):
     |${graph.toJson}""".stripMargin

def renderRefine(refinement: GraphRefinement, kept: List[SurveyEdge]): String =
  val keptKeys = kept.map(e => (e.from, e.to)).toSet
  val cell     = (s: String) => s.linesIterator.mkString(" ").replace("|", "\\|")
  val rows     = refinement.edges.map { e =>
    val status = if keptKeys((e.from, e.to)) then "added" else "dropped — duplicate, self-loop, or unknown unit"
    s"| ${e.from} | ${e.to} | ${e.kind} | $status | ${cell(e.evidence)} |"
  }
  val notes    =
    if refinement.notes.isEmpty then Nil
    else "## Unresolved" :: "" :: refinement.notes.map(n => s"- ${cell(n)}")
  (List(
    "# Graph refine (LLM)",
    "",
    s"${kept.size} of ${refinement.edges.size} proposed edge(s) merged into `graph.json` (kind prefixed `llm-`).",
    "Each carries the source statement that establishes it — verify before trusting a wave built on it.",
    "",
    "| From | To | Kind | Status | Evidence |",
    "| ---- | -- | ---- | ------ | -------- |",
  ) ++ rows ++ List("") ++ notes).mkString("\n") + "\n"

def triagePrompt(graph: SurveyGraph, inventory: String): String =
  s"""You are triaging a legacy estate for modernization. Below are its inventory and
     |dependency graph (regex-derived from the source, plus `llm-…` edges the graph-refine
     |step grounded in the source with evidence — trust them).
     |
     |Produce:
     |- "triage": for EVERY unit in the inventory, a disposition:
     |  - "rewrite": actively used business logic to modernize;
     |  - "retire": unreferenced/dead — candidate for decommissioning, with the evidence;
     |  - "wrap": keep on the legacy platform and front with an API (shared utilities other
     |    estates still call, or units out of this program's scope).
     |  Rationale in one sentence, grounded in the graph (degrees, callers, size).
     |- "waves": dependency-coherent migration slices for the REWRITE units: a wave's programs
     |  should depend only on already-migrated or same-wave units where possible; leaves and
     |  low-fan-in units first; name each wave (wave-1, wave-2, …) and give the ordering
     |  rationale.
     |- "notes": anything the graph could not resolve — dynamic CALL variables, cycles worth a
     |  human look. Empty if none.
     |
     |Inventory:
     |$inventory
     |
     |Graph (JSON):
     |${graph.toJson}""".stripMargin

def renderWavePlan(outcome: SurveyOutcome, graph: SurveyGraph, projection: Option[String]): String =
  val triageRows = outcome.triage
    .sortBy(_.name)
    .map(t => s"| ${t.name} | ${t.disposition} | ${t.rationale} |")
  val waveSecs   = outcome.waves.map { w =>
    (s"## Wave: ${w.name}" ::
      "" ::
      s"${w.rationale}" ::
      "" ::
      w.programs.map(p => s"- $p")).mkString("\n") + "\n"
  }
  val notes      =
    if outcome.notes.isEmpty then Nil
    else "## Needs a human look" :: "" :: outcome.notes.map(n => s"- $n") ::: List("")
  (List(
    "# Modernization wave plan",
    "",
    s"${graph.nodes.size} unit(s) surveyed, ${outcome.waves.size} wave(s) proposed.",
    "Scope extraction to one wave with `LLM4ZIO_WAVE=<name>` once this plan is approved.",
    "",
    "| Unit | Disposition | Rationale |",
    "| ---- | ----------- | --------- |",
  ) ++ triageRows ++ List("") ++ waveSecs ++ notes ++ projection.toList).mkString("\n") + "\n"

flow(
  args,
  coder = coderCfg,
  reasoning = Some(reasoningCfg),
  defaultPrompt = Some("Survey the legacy estate and propose a migration wave plan"),
):
  val packDir = workspace.resolve(sys.env.getOrElse("LLM4ZIO_PACK", "packs/cobol-springboot"))
  val modDir  = workDir.resolve(ModDir)
  val events  = summon[FlowEvents]

  for
    pack      <- stage("Pack")(Pack.load(packDir))
    _         <- ZIO.when(pack.survey.isEmpty)(
                   ZIO.fail(FlowError.Aborted(
                     s"pack '${pack.name}' has no '## Survey:' sections — add the dependency-edge regexes " +
                       "(CALL/COPY/EXEC PGM…) the graph should be built from"
                   ))
                 )
    graph     <- stage("Graph") {
                   for
                     g <- Survey.graph(
                            workDir,
                            sources = pack.sources.getOrElse(""".*"""),
                            units = pack.coverage,
                            edges = pack.survey,
                          )
                     _ <- writeFile(modDir.resolve("inventory.md"), Survey.renderInventory(g))
                     _ <- writeFile(modDir.resolve("graph.json"), g.toJsonPretty)
                     _ <- events.publish(FlowEvent.Info(s"${g.nodes.size} unit(s), ${g.edges.size} edge(s)"))
                   yield g
                 }
    refined   <- stage("Graph refine") {
                   if sys.env.get("LLM4ZIO_GRAPH_REFINE").map(_.trim.toLowerCase).exists(Set("off", "0", "false"))
                   then
                     events
                       .publish(FlowEvent.Info("LLM4ZIO_GRAPH_REFINE=off — regex graph ships unrefined"))
                       .as(graph)
                   else
                     for
                       r     <- Context.withShrink("survey refine") { cap =>
                                  Context
                                    .capped("graph", refinePrompt(graph), cap)
                                    .flatMap { prompt =>
                                      reasoning
                                        .executeStructured[GraphRefinement](
                                          prompt,
                                          SchemaDerivation.derive[GraphRefinement],
                                        )
                                        .mapError(e => FlowError.Llm(e.message, Some(e)))
                                    }
                                }
                       merged = Survey.merge(graph, r.edges.map(e => SurveyEdge(e.from, e.to, e.kind)))
                       kept   = merged.edges.filter(_.kind.startsWith("llm-"))
                       _     <- writeFile(modDir.resolve("graph-refine.md"), renderRefine(r, kept))
                       _     <- ZIO.when(kept.nonEmpty)(
                                  writeFile(modDir.resolve("inventory.md"), Survey.renderInventory(merged)) *>
                                    writeFile(modDir.resolve("graph.json"), merged.toJsonPretty)
                                )
                       _     <- events.publish(FlowEvent.Info(
                                  s"${kept.size} regex-missed edge(s) merged (${r.edges.size} proposed by the LLM)"
                                ))
                     yield merged
                 }
    outcome   <- stage("Triage") {
                   // Survey's two prompts scale with UNIT COUNT, not file contents, so they are the least likely of
                   // the phases to trip the budget — cap and record only. Chunking the graph by connected component
                   // is deliberately deferred until the cap is observed to actually fire.
                   Context.withShrink("survey triage") { cap =>
                     Context
                       .capped("inventory", triagePrompt(refined, Survey.renderInventory(refined)), cap)
                       .flatMap { prompt =>
                         reasoning
                           .executeStructured[SurveyOutcome](prompt, SchemaDerivation.derive[SurveyOutcome])
                           .mapError(e => FlowError.Llm(e.message, Some(e)))
                       }
                   }
                 }
    projection <- stage("Projection") {
                    val bench = workspace.resolve("bench-results.jsonl")
                    ZIO
                      .attemptBlocking(Files.exists(bench))
                      .orDie
                      .flatMap {
                        case false =>
                          events
                            .publish(FlowEvent.Info(
                              "no bench-results.jsonl next to the scripts — wave plan ships without cost projection " +
                                "(run modernize-bench.sc to measure, then rerun the survey)"
                            ))
                            .as(None)
                        case true  =>
                          BenchReport.load(List(bench)).map { records =>
                            val first = outcome.waves.headOption.map(_.programs.size).getOrElse(0)
                            Some(
                              s"## Cost projection (wave-1, $first program(s), from measured runs)\n\n" +
                                BenchReport.render(records, projectPrograms = Some(first)) +
                                "\n\nOther waves: `scala-cli run bench-report.sc -- bench-results.jsonl --project <n>`.\n"
                            )
                          }
                      }
                  }
    planMd     = renderWavePlan(outcome, refined, projection)
    _         <- stage("Wave plan")(
                   writeFile(modDir.resolve("wave-plan.md"), ApprovalGate.withDraftMarker(planMd))
                 )
    _         <- stage("Commit")(
                   git.commitAll(s"modernize(${pack.name}): survey — ${refined.nodes.size} unit(s), " +
                     s"${outcome.waves.size} wave(s) proposed").unit
                 )
    _         <- events.publish(FlowEvent.Info(
                   s"review $ModDir/wave-plan.md, flip '- [x] Approved', then run modernize-extract.sc " +
                     "(LLM4ZIO_WAVE=<name> scopes it to one wave)"
                 ))
  yield s"units=${refined.nodes.size} waves=${outcome.waves.size}"
