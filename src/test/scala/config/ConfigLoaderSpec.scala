package config

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }

import zio.*
import zio.test.*

import models.*

object ConfigLoaderSpec extends ZIOSpecDefault:

  /** Default valid config for testing */
  private val validConfig: MigrationConfig = MigrationConfig(
    sourceDir = Paths.get("/tmp/cobol-source"),
    outputDir = Paths.get("/tmp/java-output"),
  )

  def spec: Spec[Any, Any] = suite("ConfigLoaderSpec")(
    suite("validate")(
      test("accepts valid default config") {
        for result <- ConfigLoader.validate(validConfig)
        yield assertTrue(result == validConfig)
      },
      // Parallelism validation
      test("rejects parallelism below 1") {
        for result <- ConfigLoader.validate(validConfig.copy(parallelism = 0)).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("Parallelism")),
        )
      },
      test("rejects parallelism above 64") {
        for result <- ConfigLoader.validate(validConfig.copy(parallelism = 65)).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("Parallelism")),
        )
      },
      test("accepts parallelism boundary value 1") {
        for result <- ConfigLoader.validate(validConfig.copy(parallelism = 1))
        yield assertTrue(result.parallelism == 1)
      },
      test("accepts parallelism boundary value 64") {
        for result <- ConfigLoader.validate(validConfig.copy(parallelism = 64))
        yield assertTrue(result.parallelism == 64)
      },
      // Batch size validation
      test("rejects batch size below 1") {
        for result <- ConfigLoader.validate(validConfig.copy(batchSize = 0)).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("Batch size")),
        )
      },
      test("rejects batch size above 100") {
        for result <- ConfigLoader.validate(validConfig.copy(batchSize = 101)).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("Batch size")),
        )
      },
      test("accepts batch size boundary values") {
        for
          r1 <- ConfigLoader.validate(validConfig.copy(batchSize = 1))
          r2 <- ConfigLoader.validate(validConfig.copy(batchSize = 100))
        yield assertTrue(r1.batchSize == 1, r2.batchSize == 100)
      },
      // Retries validation
      test("rejects retries below 0") {
        for result <- ConfigLoader.validate(validConfig.copy(geminiMaxRetries = -1)).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("retries")),
        )
      },
      test("rejects retries above 10") {
        for result <- ConfigLoader.validate(validConfig.copy(geminiMaxRetries = 11)).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("retries")),
        )
      },
      test("accepts retries boundary values") {
        for
          r1 <- ConfigLoader.validate(validConfig.copy(geminiMaxRetries = 0))
          r2 <- ConfigLoader.validate(validConfig.copy(geminiMaxRetries = 10))
        yield assertTrue(r1.geminiMaxRetries == 0, r2.geminiMaxRetries == 10)
      },
      // Timeout validation
      test("rejects timeout below 1 second") {
        for result <- ConfigLoader.validate(validConfig.copy(geminiTimeout = Duration.Zero)).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("Timeout")),
        )
      },
      test("rejects timeout above 10 minutes") {
        for result <- ConfigLoader.validate(validConfig.copy(geminiTimeout = Duration.fromSeconds(601))).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("Timeout")),
        )
      },
      test("accepts timeout boundary values") {
        for
          r1 <- ConfigLoader.validate(validConfig.copy(geminiTimeout = Duration.fromSeconds(1)))
          r2 <- ConfigLoader.validate(validConfig.copy(geminiTimeout = Duration.fromSeconds(600)))
        yield assertTrue(
          r1.geminiTimeout.toSeconds == 1L,
          r2.geminiTimeout.toSeconds == 600L,
        )
      },
      // Rate limiter validation
      test("rejects requests per minute below 1") {
        for result <- ConfigLoader.validate(validConfig.copy(geminiRequestsPerMinute = 0)).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("requests per minute")),
        )
      },
      test("rejects requests per minute above 600") {
        for result <- ConfigLoader.validate(validConfig.copy(geminiRequestsPerMinute = 601)).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("requests per minute")),
        )
      },
      test("rejects burst size below 1") {
        for result <- ConfigLoader.validate(validConfig.copy(geminiBurstSize = 0)).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("burst size")),
        )
      },
      test("rejects burst size above 100") {
        for result <- ConfigLoader.validate(validConfig.copy(geminiBurstSize = 101)).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("burst size")),
        )
      },
      test("rejects acquire timeout below 1 second") {
        for result <- ConfigLoader
                        .validate(validConfig.copy(geminiAcquireTimeout = Duration.fromMillis(500)))
                        .either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("acquire timeout")),
        )
      },
      test("rejects acquire timeout above 300 seconds") {
        for result <- ConfigLoader
                        .validate(validConfig.copy(geminiAcquireTimeout = Duration.fromSeconds(301)))
                        .either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("acquire timeout")),
        )
      },
      // Discovery validation
      test("rejects discovery max depth below 1") {
        for result <- ConfigLoader.validate(validConfig.copy(discoveryMaxDepth = 0)).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("max depth")),
        )
      },
      test("rejects discovery max depth above 100") {
        for result <- ConfigLoader.validate(validConfig.copy(discoveryMaxDepth = 101)).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("max depth")),
        )
      },
      test("rejects empty exclude patterns") {
        for result <- ConfigLoader
                        .validate(validConfig.copy(discoveryExcludePatterns = List("valid", "", "also-valid")))
                        .either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("empty values")),
        )
      },
      test("rejects whitespace-only exclude patterns") {
        for result <- ConfigLoader
                        .validate(validConfig.copy(discoveryExcludePatterns = List("valid", "  ", "also-valid")))
                        .either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("empty values")),
        )
      },
      test("accepts empty exclude patterns list") {
        for result <- ConfigLoader.validate(validConfig.copy(discoveryExcludePatterns = List.empty))
        yield assertTrue(result.discoveryExcludePatterns.isEmpty)
      },
      test("validates provider retries from aiProvider when present") {
        val config = validConfig.copy(
          aiProvider = Some(
            AIProviderConfig(
              provider = AIProvider.OpenAi,
              model = "gpt-4.1",
              maxRetries = 11,
            )
          ),
          geminiMaxRetries = 0, // Should be ignored when aiProvider is defined
        )
        for result <- ConfigLoader.validate(config).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("retries")),
        )
      },
      test("validates provider timeout from aiProvider when present") {
        val config = validConfig.copy(
          aiProvider = Some(
            AIProviderConfig(
              provider = AIProvider.Anthropic,
              model = "claude-3-5-sonnet",
              timeout = Duration.Zero,
            )
          ),
          geminiTimeout = Duration.fromSeconds(60), // Should be ignored when aiProvider is defined
        )
        for result <- ConfigLoader.validate(config).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("Timeout")),
        )
      },
      test("requires api key for OpenAI cloud endpoints") {
        val config = validConfig.copy(
          aiProvider = Some(
            AIProviderConfig(
              provider = AIProvider.OpenAi,
              model = "gpt-4o",
              baseUrl = Some("https://api.openai.com/v1"),
              apiKey = None,
            )
          )
        )
        for result <- ConfigLoader.validate(config).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("apiKey")),
        )
      },
      test("does not require api key for OpenAI localhost endpoint") {
        val config = validConfig.copy(
          aiProvider = Some(
            AIProviderConfig(
              provider = AIProvider.OpenAi,
              model = "local-model",
              baseUrl = Some("http://localhost:1234/v1"),
              apiKey = None,
            )
          )
        )
        for result <- ConfigLoader.validate(config).either
        yield assertTrue(result.isRight)
      },
      test("rejects invalid AI baseUrl format") {
        val config = validConfig.copy(
          aiProvider = Some(
            AIProviderConfig(
              provider = AIProvider.OpenAi,
              model = "gpt-4o",
              baseUrl = Some("not-a-valid-url"),
              apiKey = Some("test"),
            )
          )
        )
        for result <- ConfigLoader.validate(config).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("baseUrl")),
        )
      },
      test("rejects AI temperature outside allowed range") {
        val config = validConfig.copy(
          aiProvider = Some(
            AIProviderConfig(
              provider = AIProvider.GeminiApi,
              model = "gemini-2.5-flash",
              temperature = Some(2.5),
            )
          )
        )
        for result <- ConfigLoader.validate(config).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("temperature")),
        )
      },
      test("rejects AI max tokens outside allowed range") {
        val config = validConfig.copy(
          aiProvider = Some(
            AIProviderConfig(
              provider = AIProvider.GeminiApi,
              model = "gemini-2.5-flash",
              maxTokens = Some(0),
            )
          )
        )
        for result <- ConfigLoader.validate(config).either
        yield assertTrue(
          result.isLeft,
          result.left.exists(_.contains("max tokens")),
        )
      },
      test("rejects enabled telegram config without bot token") {
        val config = validConfig.copy(
          telegram = TelegramBotConfig(
            enabled = true,
            mode = TelegramMode.Polling,
            botToken = None,
          )
        )
        for result <- ConfigLoader.validate(config).either
        yield assertTrue(result.left.exists(_.contains("bot token")))
      },
      test("rejects webhook mode without webhook URL") {
        val config = validConfig.copy(
          telegram = TelegramBotConfig(
            enabled = true,
            mode = TelegramMode.Webhook,
            botToken = Some("123:ABC"),
            webhookUrl = None,
          )
        )
        for result <- ConfigLoader.validate(config).either
        yield assertTrue(result.left.exists(_.contains("webhook URL")))
      },
      test("rejects polling mode with invalid polling settings") {
        val config = validConfig.copy(
          telegram = TelegramBotConfig(
            enabled = true,
            mode = TelegramMode.Polling,
            botToken = Some("123:ABC"),
            polling = TelegramPollingSettings(interval = Duration.Zero, batchSize = 0, timeoutSeconds = 0),
          )
        )
        for result <- ConfigLoader.validate(config).either
        yield assertTrue(result.isLeft)
      },
      test("accepts valid telegram polling config") {
        val config = validConfig.copy(
          telegram = TelegramBotConfig(
            enabled = true,
            mode = TelegramMode.Polling,
            botToken = Some("123:ABC"),
            polling = TelegramPollingSettings(
              interval = Duration.fromSeconds(2),
              batchSize = 50,
              timeoutSeconds = 15,
              requestTimeout = Duration.fromSeconds(45),
            ),
          )
        )
        for result <- ConfigLoader.validate(config)
        yield assertTrue(result.telegram.polling.batchSize == 50)
      },
    ),
    suite("validate - property-based")(
      test("valid parallelism range always passes") {
        check(Gen.int(1, 64)) { parallelism =>
          for result <- ConfigLoader.validate(validConfig.copy(parallelism = parallelism))
          yield assertTrue(result.parallelism == parallelism)
        }
      },
      test("invalid parallelism always fails") {
        check(Gen.oneOf(Gen.int(Int.MinValue, 0), Gen.int(65, Int.MaxValue))) { parallelism =>
          for result <- ConfigLoader.validate(validConfig.copy(parallelism = parallelism)).either
          yield assertTrue(result.isLeft)
        }
      },
      test("valid batch size range always passes") {
        check(Gen.int(1, 100)) { batchSize =>
          for result <- ConfigLoader.validate(validConfig.copy(batchSize = batchSize))
          yield assertTrue(result.batchSize == batchSize)
        }
      },
      test("invalid batch size always fails") {
        check(Gen.oneOf(Gen.int(Int.MinValue, 0), Gen.int(101, Int.MaxValue))) { batchSize =>
          for result <- ConfigLoader.validate(validConfig.copy(batchSize = batchSize)).either
          yield assertTrue(result.isLeft)
        }
      },
      test("valid retries range always passes") {
        check(Gen.int(0, 10)) { retries =>
          for result <- ConfigLoader.validate(validConfig.copy(geminiMaxRetries = retries))
          yield assertTrue(result.geminiMaxRetries == retries)
        }
      },
      test("invalid retries always fails") {
        check(Gen.oneOf(Gen.int(Int.MinValue, -1), Gen.int(11, Int.MaxValue))) { retries =>
          for result <- ConfigLoader.validate(validConfig.copy(geminiMaxRetries = retries)).either
          yield assertTrue(result.isLeft)
        }
      },
      test("valid requests per minute range always passes") {
        check(Gen.int(1, 600)) { rpm =>
          for result <- ConfigLoader.validate(validConfig.copy(geminiRequestsPerMinute = rpm))
          yield assertTrue(result.geminiRequestsPerMinute == rpm)
        }
      },
      test("valid burst size range always passes") {
        check(Gen.int(1, 100)) { burst =>
          for result <- ConfigLoader.validate(validConfig.copy(geminiBurstSize = burst))
          yield assertTrue(result.geminiBurstSize == burst)
        }
      },
      test("valid discovery max depth range always passes") {
        check(Gen.int(1, 100)) { depth =>
          for result <- ConfigLoader.validate(validConfig.copy(discoveryMaxDepth = depth))
          yield assertTrue(result.discoveryMaxDepth == depth)
        }
      },
      test("non-empty exclude patterns always pass") {
        check(Gen.listOfBounded(0, 5)(Gen.alphaNumericStringBounded(1, 20))) { patterns =>
          for result <- ConfigLoader.validate(validConfig.copy(discoveryExcludePatterns = patterns))
          yield assertTrue(result.discoveryExcludePatterns == patterns)
        }
      },
    ),
    suite("loadFromFile")(
      test("fails for missing config file") {
        for result <- ConfigLoader.loadFromFile(Paths.get("/nonexistent/path/config.conf")).either
        yield assertTrue(result.isLeft)
      }
    ),
    suite("load and loadWithEnvOverrides")(
      test("load fails when no config is available") {
        for result <- ConfigLoader.load.either
        yield assertTrue(result.isLeft)
      },
      test("loadWithEnvOverrides fails when required fields missing") {
        for result <- ConfigLoader.loadWithEnvOverrides.either
        yield assertTrue(result.isLeft)
      },
    ),
    suite("AI config resolution helpers")(
      test("applyAIEnvironmentOverrides applies MIGRATION_AI_* values") {
        val env = Map(
          "MIGRATION_AI_PROVIDER"            -> "openai",
          "MIGRATION_AI_MODEL"               -> "gpt-4.1",
          "MIGRATION_AI_BASE_URL"            -> "http://localhost:1234/v1",
          "MIGRATION_AI_MAX_RETRIES"         -> "5",
          "MIGRATION_AI_REQUESTS_PER_MINUTE" -> "120",
          "MIGRATION_AI_TEMPERATURE"         -> "0.3",
          "MIGRATION_AI_MAX_TOKENS"          -> "4096",
        )
        for
          updated <- ConfigLoader.applyAIEnvironmentOverrides(validConfig, env)
          ai       = updated.resolvedProviderConfig
        yield assertTrue(
          ai.provider == AIProvider.OpenAi,
          ai.model == "gpt-4.1",
          ai.baseUrl.contains("http://localhost:1234/v1"),
          ai.maxRetries == 5,
          ai.requestsPerMinute == 120,
          ai.temperature.contains(0.3),
          ai.maxTokens.contains(4096),
        )
      },
      test("loadAIProviderFromFile reads migration.ai section") {
        val configContent =
          """migration {
            |  ai {
            |    provider = "anthropic"
            |    model = "claude-sonnet-4-20250514"
            |    base-url = "https://api.anthropic.com"
            |    timeout = 45s
            |    max-retries = 4
            |    requests-per-minute = 55
            |    burst-size = 9
            |    acquire-timeout = 20s
            |    temperature = 0.2
            |    max-tokens = 4096
            |  }
            |}
            |""".stripMargin

        for
          tempDir <- ZIO.attempt(Files.createTempDirectory("config-loader-ai-test-")).orDie
          confPath = tempDir.resolve("application.conf")
          _       <- ZIO.attempt(Files.writeString(confPath, configContent, StandardCharsets.UTF_8)).orDie
          loaded  <- ConfigLoader.loadAIProviderFromFile(confPath)
        yield assertTrue(
          loaded.isDefined,
          loaded.exists(_.provider == AIProvider.Anthropic),
          loaded.exists(_.model == "claude-sonnet-4-20250514"),
          loaded.exists(_.baseUrl.contains("https://api.anthropic.com")),
          loaded.exists(_.timeout.toSeconds == 45L),
          loaded.exists(_.maxRetries == 4),
          loaded.exists(_.requestsPerMinute == 55),
          loaded.exists(_.burstSize == 9),
          loaded.exists(_.acquireTimeout.toSeconds == 20L),
          loaded.exists(_.temperature.contains(0.2)),
          loaded.exists(_.maxTokens.contains(4096)),
        )
      },
    ),
    suite("MigrationConfig defaults")(
      test("default config has expected default values") {
        val config = MigrationConfig(
          sourceDir = Paths.get("/tmp/src"),
          outputDir = Paths.get("/tmp/out"),
        )
        assertTrue(
          config.geminiModel == "gemini-2.5-flash",
          config.geminiMaxRetries == 3,
          config.aiProvider.isEmpty,
          config.parallelism == 4,
          config.batchSize == 10,
          config.enableCheckpointing,
          !config.dryRun,
          !config.verbose,
          config.discoveryMaxDepth == 25,
          config.discoveryExcludePatterns.nonEmpty,
          !config.telegram.enabled,
        )
      }
    ),
  )
