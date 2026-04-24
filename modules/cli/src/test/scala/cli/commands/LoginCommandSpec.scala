package cli.commands

import zio.*
import zio.test.*

object LoginCommandSpec extends ZIOSpecDefault:

  def spec = suite("LoginCommand")(
    test("writes global default provider + apiKey") {
      for
        repo   <- ConfigCommandSpec.makeRepo()
        msg    <- LoginCommand.login("OpenAI", "sk-test-1234567890abcdef", None).provide(ZLayer.succeed(repo))
        prov   <- repo.getSetting("connector.default.provider")
        key    <- repo.getSetting("connector.default.apiKey")
      yield
        assertTrue(prov.map(_.value).contains("openai")) &&
          assertTrue(key.map(_.value).contains("sk-test-1234567890abcdef")) &&
          assertTrue(msg.contains("global default")) &&
          assertTrue(msg.contains("sk-t"))
    },
    test("scopes to agent when name given") {
      for
        repo <- ConfigCommandSpec.makeRepo()
        _    <- LoginCommand.login("anthropic", "secret-key-abcd1234", Some("researcher")).provide(ZLayer.succeed(repo))
        prov <- repo.getSetting("agent.researcher.connector.provider")
        key  <- repo.getSetting("agent.researcher.connector.apiKey")
      yield
        assertTrue(prov.map(_.value).contains("anthropic")) &&
          assertTrue(key.map(_.value).contains("secret-key-abcd1234"))
    },
    test("rejects empty provider") {
      for
        repo   <- ConfigCommandSpec.makeRepo()
        result <- LoginCommand.login("   ", "key", None).provide(ZLayer.succeed(repo)).either
      yield assertTrue(result == Left("provider must not be empty"))
    },
    test("rejects empty key") {
      for
        repo   <- ConfigCommandSpec.makeRepo()
        result <- LoginCommand.login("openai", "", None).provide(ZLayer.succeed(repo)).either
      yield assertTrue(result == Left("api key must not be empty"))
    },
    test("maskKey preserves first/last 4 for long keys") {
      assertTrue(LoginCommand.maskKey("sk-abcdef1234567890") == "sk-a***********7890") &&
      assertTrue(LoginCommand.maskKey("short") == "*****")
    },
  )
