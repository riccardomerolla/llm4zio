package llm4zio.runner

import java.nio.file.Paths

import zio.test.*
import zio.Scope

object BannerSpec extends ZIOSpecDefault:
  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("Banner")(
    test("formats version and log path") {
      val out = Banner.line("2.3.0", Paths.get("/tmp/llm4zio-abc.log"))
      assertTrue(out == "llm4zio 2.3.0, logs: /tmp/llm4zio-abc.log")
    },
    test("version falls back to dev when manifest is absent") {
      assertTrue(Banner.version.nonEmpty)
    },
  )
