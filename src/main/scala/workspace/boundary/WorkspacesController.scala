package workspace.boundary

import java.nio.file.{ Files, Paths }
import java.time.{ Instant, LocalDate, ZoneOffset }

import zio.*
import zio.http.*
import zio.json.*

import analysis.control.WorkspaceAnalysisScheduler
import orchestration.control.AgentRegistry
import shared.errors.PersistenceError
import shared.web.WorkspacesView
import workspace.control.{ AssignRunRequest, GitService, WorkspaceRunService }
import workspace.entity.*

object WorkspacesController:

  def routes(
    repo: WorkspaceRepository,
    runSvc: WorkspaceRunService,
    agentRegistry: AgentRegistry,
    issueRepo: issues.entity.IssueRepository,
    gitService: GitService,
    analysisScheduler: WorkspaceAnalysisScheduler,
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

      // Runs dashboard page (across all workspaces)
      Method.GET / "runs" -> handler { (req: Request) =>
        ZIO.succeed(
          Response(
            status = Status.MovedPermanently,
            headers = Headers(Header.Location(URL.decode("/").getOrElse(URL.root))),
          )
        )
      },

      // Runs dashboard HTMX fragment
      Method.GET / "runs" / "fragment" -> handler { (req: Request) =>
        val query = parseRunsDashboardQuery(req)
        (for
          workspaces <- repo.list.mapError(persistErr)
          allRuns    <- listAllRuns(repo, workspaces).mapError(persistErr)
          filtered    = filterRuns(allRuns, query)
          sorted      = sortRuns(filtered, query.sortBy)
          scoped      = applyScope(sorted, query.scope)
          limited     = scoped.take(query.limit)
          byId        = workspaces.map(ws => ws.id -> ws.name).toMap
        yield html(WorkspacesView.runsDashboardRowsFragment(limited, byId))).catchAll(ZIO.succeed)
      },

      // Runs JSON API (across all workspaces)
      Method.GET / "api" / "runs" -> handler { (req: Request) =>
        val query = parseRunsDashboardQuery(req)
        val scope = if query.status.nonEmpty then "all" else query.scope
        (for
          workspaces <- repo.list.mapError(persistErr)
          allRuns    <- listAllRuns(repo, workspaces).mapError(persistErr)
          filtered    = filterRuns(allRuns, query)
          sorted      = sortRuns(filtered, query.sortBy)
          scoped      = applyScope(sorted, scope)
          limited     = scoped.take(query.limit)
          byId        = workspaces.map(ws => ws.id -> ws.name).toMap
          payload     = limited.map(run =>
                          RunDashboardRow(
                            runId = run.id,
                            workspaceId = run.workspaceId,
                            workspaceName = byId.getOrElse(run.workspaceId, run.workspaceId),
                            issueRef = run.issueRef,
                            agentName = run.agentName,
                            status = run.status.toString,
                            conversationId = run.conversationId,
                            createdAt = run.createdAt,
                            updatedAt = run.updatedAt,
                            durationSeconds = math.max(
                              0L,
                              java.time.Duration
                                .between(
                                  run.createdAt,
                                  run.status match
                                    case RunStatus.Pending | RunStatus.Running(_) => run.updatedAt
                                    case _                                        => run.updatedAt,
                                )
                                .getSeconds,
                            ),
                          )
                        )
        yield Response.json(payload.toJson)).catchAll(ZIO.succeed)
      },

      // Full page
      Method.GET / "settings" / "workspaces"                -> handler { (_: Request) =>
        (for
          ws         <- repo.list.mapError(persistErr)
          agents     <- agentRegistry.getAllAgents
          statusByWs <- loadAnalysisStatuses(ws.map(_.id), analysisScheduler).mapError(persistErr)
        yield html(WorkspacesView.page(ws, agents, statusByWs)))
          .catchAll(ZIO.succeed)
      },
      Method.GET / "settings" / "workspaces" / string("id") -> handler { (id: String, _: Request) =>
        (for
          workspace <- repo.get(id).mapError(persistErr)
          agents    <- agentRegistry.getAllAgents
          response  <- workspace match
                         case None     => ZIO.succeed(Response(status = Status.NotFound))
                         case Some(ws) =>
                           analysisScheduler
                             .statusForWorkspace(id)
                             .mapError(persistErr)
                             .map(statuses => html(WorkspacesView.detailPage(ws, agents, statuses)))
        yield response)
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
          patch      <- parseFormBody(req)
          now        <- Clock.instant
          id          = java.util.UUID.randomUUID().toString
          _          <- repo
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
          _          <- analysisScheduler.triggerForWorkspaceEvent(id).forkDaemon
          all        <- repo.list.mapError(persistErr)
          agents     <- agentRegistry.getAllAgents
          statusByWs <- loadAnalysisStatuses(all.map(_.id), analysisScheduler).mapError(persistErr)
        yield html(WorkspacesView.page(all, agents, statusByWs))).catchAll(ZIO.succeed)
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
                            _          <- repo
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
                            _          <- analysisScheduler.triggerForWorkspaceEvent(id).forkDaemon
                            all        <- repo.list.mapError(persistErr)
                            agents     <- agentRegistry.getAllAgents
                            statusByWs <- loadAnalysisStatuses(all.map(_.id), analysisScheduler).mapError(persistErr)
                          yield html(WorkspacesView.page(all, agents, statusByWs))
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
      Method.GET / "api" / "workspaces" / string("id") / "runs"            -> handler { (id: String, _: Request) =>
        repo.listRuns(id)
          .mapError(persistErr)
          .map(runs => html(WorkspacesView.runsFragment(runs)))
          .catchAll(ZIO.succeed)
      },
      Method.GET / "api" / "workspaces" / string("id") / "analysis-status" -> handler { (id: String, _: Request) =>
        analysisScheduler
          .statusForWorkspace(id)
          .mapError(persistErr)
          .map(statuses => html(WorkspacesView.analysisStatusFragment(id, statuses)))
          .catchAll(ZIO.succeed)
      },
      Method.POST / "api" / "workspaces" / string("id") / "reanalyze"      -> handler { (id: String, _: Request) =>
        (for
          _        <- analysisScheduler.triggerManual(id).forkDaemon
          statuses <- analysisScheduler.statusForWorkspace(id).mapError(persistErr)
        yield html(WorkspacesView.analysisStatusFragment(id, statuses))).catchAll(ZIO.succeed)
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

      // Apply run branch into workspace repository
      Method.POST / "api" / "workspaces" / string("wsId") / "runs" / string("runId") / "apply" ->
        handler { (wsId: String, runId: String, _: Request) =>
          resolveRunWorktree(repo, wsId, runId).flatMap {
            case Left(resp)                      => ZIO.succeed(resp)
            case Right((workspace, run, wtPath)) =>
              applyRunBranchToRepo(workspace, run, wtPath)
          }
        },

      // Git status for run worktree
      Method.GET / "api" / "workspaces" / string("wsId") / "runs" / string("runId") / "git" / "status" ->
        handler { (wsId: String, runId: String, _: Request) =>
          resolveRunWorktree(repo, wsId, runId).flatMap {
            case Left(resp)        => ZIO.succeed(resp)
            case Right((_, _, wt)) =>
              gitService.status(wt).map(status => Response.json(status.toJson)).catchAll(err =>
                ZIO.succeed(gitErr(err))
              )
          }
        },

      // Git diff stat (unstaged default, staged with ?staged=true)
      Method.GET / "api" / "workspaces" / string("wsId") / "runs" / string("runId") / "git" / "diff" ->
        handler { (wsId: String, runId: String, req: Request) =>
          val staged = req.queryParam("staged").exists(_.trim.equalsIgnoreCase("true"))
          resolveRunWorktree(repo, wsId, runId).flatMap {
            case Left(resp)        => ZIO.succeed(resp)
            case Right((_, _, wt)) =>
              gitService
                .diffStat(wt, staged = staged)
                .map(diff => Response.json(diff.toJson))
                .catchAll(err => ZIO.succeed(gitErr(err)))
          }
        },

      // Git file diff (URL-encoded file path segment)
      Method.GET / "api" / "workspaces" / string("wsId") / "runs" / string("runId") / "git" / "diff" / string(
        "filePath"
      ) ->
        handler { (wsId: String, runId: String, filePath: String, _: Request) =>
          val decoded = urlDecode(filePath)
          validateGitFilePath(decoded).flatMap {
            case Left(resp) => ZIO.succeed(resp)
            case Right(fp)  =>
              resolveRunWorktree(repo, wsId, runId).flatMap {
                case Left(resp)        => ZIO.succeed(resp)
                case Right((_, _, wt)) =>
                  gitService
                    .diffFile(wt, fp)
                    .map(diff => Response.text(diff).contentType(MediaType.text.plain))
                    .catchAll(err => ZIO.succeed(gitErr(err)))
              }
          }
        },

      // Git log
      Method.GET / "api" / "workspaces" / string("wsId") / "runs" / string("runId") / "git" / "log" ->
        handler { (wsId: String, runId: String, req: Request) =>
          parseLimit(req.queryParam("limit")).flatMap {
            case Left(resp)   => ZIO.succeed(resp)
            case Right(limit) =>
              resolveRunWorktree(repo, wsId, runId).flatMap {
                case Left(resp)        => ZIO.succeed(resp)
                case Right((_, _, wt)) =>
                  gitService.log(wt, limit).map(log => Response.json(log.toJson)).catchAll(err =>
                    ZIO.succeed(gitErr(err))
                  )
              }
          }
        },

      // Git branch + ahead/behind main
      Method.GET / "api" / "workspaces" / string("wsId") / "runs" / string("runId") / "git" / "branch" ->
        handler { (wsId: String, runId: String, _: Request) =>
          val baseBranch = "main"
          resolveRunWorktree(repo, wsId, runId).flatMap {
            case Left(resp)        => ZIO.succeed(resp)
            case Right((_, _, wt)) =>
              (for
                info        <- gitService.branchInfo(wt)
                aheadBehind <- gitService.aheadBehind(wt, baseBranch)
              yield Response.json(GitBranchView(info, aheadBehind, baseBranch).toJson))
                .catchAll(err => ZIO.succeed(gitErr(err)))
          }
        },

      // Issue search — returns backlog/todo issues matching ?q= for the assign-run search dropdown
      Method.GET / "api" / "workspaces" / "issues" / "search" -> handler { (req: Request) =>
        val q = req.queryParam("q").map(_.trim.toLowerCase).filter(_.nonEmpty)
        issueRepo
          .list(
            issues.entity.IssueFilter(
              states = Set(
                issues.entity.IssueStateTag.Backlog,
                issues.entity.IssueStateTag.Todo,
                issues.entity.IssueStateTag.Open,
              ),
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
                status = issues.entity.api.IssueStatus.Todo,
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

  private def parseLimit(limitRaw: Option[String]): UIO[Either[Response, Int]] =
    limitRaw.map(_.trim).filter(_.nonEmpty) match
      case None        => ZIO.succeed(Right(20))
      case Some(value) =>
        value.toIntOption match
          case Some(v) if v > 0 => ZIO.succeed(Right(v))
          case _                => ZIO.succeed(Left(Response.badRequest("Invalid limit query param")))

  private def validateGitFilePath(filePath: String): UIO[Either[Response, String]] =
    val trimmed = filePath.trim
    if trimmed.isEmpty then ZIO.succeed(Left(Response.badRequest("Invalid file path")))
    else
      val normalized = Paths.get(trimmed).normalize
      val isAbsolute = normalized.isAbsolute || trimmed.startsWith("/")
      val escapes    = normalized.iterator().hasNext && normalized.startsWith("..")
      if isAbsolute || escapes then ZIO.succeed(Left(Response.badRequest("Invalid file path")))
      else ZIO.succeed(Right(normalized.toString))

  private def resolveRunWorktree(
    repo: WorkspaceRepository,
    workspaceId: String,
    runId: String,
  ): UIO[Either[Response, (Workspace, WorkspaceRun, String)]] =
    (for
      wsOpt  <- repo.get(workspaceId).mapError(persistErr)
      ws     <- wsOpt match
                  case Some(value) => ZIO.succeed(value)
                  case None        => ZIO.fail(Response(status = Status.NotFound))
      runOpt <- repo.getRun(runId).mapError(persistErr)
      run    <- runOpt match
                  case Some(value) if value.workspaceId == workspaceId => ZIO.succeed(value)
                  case _                                               => ZIO.fail(Response(status = Status.NotFound))
      wtPath <- ZIO.succeed(run.worktreePath.trim)
      _      <- ZIO.when(wtPath.isEmpty || !Files.exists(Paths.get(wtPath))) {
                  ZIO.fail(Response.text("Run worktree not found (likely cleaned up)").status(Status.NotFound))
                }
    yield (ws, run, wtPath)).either

  private def applyRunBranchToRepo(workspace: Workspace, run: WorkspaceRun, worktreePath: String): UIO[Response] =
    val sourceBranch = run.branchName.trim
    def fail(
      status: Status,
      message: String,
      output: Option[String] = None,
      targetBranch: Option[String] = None,
    ): Response =
      Response
        .json(
          RunApplyResponse(
            applied = false,
            message = message,
            sourceBranch = sourceBranch,
            targetBranch = targetBranch,
            output = output.filter(_.trim.nonEmpty),
          ).toJson
        )
        .copy(status = status)

    val program =
      for
        repoPath    <- ZIO
                         .fromOption(Option(workspace.localPath).map(_.trim).filter(_.nonEmpty))
                         .orElseFail(fail(Status.BadRequest, "Workspace local path is empty."))
        _           <- ZIO.when(sourceBranch.isEmpty) {
                         ZIO.fail(fail(Status.BadRequest, "Run branch is empty; cannot apply to repository."))
                       }
        _           <- run.status match
                         case RunStatus.Pending | RunStatus.Running(_) =>
                           ZIO.fail(fail(Status.Conflict, "Run is still active. Wait for completion before applying."))
                         case _                                        => ZIO.unit
        _           <- ZIO.when(!Files.exists(Paths.get(repoPath))) {
                         ZIO.fail(fail(Status.NotFound, s"Workspace path not found: $repoPath"))
                       }
        repoChk     <- runGit(repoPath, List("rev-parse", "--is-inside-work-tree"))
        _           <- ZIO.when(repoChk._1 != 0 || repoChk._2.trim != "true") {
                         ZIO.fail(fail(Status.BadRequest, s"Not a git repository: $repoPath", Some(repoChk._2)))
                       }
        statusChk   <- runGit(repoPath, List("status", "--porcelain"))
        _           <- ZIO.when(statusChk._1 != 0) {
                         ZIO.fail(fail(Status.InternalServerError, "Failed to inspect repository status.", Some(statusChk._2)))
                       }
        _           <- ZIO.when(statusChk._2.trim.nonEmpty) {
                         ZIO.fail(
                           fail(
                             Status.Conflict,
                             "Workspace repository has uncommitted changes. Commit or stash before applying.",
                             Some(statusChk._2),
                           )
                         )
                       }
        target      <- resolveTargetBranch(repoPath).mapError(detail =>
                         fail(Status.InternalServerError, s"Unable to determine target branch: $detail")
                       )
        _           <- ZIO.when(target.isEmpty) {
                         ZIO.fail(fail(Status.InternalServerError, "Repository HEAD resolved to an empty branch name."))
                       }
        _           <- ZIO.when(target == "HEAD") {
                         ZIO.fail(
                           fail(
                             Status.BadRequest,
                             "Repository is in detached HEAD state. Check out a named branch before applying.",
                           )
                         )
                       }
        merge       <- runGit(repoPath, List("merge", "--no-ff", "--no-edit", sourceBranch))
        merged       = merge._1 == 0
        _           <- ZIO.when(!merged) {
                         // If histories diverge or conflicts arise, fall back to direct file sync from the run worktree.
                         // This keeps "Apply to repo" usable without requiring manual conflict resolution for generated branches.
                         runGit(repoPath, List("merge", "--abort")).ignore
                       }
        trackedWt   <- runGit(worktreePath, List("diff", "--name-only"))
        stagedWt    <- runGit(worktreePath, List("diff", "--cached", "--name-only"))
        untrackedWt <- runGit(worktreePath, List("ls-files", "--others", "--exclude-standard"))
        deletedWt   <- runGit(worktreePath, List("diff", "--name-only", "--diff-filter=D"))
        _           <- ZIO.when(
                         List(trackedWt, stagedWt, untrackedWt, deletedWt).exists(_._1 != 0)
                       ) {
                         ZIO.fail(
                           fail(
                             Status.InternalServerError,
                             "Failed to inspect run worktree changes.",
                             Some(
                               List(trackedWt, stagedWt, untrackedWt, deletedWt).map(_._2).mkString("\n")
                             ),
                             Some(target),
                           )
                         )
                       }
        changedRaw   =
          (parseGitPaths(trackedWt._2) ++ parseGitPaths(stagedWt._2) ++ parseGitPaths(untrackedWt._2)).distinct
        deletedRaw   = parseGitPaths(deletedWt._2).distinct
        changed     <- ZIO.foreach(changedRaw)(path =>
                         validateGitFilePath(path).flatMap {
                           case Left(resp) => ZIO.fail(resp)
                           case Right(ok)  => ZIO.succeed(ok)
                         }
                       )
        deleted     <- ZIO.foreach(deletedRaw)(path =>
                         validateGitFilePath(path).flatMap {
                           case Left(resp) => ZIO.fail(resp)
                           case Right(ok)  => ZIO.succeed(ok)
                         }
                       )
        syncResult  <- syncWorktreeChanges(worktreePath, repoPath, changed, deleted).mapError(msg =>
                         fail(Status.InternalServerError, "Failed to sync worktree changes.", Some(msg), Some(target))
                       )
        response    <- ZIO.succeed(
                         Response.json(
                           RunApplyResponse(
                             applied = true,
                             message =
                               if merged then
                                 s"Applied '$sourceBranch' into '$target' and synced ${syncResult._1} file(s), deleted ${syncResult._2}."
                               else
                                 s"Merge into '$target' was skipped; applied '$sourceBranch' changes via file sync and synced ${syncResult._1} file(s), deleted ${syncResult._2}."
                             ,
                             sourceBranch = sourceBranch,
                             targetBranch = Some(target),
                             output = Some(merge._2).filter(_.trim.nonEmpty),
                           ).toJson
                         )
                       )
      yield response

    program.catchAll(ZIO.succeed)

  private def resolveTargetBranch(repoPath: String): IO[String, String] =
    for
      revParse <- runGit(repoPath, List("rev-parse", "--abbrev-ref", "HEAD")).mapError(_.toString)
      branch   <- if revParse._1 == 0 then ZIO.succeed(revParse._2.trim)
                  else
                    // Handles repositories with unborn HEAD (no commits yet).
                    runGit(repoPath, List("symbolic-ref", "--quiet", "--short", "HEAD"))
                      .mapError(_.toString)
                      .flatMap {
                        case (0, out) => ZIO.succeed(out.trim)
                        case (_, out) =>
                          val revDetail = revParse._2.trim
                          val symDetail = out.trim
                          val detail    =
                            List(
                              Option.when(revDetail.nonEmpty)(s"rev-parse: $revDetail"),
                              Option.when(symDetail.nonEmpty)(s"symbolic-ref: $symDetail"),
                            ).flatten.mkString(" | ")
                          ZIO.fail(if detail.nonEmpty then detail else "git could not resolve HEAD branch")
                      }
      valid    <-
        if branch.nonEmpty then ZIO.succeed(branch)
        else ZIO.fail("git returned an empty branch name")
    yield valid

  private def parseGitPaths(raw: String): List[String] =
    raw.linesIterator.map(_.trim).filter(_.nonEmpty).toList

  private def syncWorktreeChanges(
    worktreePath: String,
    repoPath: String,
    changedPaths: List[String],
    deletedPaths: List[String],
  ): IO[String, (Int, Int)] =
    ZIO
      .attemptBlocking {
        val repoRoot = Paths.get(repoPath).normalize
        val wtRoot   = Paths.get(worktreePath).normalize

        val copied = changedPaths.distinct.count { rel =>
          val source = wtRoot.resolve(rel).normalize
          val target = repoRoot.resolve(rel).normalize
          if !source.startsWith(wtRoot) || !target.startsWith(repoRoot) then
            scala.sys.error(s"Unsafe path: $rel")
          if Files.exists(source) then
            Option(target.getParent).foreach(parent => Files.createDirectories(parent))
            Files.copy(
              source,
              target,
              java.nio.file.StandardCopyOption.REPLACE_EXISTING,
              java.nio.file.StandardCopyOption.COPY_ATTRIBUTES,
            )
            true
          else false
        }

        val deleted = deletedPaths.distinct.count { rel =>
          val target = repoRoot.resolve(rel).normalize
          if !target.startsWith(repoRoot) then scala.sys.error(s"Unsafe delete path: $rel")
          Files.deleteIfExists(target)
        }

        (copied, deleted)
      }
      .mapError(_.getMessage)

  private def runGit(repoPath: String, args: List[String]): IO[Response, (Int, String)] =
    ZIO
      .attemptBlocking {
        val cmd     = "git" :: "-c" :: "safe.directory=*" :: "-C" :: repoPath :: args
        val process = new ProcessBuilder(cmd*)
          .redirectErrorStream(true)
          .start()
        val output  = scala.io.Source.fromInputStream(process.getInputStream, "UTF-8").mkString
        val exit    = process.waitFor()
        (exit, output)
      }
      .mapError(err => Response.internalServerError(s"Failed to execute git command: ${err.getMessage}"))

  private def gitErr(err: GitError): Response =
    err match
      case GitError.NotAGitRepository(_) => Response.text("Worktree is not a git repository").status(Status.NotFound)
      case GitError.InvalidReference(_)  => Response.badRequest("Invalid git reference")
      case GitError.ParseFailure(_, _)   => Response.internalServerError("Failed to parse git output")
      case GitError.CommandFailed(_, _)  => Response.internalServerError("Git command failed")

  private def urlDecode(s: String): String =
    java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8)

  private def persistErr(e: PersistenceError): Response =
    Response.internalServerError(e.toString)

  private def html(bodyContent: String): Response =
    Response.text(bodyContent).contentType(MediaType.text.html)

  private def listAllRuns(
    repo: WorkspaceRepository,
    workspaces: List[Workspace],
  ): IO[PersistenceError, List[WorkspaceRun]] =
    ZIO.foreach(workspaces)(ws => repo.listRuns(ws.id)).map(_.flatten)

  private def loadAnalysisStatuses(
    workspaceIds: List[String],
    analysisScheduler: WorkspaceAnalysisScheduler,
  ): IO[PersistenceError, Map[String, List[analysis.control.WorkspaceAnalysisStatus]]] =
    ZIO.foreach(workspaceIds)(workspaceId =>
      analysisScheduler.statusForWorkspace(workspaceId).map(workspaceId -> _)
    ).map(_.toMap)

  private def parseRunsDashboardQuery(req: Request): RunsDashboardQuery =
    RunsDashboardQuery(
      workspace = req.queryParam("workspace").map(_.trim).filter(_.nonEmpty),
      agent = req.queryParam("agent").map(_.trim).filter(_.nonEmpty),
      status = req.queryParam("status").map(_.trim.toLowerCase).filter(_.nonEmpty),
      scope = req.queryParam("scope").map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("active"),
      sortBy = req.queryParam("sort").map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("created"),
      dateFrom = req.queryParam("from").map(_.trim).filter(_.nonEmpty),
      dateTo = req.queryParam("to").map(_.trim).filter(_.nonEmpty),
      limit = req.queryParam("limit").flatMap(_.toIntOption).map(v => math.max(1, math.min(500, v))).getOrElse(50),
    )

  private def statusToken(status: RunStatus): String = status match
    case RunStatus.Pending    => "pending"
    case RunStatus.Running(_) => "running"
    case RunStatus.Completed  => "completed"
    case RunStatus.Failed     => "failed"
    case RunStatus.Cancelled  => "cancelled"

  private def filterRuns(runs: List[WorkspaceRun], q: RunsDashboardQuery): List[WorkspaceRun] =
    val fromInstant = q.dateFrom.flatMap(s => scala.util.Try(LocalDate.parse(s)).toOption).map(
      _.atStartOfDay().toInstant(ZoneOffset.UTC)
    )
    val toInstant   = q.dateTo
      .flatMap(s => scala.util.Try(LocalDate.parse(s)).toOption)
      .map(_.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1))
    runs.filter { run =>
      q.workspace.forall(_.equalsIgnoreCase(run.workspaceId)) &&
      q.agent.forall(_.equalsIgnoreCase(run.agentName)) &&
      q.status.forall(_ == statusToken(run.status)) &&
      fromInstant.forall(from => !run.createdAt.isBefore(from)) &&
      toInstant.forall(to => !run.createdAt.isAfter(to))
    }

  private def applyScope(runs: List[WorkspaceRun], scope: String): List[WorkspaceRun] =
    scope match
      case "recent" =>
        runs.filter(run =>
          run.status == RunStatus.Completed || run.status == RunStatus.Failed || run.status == RunStatus.Cancelled
        )
      case "all"    => runs
      case _        =>
        runs.filter(run => run.status == RunStatus.Pending || run.status.isInstanceOf[RunStatus.Running])

  private def sortRuns(runs: List[WorkspaceRun], sortBy: String): List[WorkspaceRun] =
    sortBy match
      case "last_activity" =>
        runs.sortBy(_.updatedAt)(Ordering[Instant].reverse)
      case "duration"      =>
        runs.sortBy { run =>
          java.time.Duration.between(run.createdAt, run.updatedAt).getSeconds
        }(Ordering[Long].reverse)
      case _               =>
        val active = runs.filter(run => run.status == RunStatus.Pending || run.status.isInstanceOf[RunStatus.Running])
        val rest   = runs.filterNot(run => run.status == RunStatus.Pending || run.status.isInstanceOf[RunStatus.Running])
        active.sortBy(_.createdAt)(Ordering[Instant].reverse) ++ rest.sortBy(_.createdAt)(Ordering[Instant].reverse)

case class WorkspaceCreateRequest(
  name: String,
  localPath: String,
  defaultAgent: Option[String],
  description: Option[String],
  runMode: RunMode = RunMode.Host,
  cliTool: String = "claude",
) derives JsonCodec

case class ContinueRunRequest(prompt: String) derives JsonCodec

case class GitBranchView(
  branch: GitBranchInfo,
  aheadBehind: AheadBehind,
  baseBranch: String,
) derives JsonCodec

case class RunApplyResponse(
  applied: Boolean,
  message: String,
  sourceBranch: String,
  targetBranch: Option[String] = None,
  output: Option[String] = None,
) derives JsonCodec

case class RunDashboardRow(
  runId: String,
  workspaceId: String,
  workspaceName: String,
  issueRef: String,
  agentName: String,
  status: String,
  conversationId: String,
  createdAt: Instant,
  updatedAt: Instant,
  durationSeconds: Long,
) derives JsonCodec

final case class RunsDashboardQuery(
  workspace: Option[String],
  agent: Option[String],
  status: Option[String],
  scope: String,
  sortBy: String,
  dateFrom: Option[String],
  dateTo: Option[String],
  limit: Int,
)
