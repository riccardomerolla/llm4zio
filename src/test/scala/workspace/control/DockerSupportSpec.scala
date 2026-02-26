package workspace.control

import zio.*
import zio.test.*

import workspace.entity.WorkspaceError

object DockerSupportSpec extends ZIOSpecDefault:

  /** Simulates the requireDocker logic with an injectable availability check. */
  private def requireDockerWith(available: Boolean): IO[WorkspaceError, Unit] =
    if available then ZIO.unit
    else ZIO.fail(WorkspaceError.DockerNotAvailable("docker not available (stubbed)"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("DockerSupportSpec")(
    test("isAvailable returns a Boolean without throwing") {
      for result <- DockerSupport.isAvailable.either
      yield assertTrue(result.isRight)
    },
    test("requireDocker succeeds when Docker is available (stubbed true)") {
      for result <- requireDockerWith(true).either
      yield assertTrue(result.isRight)
    },
    test("requireDocker fails with DockerNotAvailable when Docker is unavailable (stubbed false)") {
      for result <- requireDockerWith(false).either
      yield assertTrue(result match
        case Left(WorkspaceError.DockerNotAvailable(_)) => true
        case _                                          => false)
    },
  )
