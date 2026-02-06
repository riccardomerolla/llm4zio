package config

import java.nio.file.Paths

import zio.*
import zio.test.*

import models.MigrationConfig

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
    suite("MigrationConfig defaults")(
      test("default config has expected default values") {
        val config = MigrationConfig(
          sourceDir = Paths.get("/tmp/src"),
          outputDir = Paths.get("/tmp/out"),
        )
        assertTrue(
          config.geminiModel == "gemini-2.5-flash",
          config.geminiMaxRetries == 3,
          config.parallelism == 4,
          config.batchSize == 10,
          config.enableCheckpointing,
          !config.dryRun,
          !config.verbose,
          config.discoveryMaxDepth == 25,
          config.discoveryExcludePatterns.nonEmpty,
        )
      }
    ),
  )
