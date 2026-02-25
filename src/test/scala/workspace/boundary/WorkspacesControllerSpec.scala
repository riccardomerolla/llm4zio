package workspace.boundary

import java.time.Instant

import zio.*
import zio.http.*
import zio.test.*

import workspace.control.{ AssignRunRequest, WorkspaceRunService }
import workspace.entity.*

object WorkspacesControllerSpec extends ZIOSpecDefault:

  private val sampleWs = Workspace(
    id = "ws-1",
    name = "my-api",
    localPath = "/tmp/my-api",
    defaultAgent = Some("gemini-cli"),
    description = None,
    enabled = true,
    createdAt = Instant.parse("2026-02-24T10:00:00Z"),
    updatedAt = Instant.parse("2026-02-24T10:00:00Z"),
  )

  private class StubWorkspaceRepo(ref: Ref[Map[String, Workspace]]) extends WorkspaceRepository:
    def list: IO[shared.errors.PersistenceError, List[Workspace]]                           = ref.get.map(_.values.toList)
    def get(id: String): IO[shared.errors.PersistenceError, Option[Workspace]]              = ref.get.map(_.get(id))
    def save(ws: Workspace): IO[shared.errors.PersistenceError, Unit]                       = ref.update(_ + (ws.id -> ws))
    def delete(id: String): IO[shared.errors.PersistenceError, Unit]                        = ref.update(_ - id)
    def listRuns(wid: String): IO[shared.errors.PersistenceError, List[WorkspaceRun]]       = ZIO.succeed(Nil)
    def getRun(id: String): IO[shared.errors.PersistenceError, Option[WorkspaceRun]]        = ZIO.succeed(None)
    def saveRun(r: WorkspaceRun): IO[shared.errors.PersistenceError, Unit]                  = ZIO.unit
    def updateRunStatus(id: String, s: RunStatus): IO[shared.errors.PersistenceError, Unit] = ZIO.unit

  private class StubRunService extends WorkspaceRunService:
    def assign(wid: String, req: AssignRunRequest): IO[WorkspaceError, WorkspaceRun] =
      ZIO.fail(WorkspaceError.NotFound(wid))
    def continueRun(runId: String, followUp: String): IO[WorkspaceError, Unit]       = ZIO.unit

  def spec: Spec[TestEnvironment & Scope, Any] = suite("WorkspacesControllerSpec")(
    test("GET /settings/workspaces returns 200") {
      for
        wsRef <- Ref.make(Map("ws-1" -> sampleWs))
        repo   = StubWorkspaceRepo(wsRef)
        runSvc = StubRunService()
        routes = WorkspacesController.routes(repo, runSvc)
        req    = Request.get(URL(Path.decode("/settings/workspaces")))
        resp  <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.Ok)
    },
    test("GET /api/workspaces returns JSON list") {
      for
        wsRef <- Ref.make(Map("ws-1" -> sampleWs))
        repo   = StubWorkspaceRepo(wsRef)
        runSvc = StubRunService()
        routes = WorkspacesController.routes(repo, runSvc)
        req    = Request.get(URL(Path.decode("/api/workspaces")))
        resp  <- routes.runZIO(req)
        body  <- resp.body.asString
      yield assertTrue(resp.status == Status.Ok && body.contains("my-api"))
    },
    test("DELETE /api/workspaces/:id returns 204") {
      for
        wsRef <- Ref.make(Map("ws-1" -> sampleWs))
        repo   = StubWorkspaceRepo(wsRef)
        runSvc = StubRunService()
        routes = WorkspacesController.routes(repo, runSvc)
        req    = Request(method = Method.DELETE, url = URL(Path.decode("/api/workspaces/ws-1")))
        resp  <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.NoContent)
    },
    test("GET /api/workspaces/:id/runs returns 200") {
      for
        wsRef <- Ref.make(Map("ws-1" -> sampleWs))
        repo   = StubWorkspaceRepo(wsRef)
        runSvc = StubRunService()
        routes = WorkspacesController.routes(repo, runSvc)
        req    = Request.get(URL(Path.decode("/api/workspaces/ws-1/runs")))
        resp  <- routes.runZIO(req)
      yield assertTrue(resp.status == Status.Ok)
    },
  )
