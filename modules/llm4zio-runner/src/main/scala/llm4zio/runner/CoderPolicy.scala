package llm4zio.runner

import llm4zio.core.{ Capability, CliConnectorConfig, ConnectorId, Grants }

/** Best-effort translation of a restricted flow's [[Grants]] into the coder CLI's own permission vocabulary (issue
  * #716, trust boundary per ADR 0021: witnesses + the FiberRef gate are guarantees for flow code and API tool calls;
  * the coder is an external process, so policy is a *translation*, never a proof).
  *
  * Claude expresses deny-lists via `--disallowed-tools` patterns, so ungranted publish-grade capabilities map onto
  * denied Bash commands. A connector that cannot express a needed restriction reports it in `unenforceable` — the entry
  * point publishes those as [[llm4zio.flow.FlowEvent.CapabilityUnenforceable]] events, or fails fast under strict
  * policy. `CliSandbox` remains the hard opt-in boundary (currently wired for gemini only; claude/codex container
  * wrapping is a fast-follow).
  */
object CoderPolicy:

  /** The adjusted coder config plus whatever restrictions the connector could not express. */
  final case class Applied(config: CliConnectorConfig, unenforceable: List[String])

  def apply(config: CliConnectorConfig, grants: Grants): Applied =
    val denied = deniedPatterns(grants)
    if denied.isEmpty then Applied(config, Nil)
    else
      config.connectorId match
        case ConnectorId.ClaudeCli =>
          Applied(withDisallowed(config, denied.map(_._2)), Nil)
        case other                 =>
          Applied(
            config,
            denied.map((cap, _) => s"$other cannot enforce the missing $cap restriction on the coder"),
          )

  /** The publish-grade capabilities the grants withhold, with claude's deny pattern for each. */
  private def deniedPatterns(grants: Grants): List[(Capability, String)] =
    List(
      Capability.GitPush  -> "Bash(git push:*)",
      Capability.GitWrite -> "Bash(git commit:*),Bash(git add:*),Bash(git checkout:*)",
      Capability.GhWrite  -> "Bash(gh pr:*),Bash(gh issue:*),Bash(gh api:*)",
      Capability.AdoWrite -> "Bash(az repos:*),Bash(az boards:*)",
    ).filterNot((cap, _) => grants.allows(cap))

  /** Merge deny patterns into `--disallowed-tools`, preserving any entries the user already configured. */
  private def withDisallowed(config: CliConnectorConfig, patterns: List[String]): CliConnectorConfig =
    val existing = config.flags.get("disallowed-tools").filter(_.nonEmpty).toList
    val merged   = (existing ++ patterns).mkString(",")
    config.copy(flags = config.flags + ("disallowed-tools" -> merged))
