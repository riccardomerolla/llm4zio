package cli.commands

import java.nio.file.Files

import zio.*
import zio.test.*

object InfoCommandSpec extends ZIOSpecDefault:

  def spec = suite("InfoCommand")(
    test("extractProviders via render — no credentials shows hint") {
      val info = InfoCommand.Info(
        statePath = Files.createTempDirectory("info-"),
        stateExists = true,
        gatewayUrl = "http://localhost:8080",
        gatewayReachable = Some(false),
        providers = Nil,
        cliVersion = "1.0.0",
      )
      val out  = InfoCommand.render(info)
      assertTrue(out.contains("llm4zio-cli v1.0.0")) &&
      assertTrue(out.contains("✗ unreachable")) &&
      assertTrue(out.contains("no provider credentials configured"))
    },
    test("render with global provider shows masked key") {
      val info = InfoCommand.Info(
        statePath = Files.createTempDirectory("info-"),
        stateExists = true,
        gatewayUrl = "http://localhost:8080",
        gatewayReachable = Some(true),
        providers = List(
          InfoCommand.ProviderStatus("default", Some("openai"), Some("sk-abcdef1234567890"))
        ),
        cliVersion = "1.0.0",
      )
      val out  = InfoCommand.render(info)
      assertTrue(out.contains("provider=openai")) &&
      assertTrue(out.contains("sk-a***********7890")) &&
      assertTrue(!out.contains("abcdef")) &&
      assertTrue(out.contains("✓ reachable"))
    },
  )
