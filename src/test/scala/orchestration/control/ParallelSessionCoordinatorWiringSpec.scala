package orchestration.control

import zio.*
import zio.test.*

/** Compile-time proof that ParallelSessionCoordinator is accessible as a ZIO service and that its ZLayer type signature
  * matches the design.
  */
object ParallelSessionCoordinatorWiringSpec extends ZIOSpecDefault:
  def spec: Spec[Environment, Any] = suite("ParallelSessionCoordinatorWiringSpec")(
    test("ParallelSessionCoordinator.live has the declared ZLayer type") {
      val _: ZLayer[
        workspace.entity.WorkspaceRunService &
          workspace.entity.WorkspaceRepository &
          OrchestratorControlPlane &
          gateway.control.MessageRouter,
        Nothing,
        ParallelSessionCoordinator,
      ] = ParallelSessionCoordinator.live
      assertCompletes
    }
  )
