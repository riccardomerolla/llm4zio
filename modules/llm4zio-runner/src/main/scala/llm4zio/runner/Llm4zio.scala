package llm4zio.runner

import java.nio.file.Path

import zio.*
import zio.http.Client

import llm4zio.core.{ CliConnectorConfig, ConnectorConfig }
import llm4zio.flow.FlowContext
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
    ZIO
      .scoped {
        DefaultFlowContext.build(reasoning, coder, workDir, reviewers).flatMap {
          case (ctx, hub) =>
            Palette.auto.flatMap(palette => TerminalListener.consume(hub, palette)) *> body(ctx).unit
        }
      }
      .provide(Client.default, HttpClient.live)
      .mapError {
        case t: Throwable => t
        case other        => new RuntimeException(other.toString)
      }
