package workspace.boundary

import zio.*
import zio.http.*
import zio.json.*

import shared.errors.PersistenceError
import shared.web.WorkspacesView
import workspace.control.{ AssignRunRequest, WorkspaceRunService }
import workspace.entity.*

object WorkspacesController:

  def routes(repo: WorkspaceRepository, runSvc: WorkspaceRunService): Routes[Any, Response] =
    Routes(
      // Redirect /workspaces → /settings/workspaces
      Method.GET / "workspaces" -> handler { (_: Request) =>
        ZIO.succeed(
          Response(
            status = Status.Found,
            headers = Headers(Header.Location(URL.decode("/settings/workspaces").getOrElse(URL.root))),
          )
        )
      },

      // Full page
      Method.GET / "settings" / "workspaces" -> handler { (_: Request) =>
        repo.list
          .mapError(persistErr)
          .map(ws => html(WorkspacesView.page(ws)))
          .catchAll(ZIO.succeed)
      },

      // JSON list
      Method.GET / "api" / "workspaces" -> handler { (_: Request) =>
        repo.list
          .mapError(persistErr)
          .map(ws => Response.json(ws.toJson))
          .catchAll(ZIO.succeed)
      },

      // New workspace form fragment
      Method.GET / "api" / "workspaces" / "new" -> handler { (_: Request) =>
        ZIO.succeed(html(WorkspacesView.newWorkspaceForm))
      },

      // Edit workspace form fragment
      Method.GET / "api" / "workspaces" / string("id") / "edit" -> handler { (id: String, _: Request) =>
        repo.get(id)
          .mapError(persistErr)
          .map {
            case None     => Response(status = Status.NotFound)
            case Some(ws) => html(WorkspacesView.editWorkspaceForm(ws))
          }
          .catchAll(ZIO.succeed)
      },

      // Create
      Method.POST / "api" / "workspaces" -> handler { (req: Request) =>
        (for
          body  <- req.body.asString.mapError(_ => Response.internalServerError("body read failed"))
          patch <- ZIO
                     .fromEither(body.fromJson[WorkspaceCreateRequest])
                     .mapError(e => Response.badRequest(e))
          now   <- Clock.instant
          newWs  = Workspace(
                     id = java.util.UUID.randomUUID().toString,
                     name = patch.name,
                     localPath = patch.localPath,
                     defaultAgent = patch.defaultAgent,
                     description = patch.description,
                     enabled = true,
                     runMode = patch.runMode,
                     createdAt = now,
                     updatedAt = now,
                   )
          _     <- repo.save(newWs).mapError(persistErr)
          all   <- repo.list.mapError(persistErr)
        yield html(WorkspacesView.page(all))).catchAll(ZIO.succeed)
      },

      // Update
      Method.PUT / "api" / "workspaces" / string("id") -> handler { (id: String, req: Request) =>
        (for
          body     <- req.body.asString.mapError(_ => Response.internalServerError("body read failed"))
          patch    <- ZIO
                        .fromEither(body.fromJson[WorkspaceCreateRequest])
                        .mapError(e => Response.badRequest(e))
          existing <- repo.get(id).mapError(persistErr)
          now      <- Clock.instant
          resp     <- existing match
                        case None     => ZIO.succeed(Response(status = Status.NotFound))
                        case Some(ws) =>
                          val updated = ws.copy(
                            name = patch.name,
                            localPath = patch.localPath,
                            defaultAgent = patch.defaultAgent,
                            description = patch.description,
                            runMode = patch.runMode,
                            updatedAt = now,
                          )
                          for
                            _   <- repo.save(updated).mapError(persistErr)
                            all <- repo.list.mapError(persistErr)
                          yield html(WorkspacesView.page(all))
        yield resp).catchAll(ZIO.succeed)
      },

      // Delete
      Method.DELETE / "api" / "workspaces" / string("id") -> handler { (id: String, _: Request) =>
        repo.delete(id)
          .mapError(persistErr)
          .as(Response(status = Status.NoContent))
          .catchAll(ZIO.succeed)
      },

      // Runs list (HTMX fragment)
      Method.GET / "api" / "workspaces" / string("id") / "runs" -> handler { (id: String, _: Request) =>
        repo.listRuns(id)
          .mapError(persistErr)
          .map(runs => html(WorkspacesView.runsFragment(runs)))
          .catchAll(ZIO.succeed)
      },

      // Assign run
      Method.POST / "api" / "workspaces" / string("id") / "runs" -> handler { (id: String, req: Request) =>
        (for
          body   <- req.body.asString.mapError(_ => Response.internalServerError("body read failed"))
          assign <- ZIO
                      .fromEither(body.fromJson[AssignRunRequest])
                      .mapError(e => Response.badRequest(e))
          _      <- runSvc
                      .assign(id, assign)
                      .mapError {
                        case WorkspaceError.NotFound(_)        => Response(status = Status.NotFound)
                        case WorkspaceError.Disabled(_)        => Response(status = Status.Conflict)
                        case WorkspaceError.WorktreeError(msg) =>
                          Response.json(s"""{"error":"$msg"}""").status(Status.Conflict)
                        case other                             => Response.internalServerError(other.toString)
                      }
          runs   <- repo.listRuns(id).mapError(persistErr)
        yield html(WorkspacesView.runsFragment(runs))).catchAll(ZIO.succeed)
      },

      // Run status
      Method.GET / "api" / "workspaces" / string("wsId") / "runs" / string("runId") ->
        handler { (wsId: String, runId: String, _: Request) =>
          repo.getRun(runId)
            .mapError(persistErr)
            .map {
              case None      => Response(status = Status.NotFound)
              case Some(run) => Response.json(run.toJson)
            }
            .catchAll(ZIO.succeed)
        },
    )

  private def persistErr(e: PersistenceError): Response =
    Response.internalServerError(e.toString)

  private def html(bodyContent: String): Response =
    Response.text(bodyContent).contentType(MediaType.text.html)

case class WorkspaceCreateRequest(
  name: String,
  localPath: String,
  defaultAgent: Option[String],
  description: Option[String],
  runMode: RunMode = RunMode.Host,
) derives JsonCodec
