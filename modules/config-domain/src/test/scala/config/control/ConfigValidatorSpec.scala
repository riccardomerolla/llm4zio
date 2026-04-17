package config.control

import zio.test.*

import _root_.config.entity.ConfigFormat

object ConfigValidatorSpec extends ZIOSpecDefault:

  private val validator = ConfigValidatorLive()

  private val validConfig =
    """migration {
      |  source-dir = "src"
      |  output-dir = "out"
      |  parallelism = 4
      |  batch-size = 10
      |}
      |""".stripMargin

  private val invalidConfig =
    """migration {
      |  source-dir = "src"
      |  output-dir = "out"
      |  parallelism = 0
      |}
      |""".stripMargin

  def spec: Spec[TestEnvironment, Any] = suite("ConfigValidatorSpec")(
    test("validates valid HOCON config") {
      for
        result <- validator.validate(validConfig, ConfigFormat.Hocon)
      yield assertTrue(result.valid)
    },
    test("returns issues for invalid config") {
      for
        result <- validator.validate(invalidConfig, ConfigFormat.Hocon)
      yield assertTrue(!result.valid, result.issues.nonEmpty)
    },
  )
