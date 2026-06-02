package llm4zio.runner

import zio.{ Console, IO }

import llm4zio.flow.{ FlowError, Interaction }

/** An [[Interaction]] that asks the user on the terminal (stdin/stdout) — the ZIO-native stand-in for orca's `ask_user`
  * MCP tool.
  */
object TerminalInteraction:
  val live: Interaction = new Interaction:
    def ask(question: String): IO[FlowError, String] =
      (Console.printLine(s"❓ $question") *> Console.readLine)
        .mapError(e => FlowError.Process("stdin", Option(e.getMessage).getOrElse(e.toString)))
