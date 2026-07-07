package llm4zio.javaapi

import java.lang.Runnable
import java.nio.file.Path
import java.util.function.{ Consumer, Supplier }

import scala.jdk.CollectionConverters.{ ListHasAsScala, SeqHasAsJava }

import zio.{ Runtime, Unsafe, ZIO }

import llm4zio.eval.*
import llm4zio.flow.*
import llm4zio.runner.Ado

/** The Java-facing flow handle — the single object a Java-authored flow works through, replacing the `.sc` surface's
  * implicit `FlowContext` and bare-name accessors (`git`/`coder`/`stage`/…). Every method is synchronous and blocking:
  * it runs an effect on the flow's scoped [[Runtime]] via [[Bridge]], so events published along the way still stream to
  * the same listeners (terminal tree, cost tracker, trace recorder) that the runner subscribed once around the whole
  * flow.
  */
final class JavaFlow private[javaapi] (runtime: Runtime[Any], ctx: FlowContext):
  private val events = ctx.events

  // ── context accessors ────────────────────────────────────────────────────────

  /** The free-form prompt the flow was started with. */
  def userPrompt: String = ctx.userPrompt

  /** The repository the flow operates on. */
  def workDir: Path = ctx.workDir

  /** llm4zio's own home (the launch directory): where `.llm4zio/` bookkeeping and sidecar artifacts live. Coincides
    * with [[workDir]] unless the flow targets an external `--repo`.
    */
  def workspace: Path = ctx.workspace

  /** The resumable plan file for this flow's prompt, under its bookkeeping directory. */
  def defaultPlanPath: Path = Plan.defaultPath(ctx.userPrompt, Workspace.llm4zioDir(ctx.workspace, ctx.workDir))

  /** Version-control side effects. */
  def git(): JavaGit = new JavaGit(runtime, ctx.git)

  /** GitHub side effects via the `gh` CLI. */
  def gh(): JavaGh = new JavaGh(runtime, ctx.gh)

  /** Abort the flow with a message: publishes `Aborted` and throws [[Llm4zioException]] (category `Aborted`). The Java
    * counterpart of the `.sc` `fail(message)`.
    */
  def fail(message: String): Unit =
    given FlowEvents = events
    Bridge.runSync(runtime, llm4zio.flow.fail(message))

  /** Emit a progress note into the flow's event stream (the live tree, trace, …) — the Java counterpart of publishing
    * `FlowEvent.Info`.
    */
  def info(message: String): Unit =
    Bridge.runSync(runtime, events.publish(FlowEvent.Info(message)))

  // ── stage ────────────────────────────────────────────────────────────────────

  /** Run `body` as a named stage: emit `StageStarted`, run the body on this (blocking) thread, then emit
    * `StageCompleted` on success or `StageFailed` on a thrown exception, re-throwing it. The Java counterpart of the
    * `.sc` `stage(name)(effect)` combinator — same event protocol, blocking shape.
    */
  def stage[A](name: String, body: Supplier[A]): A =
    Unsafe.unsafe { implicit u =>
      Bridge.run(runtime, events.publish(FlowEvent.StageStarted(name)))
      try
        val result = body.get()
        Bridge.run(runtime, events.publish(FlowEvent.StageCompleted(name)))
        result
      catch
        case t: Throwable =>
          val detail = Option(t.getMessage).getOrElse(t.toString)
          Bridge.run(runtime, events.publish(FlowEvent.StageFailed(name, detail)))
          throw t // scalafix:ok DisableSyntax.throw
    }

  /** Void-bodied stage. A Java lambda whose body is a void call (e.g. `() -> flow.git().checkoutOrCreate(id)`) is a
    * `Runnable`, not a `Supplier`, so this overload is what such a stage resolves to.
    */
  def stage(name: String, body: Runnable): Unit =
    stage(name, (() => { body.run(); () }): Supplier[Unit])

  // ── chat ─────────────────────────────────────────────────────────────────────

  /** Start a fresh coder conversation seeded with `system`. */
  def startChat(system: String): JavaChat =
    new JavaChat(runtime, Bridge.runSync(runtime, Chat.start(ctx.coder, system = Option(system))))

  // ── plan ─────────────────────────────────────────────────────────────────────

  /** Load the plan at `path` if present, else create one by planning the flow's prompt over the reasoning connector.
    * Resumes a crashed run from the first incomplete task. The Java counterpart of
    * `PlanStore.recoverOrCreate(path)(Planner.from(reasoning, userPrompt))`.
    */
  def recoverOrCreatePlan(path: Path): Plan =
    Bridge.runSync(runtime, PlanStore.recoverOrCreate(path)(Planner.from(ctx.reasoning, ctx.userPrompt)))

  /** Delete the plan file at `path` (epic cleanup at the end of a run). A no-op if it isn't there. */
  def deletePlan(path: Path): Unit = Bridge.runSync(runtime, PlanStore.delete(path))

  /** Load the plan at `path` if present. */
  def loadPlan(path: Path): java.util.Optional[Plan] =
    Bridge.runSync(runtime, PlanStore.load(path)).fold(java.util.Optional.empty[Plan])(java.util.Optional.of)

  /** Persist `plan` to `path`. */
  def savePlan(path: Path, plan: Plan): Unit = Bridge.runSync(runtime, PlanStore.save(path, plan))

  /** Skeptically assess a request over the reasoning connector before planning: [[JavaAssessment.Proceed]] with a plan
    * to go ahead, or [[JavaAssessment.Blocked]] with a reason to stop. Switch on the result in Java.
    */
  def assessThenPlan(prompt: String): JavaAssessment =
    Bridge.runSync(runtime, Planner.assessThenPlan(ctx.reasoning, prompt)) match
      case Verdict.Proceed(plan) => JavaAssessment.Proceed(plan)
      case Verdict.Blocked(why)  => JavaAssessment.Blocked(why)

  /** Summarise a diff into a PR title + body over the reasoning connector. */
  def summarisePr(diff: String, context: String): PrSummary =
    Bridge.runSync(runtime, llm4zio.flow.summarisePr(ctx.reasoning, diff, Option(context)))

  /** Triage a bug report over the reasoning connector. Switch on the returned [[Triage]] in Java (`Triage.NotABug` /
    * `Triage.Untestable` / `Triage.Testable`).
    */
  def triage(title: String, body: String): Triage =
    Bridge.runSync(runtime, Planner.triage(ctx.reasoning, title, body))

  /** Plan `prompt` over the reasoning connector (no recovery — always a fresh plan). */
  def planFrom(prompt: String): Plan = Bridge.runSync(runtime, Planner.from(ctx.reasoning, prompt))

  /** Have the reasoning connector critique a draft plan and return an improved one. */
  def reviewedPlan(plan: Plan): Plan = Bridge.runSync(runtime, Planner.reviewed(ctx.reasoning, plan))

  /** Attach a codebase brief (gathered over the reasoning connector for `prompt`) to `plan`. */
  def briefedPlan(plan: Plan, prompt: String): Plan =
    Bridge.runSync(runtime, Planner.briefed(ctx.reasoning, plan, prompt))

  /** Write a one-off codebase brief for `prompt` over the reasoning connector (default instructions). */
  def brief(prompt: String): String = Bridge.runSync(runtime, Planner.brief(ctx.reasoning, prompt))

  /** As [[brief]], with explicit instructions — how the sdd/pipeline/reverse-engineer flows drive their spec, design,
    * and documentation phases.
    */
  def brief(prompt: String, instructions: String): String =
    Bridge.runSync(runtime, Planner.brief(ctx.reasoning, prompt, instructions))

  /** As [[planFrom]], with explicit planning instructions. */
  def planFrom(prompt: String, instructions: String): Plan =
    Bridge.runSync(runtime, Planner.from(ctx.reasoning, prompt, instructions))

  /** As [[recoverOrCreatePlan]], but planning with your own `create` (e.g. reviewed + briefed) when no plan file is on
    * disk. `create` runs on this (blocking) thread.
    */
  def recoverOrCreatePlan(path: Path, create: Supplier[Plan]): Plan =
    Bridge.runSync(
      runtime,
      PlanStore.recoverOrCreate(path)(ZIO.attemptBlocking(create.get()).mapError(Llm4zioException.toFlowError)),
    )

  // ── loops ────────────────────────────────────────────────────────────────────

  /** Run `plan` to completion one task at a time, persisting progress to `path` after each task (so a crashed run
    * resumes from the first incomplete task). Each task runs inside a stage; `perTask` is your Java body.
    */
  def implementTaskLoop(path: Path, plan: Plan, perTask: Consumer[Task]): Plan =
    given FlowEvents = events
    Bridge.runSync(
      runtime,
      llm4zio.flow.implementTaskLoop(path, plan) { task =>
        ZIO.attemptBlocking(perTask.accept(task)).mapError(Llm4zioException.toFlowError)
      },
    )

  /** Review the current diff with `reviewers` over the reasoning connector and have the coder `chat` fix what they
    * find, re-reading `diff` each round, until clean or the round cap. `diff` is a supplier so it re-reads live state.
    */
  def reviewAndFixLoop(reviewers: java.util.List[Reviewer], chat: JavaChat, taskTitle: String, diff: Supplier[String])
    : Unit = reviewAndFixLoop(reviewers, chat, taskTitle, diff, 0)

  /** As [[reviewAndFixLoop]], capping concurrent reviewer calls at `parallelism` (`1` serializes them — what a
    * rate-limited local/free-tier backend needs; `0` fans them all out).
    */
  def reviewAndFixLoop(
    reviewers: java.util.List[Reviewer],
    chat: JavaChat,
    taskTitle: String,
    diff: Supplier[String],
    parallelism: Int,
  ): Unit =
    given FlowEvents = events
    val rs           = reviewers.asScala.toList
    val _            = Bridge.runSync(
      runtime,
      llm4zio.flow.reviewAndFixLoop(
        rs,
        ctx.reasoning,
        chat.underlying,
        taskTitle,
        ZIO.attemptBlocking(diff.get()).mapError(Llm4zioException.toFlowError),
        parallelism = parallelism,
      ),
    )

  /** As [[reviewAndFixLoop]], with a lint/build gate run before each review round: if `lintCommand` (e.g.
    * `["mvn","-q","test"]`, run in the work dir) fails, its findings go straight to the fixer and the LLM reviewers are
    * skipped for the round — fix the build first.
    */
  def reviewAndFixLoop(
    reviewers: java.util.List[Reviewer],
    chat: JavaChat,
    taskTitle: String,
    diff: Supplier[String],
    lintCommand: java.util.List[String],
    parallelism: Int,
  ): Unit =
    given FlowEvents = events
    val rs           = reviewers.asScala.toList
    val gate         = llm4zio.flow.Reviewers.lintCommand(lintCommand.asScala.toList, ctx.workDir)
    val _            = Bridge.runSync(
      runtime,
      llm4zio.flow.reviewAndFixLoop(
        rs,
        ctx.reasoning,
        chat.underlying,
        taskTitle,
        ZIO.attemptBlocking(diff.get()).mapError(Llm4zioException.toFlowError),
        lint = Some(gate),
        parallelism = parallelism,
      ),
    )

  /** Run a lint/build command (in the work dir) as a standalone gate. `result.isClean()` says whether it passed — the
    * sdd flow's RED check (a new test must fail) and final verify both branch on this.
    */
  def lint(command: java.util.List[String]): ReviewResult =
    Bridge.runSync(runtime, llm4zio.flow.Reviewers.lintCommand(command.asScala.toList, ctx.workDir))

  // ── eval / judge ─────────────────────────────────────────────────────────────

  /** An LLM-as-a-Judge over the reasoning connector, scoring the given dimensions. Combine with [[Evals]] helpers and
    * run via [[evaluate]] / [[runSuite]].
    */
  def judge(dimensions: java.util.List[Dimension]): Evaluator[Sample] =
    Judge.of(ctx.reasoning, dimensions.asScala.toList)

  /** Run one evaluation, blocking until the scores are in. */
  def evaluate(evaluator: Evaluator[Sample], sample: Sample): EvalResult =
    Bridge.runSync(runtime, evaluator.evaluate(sample).mapError(e => FlowError.Llm(e.message, Some(e))))

  /** Score a whole suite of cases (each judged `repeats` times; spreads above `spreadThreshold` are flagged flaky). */
  def runSuite(
    evaluator: Evaluator[Sample],
    cases: java.util.List[EvalCase[Sample]],
    repeats: Int,
    spreadThreshold: Int,
  ): SuiteReport =
    Bridge.runSync(
      runtime,
      EvalSuite
        .run(evaluator, cases.asScala.toList, repeats, spreadThreshold)
        .mapError(e => FlowError.Llm(e.message, Some(e))),
    )

  // ── azure devops ─────────────────────────────────────────────────────────────

  /** Provide an Azure DevOps tool to `body` for its duration (config from the ADO pipeline env / `LLM4ZIO_ADO_*`). The
    * Java counterpart of `Ado.withTool() { ado => … }`.
    */
  def withAdo(body: Consumer[JavaAdo]): Unit =
    val _ = Bridge.runSync(
      runtime,
      Ado.withTool() { ado =>
        ZIO.attemptBlocking(body.accept(new JavaAdo(runtime, ado))).mapError(Llm4zioException.toFlowError)
      },
    )

  // ── reverse-engineering ──────────────────────────────────────────────────────

  /** Infer the significant architecture decisions from `input` (e.g. architecture + domain docs) over the reasoning
    * connector, as structured [[Adrs.Adr]] records. Render with [[Adrs.render]].
    */
  def adrs(input: String): java.util.List[Adrs.Adr] =
    Bridge
      .runSync(
        runtime,
        ctx.reasoning
          .executeStructured[Adrs.AdrSet](input, Adrs.schema)
          .mapError(e => FlowError.Llm(e.message, Some(e))),
      )
      .adrs
      .asJava
