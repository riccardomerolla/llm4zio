package eval.control

import java.util.UUID

import zio.*

import eval.entity.*

/** Executes an eval dataset against a pluggable executor and returns a complete [[EvalRun]] record.
  *
  * The executor is the only integration point — it takes a prompt and returns (actualOutput, exitCode). Callers wire
  * this to whatever underlying runtime they want (local CLI subprocess, HTTP gateway, in-process LLM, test stub).
  */
object EvalRunner:

  type Executor = String => Task[(String, Int)]

  def run(
    datasetPath: String,
    agentName: String,
    cases: List[EvalCase],
    executor: Executor,
    scorer: EvalScorer = EvalScorer.substringCaseInsensitive,
    onProgress: (Int, Int, EvalCaseResult) => UIO[Unit] = (_, _, _) => ZIO.unit,
  ): UIO[EvalRun] =
    for
      runId     <- ZIO.succeed(UUID.randomUUID().toString)
      startedAt <- Clock.instant
      results   <- ZIO.foreach(cases.zipWithIndex) { case (c, idx) =>
                     runOne(c, executor, scorer).flatMap { r =>
                       onProgress(idx + 1, cases.size, r).as(r)
                     }
                   }
      finished  <- Clock.instant
    yield EvalRun(
      runId = runId,
      datasetPath = datasetPath,
      agentName = agentName,
      startedAt = startedAt,
      finishedAt = finished,
      results = results,
      summary = EvalSummary.from(results),
    )

  private def runOne(
    c: EvalCase,
    executor: Executor,
    scorer: EvalScorer,
  ): UIO[EvalCaseResult] =
    for
      start <- Clock.nanoTime
      out   <- executor(c.prompt).either
      end   <- Clock.nanoTime
      durationMs = (end - start) / 1_000_000L
      result = out match
                 case Right((actual, code)) =>
                   val verdict =
                     if code != 0 then EvalVerdict.Fail
                     else scorer.score(c.expected, actual)
                   EvalCaseResult(
                     prompt = c.prompt,
                     expected = c.expected,
                     actual = actual,
                     verdict = verdict,
                     exitCode = code,
                     durationMs = durationMs,
                     error = None,
                   )
                 case Left(err)             =>
                   EvalCaseResult(
                     prompt = c.prompt,
                     expected = c.expected,
                     actual = "",
                     verdict = EvalVerdict.Fail,
                     exitCode = -1,
                     durationMs = durationMs,
                     error = Some(err.getMessage),
                   )
    yield result
