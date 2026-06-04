package llm4zio.flow

import zio.json.ast.Json
import zio.{ IO, ZIO }

/** The verdict on a single tool call requested by a coding agent. */
enum ApprovalDecision:
  case Allow
  case Deny(reason: String)

/** Decides whether a CLI coding agent may run a given tool call. Wired to claude via `--permission-prompt-tool`: the
  * agent asks before each non-allowlisted tool use, and the [[McpServer.approvalTool]] turns this decision into the
  * `allow`/`deny` verdict claude expects.
  */
trait ApprovalPolicy:
  def decide(tool: String, input: Json): IO[FlowError, ApprovalDecision]

object ApprovalPolicy:

  /** Approve everything — the autonomous default (claude still runs inside the runtime-owned git/branch sandbox). */
  val autoApprove: ApprovalPolicy = (_, _) => ZIO.succeed(ApprovalDecision.Allow)

  /** Ask the human to approve each tool call via [[Interaction]]: `y`/`yes` allows, anything else denies. */
  def interactive(
    interaction: Interaction,
    summarise: (String, Json) => String = defaultSummary,
  ): ApprovalPolicy =
    (tool, input) =>
      interaction.ask(s"Allow ${summarise(tool, input)}? [y/N]").map { answer =>
        if isYes(answer) then ApprovalDecision.Allow
        else ApprovalDecision.Deny("denied by operator")
      }

  private def isYes(answer: String): Boolean =
    val a = answer.trim.toLowerCase
    a == "y" || a == "yes"

  private def defaultSummary(tool: String, input: Json): String =
    val maxArgs = 80
    val args    = input.toString
    if args.length <= maxArgs then s"$tool $args" else s"$tool ${args.take(maxArgs - 1)}…"
