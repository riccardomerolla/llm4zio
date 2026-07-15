//> using dep "io.github.riccardomerolla::llm4zio-runner:3.18.0"
//> using scala "3.8.3"
//> using jvm 21

/** Aggregate `bench-results.jsonl` lines (from any machines) into a markdown benchmark comparison.
  * Pure Scala, no LLM — the same inputs always render the same report.
  *
  *   scala-cli run bench-report.sc -- [files-or-dirs...] [--project N] [--out benchmark.md]
  *
  * Defaults: input `./bench-results.jsonl`, output `./benchmark.md`. Runs are sectioned by
  * (pack, fixture fingerprint) so different tasks are never merged; within a section the best and
  * worst provider per metric are crowned (✅ / ⚠️) with the gap, judged quality scores are marked
  * cross-comparable only when every run had the same examiner, and `--project N` adds the linear
  * per-program extrapolation to an N-program estate. Time metrics are compared across machines
  * as-is (machines are footnoted) — read durations with that in mind.
  *
  * Read-only, no LLM seats, no git.
  */

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*

import llm4zio.flow.BenchReport

def parseArgs(rest: List[String], inputs: List[String], project: Option[Int], out: String): (List[String], Option[Int], String) =
  rest match
    case "--project" :: n :: tail => parseArgs(tail, inputs, n.toIntOption, out)
    case "--out" :: path :: tail  => parseArgs(tail, inputs, project, path)
    case path :: tail             => parseArgs(tail, inputs :+ path, project, out)
    case Nil                      => (inputs, project, out)

val (inputs, project, out) = parseArgs(args.toList, Nil, None, "benchmark.md")
val paths                  = (if inputs.isEmpty then List("bench-results.jsonl") else inputs).map(Path.of(_))

val program =
  for
    records <- BenchReport.load(paths)
    _       <- ZIO.when(records.isEmpty)(
                 Console.printLine(s"no benchmark records found under: ${paths.mkString(", ")} — run modernize-bench.sc first")
               )
    _       <- ZIO.when(records.nonEmpty) {
                 val markdown = BenchReport.render(records, project)
                 ZIO.attemptBlocking(Files.write(Path.of(out), markdown.getBytes(StandardCharsets.UTF_8))) *>
                   Console.printLine(
                     s"$out — ${records.size} run(s), providers: ${records.map(_.provider).distinct.sorted.mkString(", ")}" +
                       project.fold("")(n => s", projected @ $n programs")
                   )
               }
  yield ()

Unsafe.unsafe { implicit u =>
  Runtime.default.unsafe.run(program.catchAll(e => Console.printLine(s"bench report failed: $e").orDie)).getOrThrow()
}
