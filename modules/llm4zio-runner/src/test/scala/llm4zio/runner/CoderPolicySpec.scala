package llm4zio.runner

import zio.Scope
import zio.test.*

import llm4zio.core.{ CliConnectorConfig, ConnectorId, Grants }

/** Best-effort translation of a restricted flow's [[Grants]] into the coder CLI's own permission vocabulary (issue
  * #716, Q6): claude expresses deny-lists via `--disallowed-tools`; connectors that cannot express a needed restriction
  * report it as unenforceable instead of silently proceeding.
  */
object CoderPolicySpec extends ZIOSpecDefault:

  private val claude = CliConnectorConfig(ConnectorId.ClaudeCli)
  private val codex  = CliConnectorConfig(ConnectorId.Codex)

  private def disallowed(config: CliConnectorConfig): String =
    config.flags.getOrElse("disallowed-tools", "")

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("CoderPolicy")(
    test("full grants leave the config untouched with nothing unenforceable") {
      val applied = CoderPolicy(claude, Grants.all)
      assertTrue(applied.config == claude, applied.unenforceable.isEmpty)
    },
    test("claude: an ungranted push denies git push via disallowed-tools") {
      val applied = CoderPolicy(claude, Grants.all.copy(git = Grants.Level.Write))
      assertTrue(
        disallowed(applied.config).contains("Bash(git push:*)"),
        applied.unenforceable.isEmpty,
      )
    },
    test("claude: an ungranted gh write denies pr/issue/api commands") {
      val applied = CoderPolicy(claude, Grants.all.copy(gh = Grants.Level.Read))
      val d       = disallowed(applied.config)
      assertTrue(
        d.contains("Bash(gh pr:*)"),
        d.contains("Bash(gh issue:*)"),
        d.contains("Bash(gh api:*)"),
      )
    },
    test("claude: an ungranted git write denies commit-grade git commands") {
      val applied = CoderPolicy(claude, Grants.all.copy(git = Grants.Level.Read))
      val d       = disallowed(applied.config)
      assertTrue(d.contains("Bash(git commit:*)"), d.contains("Bash(git push:*)"))
    },
    test("claude: existing disallowed-tools entries are preserved when merging") {
      val pre     = claude.copy(flags = Map("disallowed-tools" -> "WebSearch"))
      val applied = CoderPolicy(pre, Grants.all.copy(git = Grants.Level.Write))
      val d       = disallowed(applied.config)
      assertTrue(d.contains("WebSearch"), d.contains("Bash(git push:*)"))
    },
    test("codex: restrictions it cannot express are reported unenforceable, config untouched") {
      val applied = CoderPolicy(codex, Grants.all.copy(git = Grants.Level.Write))
      assertTrue(
        applied.config == codex,
        applied.unenforceable.exists(_.toLowerCase.contains("push")),
      )
    },
  )
