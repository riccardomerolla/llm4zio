package workspace.boundary

import zio.*
import zio.http.*
import zio.json.*

import orchestration.control.AgentRegistry
import shared.errors.PersistenceError
import shared.web.WorkspacesView
import workspace.control.{ AssignRunRequest, WorkspaceRunService }
import workspace.entity.*

object WorkspacesController:

  def routes(
    repo: WorkspaceRepository,
    runSvc: WorkspaceRunService,
    agentRegistry: AgentRegistry,
    issueRepo: issues.entity.IssueRepository,
  ): Routes[Any, Response] =
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
        (for
          ws     <- repo.list.mapError(persistErr)
          agents <- agentRegistry.getAllAgents
        yield html(WorkspacesView.page(ws, agents)))
          .catchAll(ZIO.succeed)
      },

      // JSON list
      Method.GET / "api" / "workspaces" -> handler { (_: Request) =>
        repo.list
          .mapError(persistErr)
          .map(ws => Response.json(ws.toJson))
          .catchAll(ZIO.succeed)
      },

      // New workspace form fragment (no agents needed — modal is static)
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
          patch  <- parseFormBody(req)
          now    <- Clock.instant
          id      = java.util.UUID.randomUUID().toString
          _      <- repo
                      .append(
                        WorkspaceEvent.Created(
                          workspaceId = id,
                          name = patch.name,
                          localPath = patch.localPath,
                          defaultAgent = patch.defaultAgent,
                          description = patch.description,
                          cliTool = patch.cliTool,
                          runMode = patch.runMode,
                          occurredAt = now,
                        )
                      )
                      .mapError(persistErr)
          all    <- repo.list.mapError(persistErr)
          agents <- agentRegistry.getAllAgents
        yield html(WorkspacesView.page(all, agents))).catchAll(ZIO.succeed)
      },

      // Update
      Method.PUT / "api" / "workspaces" / string("id") -> handler { (id: String, req: Request) =>
        (for
          patch    <- parseFormBody(req)
          existing <- repo.get(id).mapError(persistErr)
          now      <- Clock.instant
          resp     <- existing match
                        case None    => ZIO.succeed(Response(status = Status.NotFound))
                        case Some(_) =>
                          for
                            _      <- repo
                                        .append(
                                          WorkspaceEvent.Updated(
                                            workspaceId = id,
                                            name = patch.name,
                                            localPath = patch.localPath,
                                            defaultAgent = patch.defaultAgent,
                                            description = patch.description,
                                            cliTool = patch.cliTool,
                                            runMode = patch.runMode,
                                            occurredAt = now,
                                          )
                                        )
                                        .mapError(persistErr)
                            all    <- repo.list.mapError(persistErr)
                            agents <- agentRegistry.getAllAgents
                          yield html(WorkspacesView.page(all, agents))
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
          assign <- parseAssignBody(req)
          result <- runSvc.assign(id, assign).either
          resp   <- result match
                      case Right(_)                                   =>
                        repo.listRuns(id).mapError(persistErr).map(runs => html(WorkspacesView.runsFragment(runs)))
                      case Left(WorkspaceError.NotFound(_))           =>
                        ZIO.succeed(html(WorkspacesView.assignErrorFragment("Workspace not found")))
                      case Left(WorkspaceError.Disabled(_))           =>
                        ZIO.succeed(html(WorkspacesView.assignErrorFragment("Workspace is disabled")))
                      case Left(WorkspaceError.DockerNotAvailable(r)) =>
                        ZIO.succeed(html(WorkspacesView.assignErrorFragment(s"Docker not available: $r")))
                      case Left(WorkspaceError.WorktreeError(msg))    =>
                        ZIO.succeed(html(WorkspacesView.assignErrorFragment(s"Git worktree error: $msg")))
                      case Left(other)                                =>
                        ZIO.succeed(html(WorkspacesView.assignErrorFragment(other.toString)))
        yield resp)
          .catchAll(err => ZIO.succeed(err))
          .catchAllDefect(t =>
            ZIO.logError(s"Unhandled defect in assign run: ${t.getMessage}\n${t.getStackTrace.mkString("\n  at ")}") *>
              ZIO.succeed(html(WorkspacesView.assignErrorFragment(t.getMessage)))
          )
      },

      // Run status (JSON)
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

      // Run row poll fragment (HTML) — used by HTMX to refresh a single row
      Method.GET / "api" / "workspaces" / string("wsId") / "runs" / string("runId") / "row" ->
        handler { (wsId: String, runId: String, _: Request) =>
          repo.getRun(runId)
            .mapError(persistErr)
            .map {
              case None      => Response(status = Status.NotFound)
              case Some(run) => html(WorkspacesView.runRowFragment(run))
            }
            .catchAll(ZIO.succeed)
        },

      // Cancel run
      Method.DELETE / "api" / "workspaces" / string("wsId") / "runs" / string("runId") ->
        handler { (wsId: String, runId: String, _: Request) =>
          runSvc.cancelRun(runId)
            .as(Response(status = Status.NoContent))
            .catchAll {
              case WorkspaceError.NotFound(_) => ZIO.succeed(Response(status = Status.NotFound))
              case other                      => ZIO.succeed(Response.internalServerError(other.toString))
            }
        },

      // Continue run
      Method.POST / "api" / "workspaces" / string("wsId") / "runs" / string("runId") / "continue" ->
        handler { (wsId: String, runId: String, req: Request) =>
          val wantsHtml = req.header(Header.Accept).exists(_.renderedValue.toLowerCase.contains("text/html"))
          parseContinueBody(req)
            .flatMap(body => runSvc.continueRun(runId, body.prompt))
            .flatMap { continued =>
              if wantsHtml then
                repo.listRuns(wsId).mapError(persistErr).map(runs => html(WorkspacesView.runsFragment(runs)))
              else
                ZIO.succeed(Response.json(s"""{"runId":"${continued.id}"}"""))
            }
            .catchAll {
              case WorkspaceError.NotFound(_)                   => ZIO.succeed(Response(status = Status.NotFound))
              case WorkspaceError.InvalidRunState(_, _, actual) =>
                ZIO.succeed(html(WorkspacesView.assignErrorFragment(actual)))
              case other                                        => ZIO.succeed(Response.internalServerError(other.toString))
            }
        },

      // Issue search — returns open/unassigned issues matching ?q= for the assign-run search dropdown
      Method.GET / "api" / "workspaces" / "issues" / "search" -> handler { (req: Request) =>
        val q = req.queryParam("q").map(_.trim.toLowerCase).filter(_.nonEmpty)
        issueRepo
          .list(
            issues.entity.IssueFilter(
              states = Set(issues.entity.IssueStateTag.Open),
              limit = 20,
            )
          )
          .map { all =>
            val filtered = q match
              case None       => all
              case Some(term) =>
                all.filter(i =>
                  i.title.toLowerCase.contains(term) ||
                  i.description.toLowerCase.contains(term) ||
                  i.id.value.contains(term)
                )
            val views    = filtered.map(i =>
              issues.entity.api.AgentIssueView(
                id = Some(i.id.value),
                title = i.title,
                description = i.description,
                issueType = i.issueType,
                priority = issues.entity.api.IssuePriority.values
                  .find(_.toString.equalsIgnoreCase(i.priority))
                  .getOrElse(issues.entity.api.IssuePriority.Medium),
                status = issues.entity.api.IssueStatus.Open,
                createdAt = java.time.Instant.EPOCH,
                updatedAt = java.time.Instant.EPOCH,
              )
            )
            html(WorkspacesView.issueSearchResults(views))
          }
          .catchAll(_ => ZIO.succeed(html(WorkspacesView.issueSearchResults(List.empty))))
      },
    )

  /** Parse a URL-encoded form body (as sent by HTMX by default) into a WorkspaceCreateRequest. */
  private def parseFormBody(req: Request): IO[Response, WorkspaceCreateRequest] =
    req.body.asString
      .mapError(_ => Response.internalServerError("body read failed"))
      .flatMap { body =>
        val fields = body
          .split("&")
          .collect {
            case s if s.contains("=") =>
              val idx = s.indexOf('=')
              urlDecode(s.substring(0, idx)) -> urlDecode(s.substring(idx + 1))
          }
          .toMap

        val name         = fields.getOrElse("name", "")
        val localPath    = fields.getOrElse("localPath", "")
        val defaultAgent = fields.get("defaultAgent").filter(_.nonEmpty)
        val description  = fields.get("description").filter(_.nonEmpty)
        val cliTool      = fields.get("cliTool").filter(_.nonEmpty).getOrElse("claude")
        val runModeType  = fields.getOrElse("runModeType", "host")
        val runMode      =
          if runModeType == "docker" then
            val image         = fields.getOrElse("dockerImage", "")
            val network       = fields.get("dockerNetwork").filter(_.nonEmpty)
            val mountWorktree = fields.get("dockerMount").contains("on")
            RunMode.Docker(image = image, mountWorktree = mountWorktree, network = network)
          else RunMode.Host

        if name.isEmpty || localPath.isEmpty then
          ZIO.fail(Response.badRequest("name and localPath are required"))
        else
          ZIO.succeed(WorkspaceCreateRequest(name, localPath, defaultAgent, description, runMode, cliTool))
      }

  /** Parse a URL-encoded form body into an AssignRunRequest. */
  private def parseAssignBody(req: Request): IO[Response, AssignRunRequest] =
    req.body.asString
      .mapError(_ => Response.internalServerError("body read failed"))
      .flatMap { body =>
        val fields    = body
          .split("&")
          .collect {
            case s if s.contains("=") =>
              val idx = s.indexOf('=')
              urlDecode(s.substring(0, idx)) -> urlDecode(s.substring(idx + 1))
          }
          .toMap
        val issueRef  = fields.getOrElse("issueRef", "")
        val prompt    = fields.getOrElse("prompt", "")
        val agentName = fields.getOrElse("agentName", "")
        if issueRef.isEmpty || prompt.isEmpty || agentName.isEmpty then
          ZIO.fail(Response.badRequest("issueRef, prompt and agentName are required"))
        else ZIO.succeed(AssignRunRequest(issueRef, prompt, agentName))
      }

  private def parseContinueBody(req: Request): IO[WorkspaceError, ContinueRunRequest] =
    req.body.asString
      .mapError(_ => WorkspaceError.WorktreeError("body read failed"))
      .flatMap { body =>
        val fields = body
          .split("&")
          .collect {
            case s if s.contains("=") =>
              val idx = s.indexOf('=')
              urlDecode(s.substring(0, idx)) -> urlDecode(s.substring(idx + 1))
          }
          .toMap
        val prompt = fields.getOrElse("prompt", body).trim
        if prompt.nonEmpty then ZIO.succeed(ContinueRunRequest(prompt))
        else ZIO.fail(WorkspaceError.WorktreeError("prompt is required"))
      }

  private def urlDecode(s: String): String =
    java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8)

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
  cliTool: String = "claude",
) derives JsonCodec

case class ContinueRunRequest(prompt: String) derives JsonCodec
