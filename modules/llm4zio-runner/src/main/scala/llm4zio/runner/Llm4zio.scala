package llm4zio.runner

import java.nio.file.Path

import zio.*
import zio.http.Client

import llm4zio.core.{ CliConnectorConfig, ConnectorConfig }
import llm4zio.flow.{ CostTracker, FlowContext }
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
                     for
                       bundle    <- DefaultFlowContext.build(reasoning, coder, workDir, reviewers)
                       (ctx, hub) = bundle
                       tracker   <- CostTracker.make
                       _         <- TerminalListener.consume(hub, palette)
                       _         <- tracker.consume(hub)
                       _         <- body(ctx).unit
                                      .ensuring(tracker.summary.flatMap(s => Console.printLine("\n" + s).orDie))
                     yield ()
                   }
                   .provideSomeLayer[HttpClient & Client](RunnerLog.fileOnly(logPath))
    yield ())
      .provide(Client.default, HttpClient.live)
      .mapError {
        case t: Throwable => t
        case other        => new RuntimeException(other.toString)
      }
