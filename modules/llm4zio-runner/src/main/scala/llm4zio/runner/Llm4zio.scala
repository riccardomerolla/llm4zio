package llm4zio.runner

import java.nio.file.Path

import zio.*
import zio.http.Client

import llm4zio.core.{ CliConnectorConfig, ConnectorConfig }
import llm4zio.flow.{ CostTracker, FlowContext, FlowError, FlowEvents, UsageLimitPolicy, withUsageLimitRetry }
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
                     val policy = if usageLimit.enabled then usageLimit else UsageWaitEnv.parse(sys.env.get("LLM4ZIO_USAGE_WAIT"))
                     for
                       surface   <- if palette.enabled then TerminalSurface.live(palette) else TerminalSurface.plain
                       bundle    <- DefaultFlowContext.build(reasoning, coder, workDir, reviewers, policy)
                       (ctx, hub) = bundle
                       tracker   <- CostTracker.make
                       // Two fire-and-forget subscribers on the bounded event hub. Both drain fast
                       // (terminal write / map update); the hub back-pressures the producer if a
                       // subscriber stalls, which paces output rather than dropping events.
                       _         <- TerminalListener.consumeTo(hub, palette, surface)
                       _         <- tracker.consume(hub)
                       _         <- {
                                      given FlowEvents = hub
                                      withUsageLimitRetry(policy)(
                                        body(ctx).mapError {
                                          case fe: FlowError => fe
                                          case other         => FlowError.Llm(other.toString)
                                        }
                                      ).unit
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
