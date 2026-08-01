package llm4zio.modernize

object VerifyFlow:

  // Modernize flows are full-power library flows (branch/commit/push/PR); the mint is the entry-point grant.
  // Capability-parametric variants can narrow this later — every bypass is greppable via Caps.grantAll.
  private[modernize] given llm4zio.flow.Caps.All = llm4zio.flow.Caps.grantAll

  /** Legacy-modernization phase 4 of 5: prove the implementation equivalent to its specs — per rule, with a
    * deterministic diff, and an auditor-facing report.
    *
    * modernize-extract.sc → (human approves) → modernize-seed.sc → modernize-implement.sc → modernize-verify.sc →
    * modernize-review.sc
    *
    * Runs ROOTED AT THE TARGET REPO (`--repo <target>`) on the implementation branch, behind the clean-room wall (no
    * legacy source may be present — verification is against specs and vectors, never against the original code):
    *
    *   1. VECTORS, generated-first: for each spec'd program with no vector file yet, `reasoning` generates equivalence
    *      vectors from the spec + BDD scenarios (boundary values included) into
    *      `docs/modernization/vectors/<PROGRAM>.jsonl` — resumable per program like extraction; delete a file to
    *      regenerate it. Captured-tier vectors (recorded legacy runs, `"tier":"captured"`) can be dropped into the same
    *      directory by the bank's own capture tooling — same JSONL format.
    *   2. REPLAY, transport-blind: every vector is piped (JSON on stdin) into the pack's `replay:` command, which
    *      drives the target implementation and prints the resulting observations as a JSON array (the
    *      `Equiv.Observation` encoding: `{"type":"record",...}` / `{"type":"db",...}`).
    *   3. DIFF, deterministic: `Equiv.diff` compares expected vs actual observations under the pack's `## Equivalence`
    *      policy (ordering, ignored fields). No LLM decides equivalence.
    *   4. REPORT, per rule: `docs/modernization/equivalence.md` — every rule from the spec pack's rules.txt with
    *      generated/captured columns kept separate (never summed) and unexercised rules listed loudly. The report's
    *      hash is appended to provenance.json.
    *   5. TRIAGE, bounded: mismatches are distilled by `reasoning` into fix specs under `docs/specs/fixes/` + appended
    *      plan tasks — rerun modernize-implement.sc to pick them up. The flow then HALTS non-green so a pipeline can
    *      gate on it.
    *
    * Generated vectors prove SPEC CONFORMANCE; captured vectors prove BEHAVIOURAL EQUIVALENCE. The report keeps the two
    * claims separate — do not let anyone sum them.
    *
    * Run: scala-cli run modernize-verify.sc -- --repo ~/services/meridian-transfers
    */

  import java.nio.charset.StandardCharsets
  import java.nio.file.{ Files, Path }

  import scala.jdk.CollectionConverters.*

  import zio.json.*
  import zio.json.ast.Json
  import zio.{ IO, ZIO }

  import llm4zio.core.SchemaDerivation
  import llm4zio.flow.*
  import llm4zio.runner.*

  val ProModel: String = "gemini-2.5-pro" // point these at whatever your `gemini` CLI offers
  val ModDir: String   = "docs/modernization"

  val (coderCfg, reasoningCfg) =
    Env.get("LLM4ZIO_CODER").map(_.trim.toLowerCase).filter(_.nonEmpty) match
      case None | Some("gemini") => (gemini, gemini.withModel(ProModel).copy(readOnly = true))
      case Some(_)               =>
        val agent = Connectors.coderFromEnv()
        (agent, agent.copy(readOnly = true))

  /** The model-facing vector shape: flat maps only, so structured output stays schema-simple. `kind` is "record" (an
    * emitted record: `channel` names it, `fields` carries it) or "db" (a mutation: `channel` is the table, `op`
    * insert/update/delete, `key` addresses the row, `fields` is what's written).
    */
  case class GenObservation(
    kind: String,
    channel: String,
    op: String,
    key: Map[String, String],
    fields: Map[String, String],
  ) derives JsonCodec
  case class GenVector(id: String, rules: List[String], inputs: Map[String, String], observations: List[GenObservation])
    derives JsonCodec
  case class GenVectors(vectors: List[GenVector]) derives JsonCodec

  /** Verify's triage outcome: only fixes — improvements and lessons belong to modernize-review.sc. */
  case class FixSpec(title: String, spec: String, taskTitle: String, taskDescription: String) derives JsonCodec
  case class VerifyOutcome(fixes: List[FixSpec]) derives JsonCodec

  /** The enforced clean-room wall: refuse to run with legacy source inside the target workspace. */
  def assertWall(pack: Pack, root: Path, events: FlowEvents): IO[FlowError, Unit] =
    pack.sources match
      case None        => events.publish(FlowEvent.Info("pack has no sources regex — wall check skipped"))
      case Some(regex) =>
        Wall.check(root, regex).flatMap {
          case Wall.Result.Clean           =>
            events.publish(FlowEvent.Info("clean-room wall: no legacy source in the target workspace"))
          case Wall.Result.Breached(paths) =>
            val shown = paths.take(10).mkString(", ")
            val more  = if paths.size > 10 then s" (+${paths.size - 10} more)" else ""
            ZIO.fail(FlowError.Aborted(
              s"clean-room wall breached — legacy source inside the target workspace: $shown$more. " +
                "Equivalence is proven against specs and vectors, never against the original code."
            ))
        }

  def writeFile(path: Path, content: String): IO[FlowError, Unit] =
    ZIO
      .attemptBlocking {
        Option(path.getParent).foreach(Files.createDirectories(_))
        Files.write(path, content.getBytes(StandardCharsets.UTF_8))
        ()
      }
      .mapError(e => FlowError.Persistence(s"failed to write $path", Some(e)))

  def readFileOr(path: Path, fallback: String): IO[FlowError, String] =
    ZIO
      .attemptBlocking(if Files.exists(path) then Files.readString(path) else fallback)
      .mapError(e => FlowError.Persistence(s"failed to read $path", Some(e)))

  /** The spec'd programs: top-level `<NAME>.md` files under the pack's specs dir, indexes aside. */
  def specPrograms(specsDir: Path): IO[FlowError, List[String]] =
    ZIO
      .attemptBlocking {
        if !Files.isDirectory(specsDir) then Nil
        else
          val stream = Files.list(specsDir)
          try
            stream
              .iterator()
              .asScala
              .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".md"))
              .map(_.getFileName.toString.stripSuffix(".md"))
              .filterNot(Set("traceability", "mapping", "README"))
              .toList
              .sorted
          finally stream.close()
      }
      .mapError(e => FlowError.Persistence(s"failed to list specs under $specsDir", Some(e)))

  /** The program's .feature file under the pack's features dir, matched by case-insensitive stem. */
  def featureFor(featuresDir: Path, program: String): IO[FlowError, String] =
    ZIO
      .attemptBlocking {
        if !Files.isDirectory(featuresDir) then ""
        else
          val stream = Files.walk(featuresDir)
          try
            stream
              .iterator()
              .asScala
              .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".feature"))
              .find(_.getFileName.toString.stripSuffix(".feature").equalsIgnoreCase(program))
              .map(Files.readString)
              .getOrElse("")
          finally stream.close()
      }
      .mapError(e => FlowError.Persistence(s"failed to read features under $featuresDir", Some(e)))

  def slug(title: String): String =
    title.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-").take(60)

  def generatePrompt(pack: Pack, program: String, spec: String, feature: String, rules: List[String]): String =
    val packBrief = pack.prompt("vectors").getOrElse("")
    val ruleSlice = rules.mkString("\n")
    s"""$packBrief
       |
       |Generate equivalence test vectors for the program $program from its behavioural spec and BDD
       |scenarios below. Vectors are the generated tier: they prove spec conformance, so cover every
       |rule the spec states — normal paths, boundary values (thresholds exactly at/over/under), and
       |error paths (rejects, insufficient funds, validation order).
       |
       |For each vector:
       |- "id": short kebab-case name describing the case.
       |- "rules": the source units it exercises, chosen ONLY from this list (the ones this program's
       |  spec cites): use the exact names.
       |$ruleSlice
       |- "inputs": a flat string map — the fields the replay harness needs to drive one execution
       |  (amounts as plain decimal strings, dates ISO, codes verbatim from the spec).
       |- "observations": the EXACT outcomes the spec promises, in order. kind "record" for emitted
       |  records ("channel" names the output, "fields" carries values, "op" and "key" empty); kind
       |  "db" for database mutations ("channel" = table, "op" = insert/update/delete, "key" addresses
       |  the row, "fields" = the values written). Every amount, status, and reason code must come
       |  from the spec — never invent values.
       |
       |Aim for 5–8 vectors: the happy path, each boundary, each reject.
       |
       |Spec:
       |$spec
       |
       |Scenarios:
       |$feature""".stripMargin

  def toVector(program: String, g: GenVector): Either[String, EquivVector] =
    g.observations
      .foldLeft[Either[String, List[Equiv.Observation]]](Right(Nil)) { (acc, o) =>
        acc.flatMap { done =>
          o.kind.toLowerCase match
            case "record" => Right(done :+ Equiv.Observation.Record(o.channel, o.fields))
            case "db"     => Right(done :+ Equiv.Observation.DbMutation(o.channel, o.op, o.key, o.fields))
            case other    => Left(s"vector ${g.id}: unknown observation kind '$other' (expected record|db)")
        }
      }
      .map { obs =>
        EquivVector(
          schema = 1,
          program = program,
          id = g.id,
          tier = Equiv.Tier.Generated,
          rules = g.rules,
          inputs = Json.Obj(g.inputs.toList.map((k, v) => k -> Json.Str(v))*),
          observations = obs,
        )
      }

  /** One triage call per program (not one for the whole estate): mismatches already carry `v.vector.program`, so this
    * groups rather than redesigns. Carrying only `program`'s own spec — instead of concatenating every spec in the
    * estate — is what keeps this prompt inside a real estate's context budget.
    */
  def triagePrompt(pack: Pack, program: String, failing: List[VectorVerdict], specText: String): String =
    val details = failing
      .map { v =>
        val ms = v.mismatches
          .map {
            case Equiv.Mismatch.FieldDiff(at, field, e, a) => s"  - $at: $field expected $e, actual $a"
            case Equiv.Mismatch.Missing(o)                 => s"  - missing: $o"
            case Equiv.Mismatch.Unexpected(o)              => s"  - unexpected: $o"
          }
          .mkString("\n")
        s"- ${v.vector.id} (rules: ${v.vector.rules.mkString(", ")})\n$ms"
      }
      .mkString("\n")
    s"""${pack.prompt("review").getOrElse("")}
       |
       |The equivalence harness replayed test vectors for the program $program against the
       |implementation and found the mismatches below. For each DISTINCT root cause produce one fix:
       |a short spec document (Markdown: the rule violated, expected vs actual behaviour, the failing
       |vector ids) and a plan task (title + description naming the spec rules). Group mismatches
       |sharing a cause. If a mismatch reveals a wrong or ambiguous SPEC rather than wrong code, say
       |so explicitly in that fix document — spec gaps go back to extraction, not to the coder.
       |
       |Mismatches:
       |$details
       |
       |Spec for $program:
       |$specText""".stripMargin

  def run(args: Array[String]): Unit =
    flow(
      args,
      coder = coderCfg,
      reasoning = Some(reasoningCfg),
      defaultPrompt = Some("Prove the implementation equivalent to its spec pack"),
    ):
      val packDir    = workspace.resolve(Env.getOrElse("LLM4ZIO_PACK", "packs/cobol-springboot"))
      val planFile   = workDir.resolve(ModDir).resolve("plan.md")
      val vectorsDir = workDir.resolve(ModDir).resolve("vectors")
      val events     = summon[FlowEvents]

      for
        pack      <- stage("Pack")(Pack.load(packDir))
        _         <- stage("Wall")(assertWall(pack, workDir, events))
        specsDir   = workDir.resolve(pack.specsDir)
        rulesTxt  <- readFileOr(specsDir.resolve("rules.txt"), "")
        universe   = rulesTxt.linesIterator.map(_.strip).filter(_.nonEmpty).toList
        _         <- ZIO.when(universe.isEmpty)(
                       events.publish(FlowEvent.Info(
                         "no rules.txt in the spec pack (extracted before 3.23.0?) — the report will use the " +
                           "vectors' own rules and cannot flag unexercised ones"
                       ))
                     )
        programs  <- stage("Programs")(specPrograms(specsDir))
        _         <- ZIO.when(programs.isEmpty)(
                       ZIO.fail(FlowError.Aborted(s"no specs under $specsDir — run modernize-seed.sc first"))
                     )
        _         <- stage("Vectors") { // generated-first, resumable per program: delete a file to regenerate
                       ZIO.foreachDiscard(programs) { program =>
                         val file = vectorsDir.resolve(s"$program.jsonl")
                         ZIO
                           .attemptBlocking(Files.exists(file))
                           .orDie
                           .flatMap {
                             case true  => events.publish(FlowEvent.Info(s"vectors exist for $program — skipping"))
                             case false =>
                               for
                                 spec    <- readFileOr(specsDir.resolve(s"$program.md"), "")
                                 feature <- featureFor(workDir.resolve(pack.featuresDir), program)
                                 // `universe` is every rule in the estate's rules.txt, sent once per program, so
                                 // this is the largest prompt the phase builds — budget it like the rest.
                                 gen     <- Context.withShrink(s"vectors[$program]") { cap =>
                                              Context
                                                .capped(
                                                  s"vectors[$program]",
                                                  generatePrompt(pack, program, spec, feature, universe),
                                                  cap,
                                                )
                                                .flatMap { prompt =>
                                                  reasoning
                                                    .executeStructured[GenVectors](
                                                      prompt,
                                                      SchemaDerivation.derive[GenVectors],
                                                    )
                                                    .mapError(e => FlowError.Llm(e.message, Some(e)))
                                                }
                                            }
                                 vectors <- ZIO.foreach(gen.vectors) { g =>
                                              ZIO.fromEither(toVector(program, g)).mapError(FlowError.PlanParse(_))
                                            }
                                 _       <- ZIO.when(vectors.isEmpty)(
                                              ZIO.fail(FlowError.Aborted(s"generator produced no vectors for $program"))
                                            )
                                 _       <- VectorStore.write(file, vectors)
                                 _       <- events.publish(FlowEvent.Info(s"${vectors.size} vector(s) generated for $program"))
                               yield ()
                           }
                       }
                     }
        replayCmd <- ZIO
                       .fromOption(pack.replay)
                       .orElseFail(FlowError.Aborted(
                         s"pack '${pack.name}' has no replay: command — add one (reads a vector JSON on stdin, " +
                           "prints the resulting observations as a JSON array on stdout)"
                       ))
        verdicts  <- stage("Replay") {
                       for
                         files   <- SpecChecks.matchingFiles(vectorsDir, """.*\.jsonl""").orElseSucceed(Nil)
                         vectors <-
                           ZIO.foreach(files.sorted)(f => VectorStore.read(vectorsDir.resolve(f))).map(_.flatten)
                         _       <- ZIO.when(vectors.isEmpty)(
                                      ZIO.fail(FlowError.Aborted(s"no vectors under $vectorsDir — nothing to replay"))
                                    )
                         result  <- ZIO.foreach(vectors) { v =>
                                      Equiv.replayVector(replayCmd, workDir, v).flatMap {
                                        case Equiv.Replayed.Crashed(code, problem) =>
                                          events
                                            .publish(FlowEvent.Info(
                                              s"replay failed for ${v.program}/${v.id} (exit $code): ${problem.take(300)}"
                                            ))
                                            .as(VectorVerdict(v, v.observations.map(Equiv.Mismatch.Missing(_))))
                                        case Equiv.Replayed.Observed(actual)       =>
                                          ZIO.succeed(VectorVerdict(
                                            v,
                                            Equiv.diff(v.observations, actual, pack.equivalence),
                                          ))
                                      }
                                    }
                       yield result
                     }
        allRules   = if universe.nonEmpty then universe else verdicts.flatMap(_.vector.rules).distinct.sorted
        failing    = verdicts.filterNot(_.passed)
        _         <- stage("Report") {
                       writeFile(workDir.resolve(ModDir).resolve("equivalence.md"), EquivReport.render(verdicts, allRules))
                     }
        _         <- ZIO.when(failing.nonEmpty)(stage("Triage") {
                       // One triage call PER PROGRAM, each carrying only that program's spec — not the whole
                       // estate's specs in one prompt, which is what blows a real estate's context budget.
                       val byProgram = failing.groupBy(_.vector.program).toList.sortBy(_._1)
                       for
                         outcomes <- ZIO.foreach(byProgram) { (program, vs) =>
                                       for
                                         spec    <- readFileOr(specsDir.resolve(s"$program.md"), "")
                                         // Cap the WHOLE rendered prompt, not just the spec: the mismatch detail
                                         // block is unbounded too (a program with a large captured-vector set),
                                         // and shrinking one ingredient while another grows leaves the ladder
                                         // failing all three rungs and then advising a knob that cannot help.
                                         outcome <- Context.withShrink(s"triage[$program]") { cap =>
                                                      Context
                                                        .capped(s"triage[$program]", triagePrompt(pack, program, vs, spec), cap)
                                                        .flatMap { prompt =>
                                                          reasoning
                                                            .executeStructured[VerifyOutcome](
                                                              prompt,
                                                              SchemaDerivation.derive[VerifyOutcome],
                                                            )
                                                            .mapError(e => FlowError.Llm(e.message, Some(e)))
                                                        }
                                                    }
                                       yield outcome
                                     }
                         allFixes  = outcomes.flatMap(_.fixes)
                         _        <- ZIO.foreachDiscard(allFixes) { f =>
                                       writeFile(
                                         specsDir.resolve("fixes").resolve(s"fix-${slug(f.title)}.md"),
                                         s"# ${f.title}\n\n${f.spec}\n",
                                       )
                                     }
                         _        <- ZIO.when(allFixes.nonEmpty) {
                                       PlanStore
                                         .load(planFile)
                                         .someOrFail(FlowError.Aborted(s"no plan at $planFile — run modernize-seed.sc first"))
                                         .flatMap { plan =>
                                           val increment = allFixes.map(f => Task(f.taskTitle, f.taskDescription))
                                           PlanStore.save(planFile, plan.copy(tasks = plan.tasks ++ increment)) *>
                                             events.publish(FlowEvent.Info(
                                               s"${increment.size} fix task(s) appended — rerun modernize-implement.sc"
                                             ))
                                         }
                                     }
                       yield ()
                     })
        _         <- stage("Provenance") {
                       val manifest = workDir.resolve(ModDir).resolve("provenance.json")
                       ZIO
                         .attemptBlocking(Files.exists(manifest))
                         .orDie
                         .flatMap {
                           case false => events.publish(FlowEvent.Info("no provenance.json — seeded before 3.23.0; skipping"))
                           case true  =>
                             for
                               h  <- Provenance.hashFiles(workDir, List(s"$ModDir/equivalence.md"))
                               ts <- Context.truncations
                               _  <- Provenance.extend(manifest)(p =>
                                       p.copy(
                                         equivalenceReport = h.values.headOption,
                                         contextTruncations = p.contextTruncations ++ ts.map(_.render).toList,
                                       )
                                     )
                             yield ()
                         }
                     }
        gCount     = verdicts.count(_.vector.tier == Equiv.Tier.Generated)
        cCount     = verdicts.count(_.vector.tier == Equiv.Tier.Captured)
        summary    = s"${verdicts.count(_.passed)}/${verdicts.size} vectors green ($gCount generated, $cCount captured)"
        _         <- stage("Commit")(
                       git.commitAll(s"modernize(${pack.name}): verify — $summary" +
                         (if failing.nonEmpty then s"; ${failing.size} failing triaged" else "")).unit
                     )
        _         <- ZIO.when(failing.nonEmpty)(
                       ZIO.fail(FlowError.Aborted(
                         s"equivalence not proven: ${failing.size} failing vector(s) — see $ModDir/equivalence.md; " +
                           "fix specs filed, rerun modernize-implement.sc"
                       ))
                     )
      yield summary
