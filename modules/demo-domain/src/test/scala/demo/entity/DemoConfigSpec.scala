package demo.entity

import zio.*
import zio.test.*
import zio.test.Assertion.*

object DemoConfigSpec extends ZIOSpecDefault:

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("DemoConfig")(
    suite("fromSettings")(
      test("default values when map is empty") {
        val cfg = DemoConfig.fromSettings(Map.empty)
        assertTrue(
          !cfg.enabled,
          cfg.issueCount == 25,
          cfg.agentDelaySeconds == 5,
        )
      },
      test("parses demo.enabled=true") {
        val cfg = DemoConfig.fromSettings(Map("demo.enabled" -> "true"))
        assertTrue(cfg.enabled)
      },
      test("treats demo.enabled=false as disabled") {
        val cfg = DemoConfig.fromSettings(Map("demo.enabled" -> "false"))
        assertTrue(!cfg.enabled)
      },
      test("parses demo.issueCount") {
        val cfg = DemoConfig.fromSettings(Map("demo.issueCount" -> "10"))
        assertTrue(cfg.issueCount == 10)
      },
      test("parses demo.agentDelaySeconds") {
        val cfg = DemoConfig.fromSettings(Map("demo.agentDelaySeconds" -> "3"))
        assertTrue(cfg.agentDelaySeconds == 3)
      },
      test("falls back to defaults for non-integer issueCount") {
        val cfg = DemoConfig.fromSettings(Map("demo.issueCount" -> "notanumber"))
        assertTrue(cfg.issueCount == 25)
      },
      test("falls back to defaults for non-integer agentDelaySeconds") {
        val cfg = DemoConfig.fromSettings(Map("demo.agentDelaySeconds" -> ""))
        assertTrue(cfg.agentDelaySeconds == 5)
      },
    )
  )
