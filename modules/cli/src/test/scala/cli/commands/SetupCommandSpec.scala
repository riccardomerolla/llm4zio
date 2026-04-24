package cli.commands

import java.nio.file.Files

import zio.*
import zio.test.*

object SetupCommandSpec extends ZIOSpecDefault:

  def spec = suite("SetupCommand")(
    test("creates state dir and seeds gateway.url on first run") {
      for
        tmp    <- ZIO.succeed(Files.createTempDirectory("setup-")).map(_.resolve("state"))
        repo   <- ConfigCommandSpec.makeRepo()
        msg    <- SetupCommand.setup(tmp, "http://localhost:9090").provide(ZLayer.succeed(repo))
        exists  = Files.isDirectory(tmp)
        stored <- repo.getSetting("gateway.url")
      yield
        assertTrue(exists) &&
          assertTrue(stored.map(_.value).contains("http://localhost:9090")) &&
          assertTrue(msg.contains("Seeded gateway.url"))
    },
    test("does not overwrite an existing gateway.url") {
      for
        tmp    <- ZIO.succeed(Files.createTempDirectory("setup-"))
        repo   <- ConfigCommandSpec.makeRepo(Map("gateway.url" -> "http://already.set:1234"))
        msg    <- SetupCommand.setup(tmp, "http://localhost:8080").provide(ZLayer.succeed(repo))
        stored <- repo.getSetting("gateway.url")
      yield
        assertTrue(stored.map(_.value).contains("http://already.set:1234")) &&
          assertTrue(msg.contains("already set"))
    },
  )
