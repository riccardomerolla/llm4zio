package llm4zio.runner

import java.nio.file.Path

import zio.*
import zio.http.Client

import llm4zio.core.{ CliConnectorConfig, ConnectorConfig }
import llm4zio.flow.*
import llm4zio.providers.HttpClient

/** Entry point for scala-cli flow scripts. Builds a real [[FlowContext]], streams progress to the terminal, runs the
  * flow body, and provides the zio-http client + HttpClient layers — so a `.sc` reads top-to-bottom:
  *
  * {{{
  * object Main extends zio.ZIOAppDefault:
  *   def run = Llm4zio.run(os.pwd, reasoning, coder) { ctx =>
  *     // ... a flow over ctx ...
  *   }
  * }}}
  */
object Llm4zio:

  /** Best-effort human-readable reason from a failed flow's cause, for the final ✖ banner. */
  private def failMessage(cause: Cause[FlowError]): String =
    cause.failureOption
      .map(_.message)
      .orElse(cause.dieOption.map(_.getMessage))
      .getOrElse(if cause.isInterrupted then "interrupted" else "unknown error")

  def run(
    workDir: Path,
    reasoning: ConnectorConfig,
    coder: CliConnectorConfig,
    reviewers: List[ConnectorConfig] = Nil,
    usageLimit: UsageLimitPolicy = UsageLimitPolicy.off,
  )(
    body: FlowContext => ZIO[Any, Any, Any]
  ): ZIO[Any, Throwable, Unit] =
    (for
      logPath <- RunnerLog.newLogFile
      palette <- Palette.auto
      _       <- Console.printLine(Banner.line(Banner.version, logPath)).orDie
      _       <- Console.printLine("").orDie
      _       <- ZIO
                   .scoped {
                     val policy =
                       if usageLimit.enabled then usageLimit else UsageWaitEnv.parse(sys.env.get("LLM4ZIO_USAGE_WAIT"))
                     for
                       base      <- if palette.enabled then TerminalSurface.live(palette) else TerminalSurface.plain
                       // Tee every rendered tree line into the log file too, so the log is a complete record.
                       surface    = TerminalSurface.teeingToLog(base)
                       retries    = RetryEnv.parse(sys.env.get("LLM4ZIO_RETRIES"))
                       bundle    <- DefaultFlowContext.build(reasoning, coder, workDir, reviewers, policy, retries)
                       (ctx, hub) = bundle
                       tracker   <- CostTracker.make
                       // Two fire-and-forget subscribers on the bounded event hub. Both drain fast
                       // (terminal write / map update); the hub back-pressures the producer if a
                       // subscriber stalls, which paces output rather than dropping events.
                       consumed  <- TerminalListener.consumeTo(hub, palette, surface)
                       _         <- tracker.consume(hub)
                       _         <- {
                         given FlowEvents = hub
                         withUsageLimitRetry(policy)(
                           body(ctx).mapError {
                             case fe: FlowError => fe
                             case other         => FlowError.Llm(other.toString)
                           }
                         ).unit
                           // On exit, first drain the hub so trailing events (notably a final StageFailed) render
                           // rather than being interrupted away; on failure, follow with an authoritative ✖ banner
                           // written straight to the surface — so a failed run can never look like a clean finish.
                           .onExit {
                             case Exit.Failure(cause) =>
                               TerminalListener.awaitDrained(hub, consumed, 3.seconds) *>
                                 surface.log("\n" + palette.fail(s"flow failed: ${failMessage(cause)}"))
                             case Exit.Success(_)     =>
                               TerminalListener.awaitDrained(hub, consumed, 3.seconds)
                           }
                           .ensuring(tracker.summary.flatMap(s => surface.log("\n" + s)))
                       }
                     yield ()
                   }
                   .provideSomeLayer[HttpClient & Client](RunnerLog.fileOnly(logPath))
    yield ())
      .provide(Client.default, HttpClient.live)
      .mapError {
        case t: Throwable => t
        case other        => new RuntimeException(other.toString)
      }
