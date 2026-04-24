package deploy.control

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import zio.*
import zio.test.*

import deploy.entity.*

object DeployDispatcherSpec extends ZIOSpecDefault:

  private def tempDirs: UIO[(Path, Path)] =
    ZIO.succeed {
      val repo = Files.createTempDirectory("deploy-repo-")
      val ws   = Files.createTempDirectory("deploy-ws-")
      (repo, ws)
    }

  def spec = suite("DeployDispatcher")(
    test("Kubernetes target writes deployment.yaml under <workspace>/k8s/") {
      for
        tmp       <- tempDirs
        (repo, ws) = tmp
        result    <- DeployDispatcher.deploy(
                       DeploySpec(DeployTarget.Kubernetes, ws, repo, imageName = "my-ws")
                     )
        manifest   = ws.resolve("k8s").resolve("deployment.yaml")
        exists     = Files.isRegularFile(manifest)
        body       = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)
        hasName    = body.contains("name: my-ws")
      yield
        assertTrue(result.artifacts.contains(manifest)) &&
        assertTrue(exists) &&
        assertTrue(hasName) &&
        assertTrue(result.imageRef.isEmpty)
    },
    test("CloudRun target writes cloud-run/service.yaml") {
      for
        tmp       <- tempDirs
        (repo, ws) = tmp
        result    <- DeployDispatcher.deploy(
                       DeploySpec(DeployTarget.CloudRun, ws, repo, imageName = "cr-ws")
                     )
        manifest   = ws.resolve("cloud-run").resolve("service.yaml")
        exists     = Files.isRegularFile(manifest)
      yield
        assertTrue(result.artifacts.contains(manifest)) &&
        assertTrue(exists)
    },
    test("Docker dry-run writes Dockerfile but does not invoke docker") {
      for
        tmp       <- tempDirs
        (repo, ws) = tmp
        result    <- DeployDispatcher.deploy(
                       DeploySpec(DeployTarget.Docker, ws, repo, imageName = "img", imageTag = "t", dryRun = true)
                     )
        dockerfile = repo.resolve("Dockerfile.llm4zio")
        exists     = Files.isRegularFile(dockerfile)
      yield
        assertTrue(result.artifacts.contains(dockerfile)) &&
        assertTrue(exists) &&
        assertTrue(result.imageRef.isEmpty) &&
        assertTrue(result.notes.exists(_.contains("Dockerfile at")))
    },
    test("AgentRuntime target fails with Unsupported") {
      for
        tmp       <- tempDirs
        (repo, ws) = tmp
        exit      <- DeployDispatcher
                       .deploy(DeploySpec(DeployTarget.AgentRuntime, ws, repo))
                       .either
      yield assertTrue(exit match
        case Left(DeployError.Unsupported(_)) => true
        case _                                => false
      )
    },
  )
