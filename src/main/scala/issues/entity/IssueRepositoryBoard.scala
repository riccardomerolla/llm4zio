package issues.entity

import java.time.Instant

import zio.*

import board.entity.{ IssueEstimate as BoardIssueEstimate, IssuePriority as BoardIssuePriority, * }
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, BoardIssueId, IssueId }
import workspace.entity.{ Workspace, WorkspaceRepository }

final case class IssueRepositoryBoard(
  boardRepository: BoardRepository,
  workspaceRepository: WorkspaceRepository,
  historyRef: Ref[Map[IssueId, List[IssueEvent]]],
  pendingCreatedRef: Ref[Map[IssueId, IssueEvent.Created]],
) extends IssueRepository:

  private val boardColumns: List[BoardColumn] =
    List(
      BoardColumn.Backlog,
      BoardColumn.Todo,
      BoardColumn.InProgress,
      BoardColumn.Review,
      BoardColumn.Done,
      BoardColumn.Archive,
    )

  override def append(event: IssueEvent): IO[PersistenceError, Unit] =
    for
      _ <- historyRef.update(current => current.updated(event.issueId, current.getOrElse(event.issueId, Nil) :+ event))
      _ <- applyEvent(event)
    yield ()

  override def get(id: IssueId): IO[PersistenceError, AgentIssue] =
    resolveWorkspaceBoardIssue(id).flatMap {
      case Some((ws, issue)) => ZIO.succeed(boardToDomain(issue, ws.id))
      case None              => ZIO.fail(PersistenceError.NotFound("issue", id.value))
    }

  override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]] =
    historyRef.get.map(_.getOrElse(id, Nil))

  override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
    loadAllBoardIssues
      .map(_.filter(issueMatches(filter, _)))
      .map(_.slice(filter.offset.max(0), filter.offset.max(0) + filter.limit.max(0)))

  override def delete(id: IssueId): IO[PersistenceError, Unit] =
    for
      found <- resolveWorkspaceBoardIssue(id)
      _     <- found match
                 case Some((workspace, issue)) =>
                   boardRepository
                     .deleteIssue(workspace.localPath, issue.frontmatter.id)
                     .mapError(mapBoardError)
                 case None                     =>
                   ZIO.unit
      _     <- historyRef.update(_ - id)
      _     <- pendingCreatedRef.update(_ - id)
    yield ()

  private def applyEvent(event: IssueEvent): IO[PersistenceError, Unit] =
    event match
      case created: IssueEvent.Created                        =>
        ensureCreatedIssue(created)
      case linked: IssueEvent.WorkspaceLinked                 =>
        ensureCreatedFromPending(linked)
      case metadata: IssueEvent.MetadataUpdated               =>
        withExistingIssue(metadata.issueId) {
          case (workspace, issue) =>
            boardRepository
              .updateIssue(
                workspace.localPath,
                issue.frontmatter.id,
                fm =>
                  fm.copy(
                    title = metadata.title.trim,
                    priority = toBoardPriority(metadata.priority),
                    requiredCapabilities = sanitizeList(metadata.requiredCapabilities),
                  ),
              )
              .mapError(mapBoardError)
              .unit
        }
      case updated: IssueEvent.TagsUpdated                    =>
        withExistingIssue(updated.issueId) {
          case (workspace, issue) =>
            boardRepository
              .updateIssue(
                workspace.localPath,
                issue.frontmatter.id,
                _.copy(tags = sanitizeList(updated.tags)),
              )
              .mapError(mapBoardError)
              .unit
        }
      case linked: IssueEvent.DependencyLinked                =>
        withExistingIssue(linked.issueId) {
          case (workspace, issue) =>
            toBoardIssueId(linked.blockedByIssueId).fold[IO[PersistenceError, Unit]](ZIO.unit) { blockedById =>
              boardRepository
                .updateIssue(
                  workspace.localPath,
                  issue.frontmatter.id,
                  fm => fm.copy(blockedBy = (fm.blockedBy :+ blockedById).distinct),
                )
                .mapError(mapBoardError)
                .unit
            }
        }
      case unlinked: IssueEvent.DependencyUnlinked            =>
        withExistingIssue(unlinked.issueId) {
          case (workspace, issue) =>
            toBoardIssueId(unlinked.blockedByIssueId).fold[IO[PersistenceError, Unit]](ZIO.unit) { blockedById =>
              boardRepository
                .updateIssue(
                  workspace.localPath,
                  issue.frontmatter.id,
                  fm => fm.copy(blockedBy = fm.blockedBy.filterNot(_ == blockedById)),
                )
                .mapError(mapBoardError)
                .unit
            }
        }
      case assigned: IssueEvent.Assigned                      =>
        withExistingIssue(assigned.issueId) {
          case (workspace, issue) =>
            boardRepository
              .updateIssue(
                workspace.localPath,
                issue.frontmatter.id,
                _.copy(
                  assignedAgent = Some(assigned.agent.value),
                  transientState = TransientState.Assigned(assigned.agent.value, assigned.assignedAt),
                ),
              )
              .mapError(mapBoardError)
              .unit
        }
      case started: IssueEvent.Started                        =>
        withExistingIssue(started.issueId) {
          case (workspace, issue) =>
            for
              _ <- boardRepository
                     .updateIssue(
                       workspace.localPath,
                       issue.frontmatter.id,
                       _.copy(
                         assignedAgent = Some(started.agent.value),
                         transientState = TransientState.None,
                       ),
                     )
                     .mapError(mapBoardError)
              _ <- moveIssue(workspace.localPath, issue.frontmatter.id, BoardColumn.InProgress)
            yield ()
        }
      case moved: IssueEvent.MovedToBacklog                   =>
        withExistingIssue(moved.issueId) {
          case (workspace, issue) =>
            for
              _ <- moveIssue(workspace.localPath, issue.frontmatter.id, BoardColumn.Backlog)
              _ <- boardRepository
                     .updateIssue(
                       workspace.localPath,
                       issue.frontmatter.id,
                       _.copy(
                         transientState = TransientState.None,
                         failureReason = None,
                         completedAt = None,
                       ),
                     )
                     .mapError(mapBoardError)
            yield ()
        }
      case moved: IssueEvent.MovedToTodo                      =>
        withExistingIssue(moved.issueId) {
          case (workspace, issue) =>
            for
              _ <- moveIssue(workspace.localPath, issue.frontmatter.id, BoardColumn.Todo)
              _ <- boardRepository
                     .updateIssue(
                       workspace.localPath,
                       issue.frontmatter.id,
                       _.copy(transientState = TransientState.None),
                     )
                     .mapError(mapBoardError)
            yield ()
        }
      case moved: IssueEvent.MovedToHumanReview               =>
        withExistingIssue(moved.issueId) {
          case (workspace, issue) =>
            moveIssue(workspace.localPath, issue.frontmatter.id, BoardColumn.Review)
        }
      case moved: IssueEvent.MovedToRework                    =>
        withExistingIssue(moved.issueId) {
          case (workspace, issue) =>
            for
              _ <- moveIssue(workspace.localPath, issue.frontmatter.id, BoardColumn.Backlog)
              _ <- boardRepository
                     .updateIssue(
                       workspace.localPath,
                       issue.frontmatter.id,
                       _.copy(
                         transientState = TransientState.Rework(moved.reason, moved.movedAt),
                         failureReason = Some(moved.reason),
                       ),
                     )
                     .mapError(mapBoardError)
            yield ()
        }
      case moved: IssueEvent.MovedToMerging                   =>
        withExistingIssue(moved.issueId) {
          case (workspace, issue) =>
            boardRepository
              .updateIssue(
                workspace.localPath,
                issue.frontmatter.id,
                _.copy(transientState = TransientState.Merging(moved.movedAt)),
              )
              .mapError(mapBoardError)
              .unit
        }
      case done: IssueEvent.MarkedDone                        =>
        withExistingIssue(done.issueId) {
          case (workspace, issue) =>
            for
              _ <- moveIssue(workspace.localPath, issue.frontmatter.id, BoardColumn.Done)
              _ <- boardRepository
                     .updateIssue(
                       workspace.localPath,
                       issue.frontmatter.id,
                       _.copy(
                         transientState = TransientState.None,
                         completedAt = Some(done.doneAt),
                         failureReason = None,
                       ),
                     )
                     .mapError(mapBoardError)
            yield ()
        }
      case completed: IssueEvent.Completed                    =>
        withExistingIssue(completed.issueId) {
          case (workspace, issue) =>
            for
              _ <- moveIssue(workspace.localPath, issue.frontmatter.id, BoardColumn.Done)
              _ <- boardRepository
                     .updateIssue(
                       workspace.localPath,
                       issue.frontmatter.id,
                       _.copy(
                         transientState = TransientState.None,
                         completedAt = Some(completed.completedAt),
                         assignedAgent = Some(completed.agent.value),
                       ),
                     )
                     .mapError(mapBoardError)
            yield ()
        }
      case succeeded: IssueEvent.MergeSucceeded               =>
        withExistingIssue(succeeded.issueId) {
          case (workspace, issue) =>
            for
              _ <- moveIssue(workspace.localPath, issue.frontmatter.id, BoardColumn.Done)
              _ <- boardRepository
                     .updateIssue(
                       workspace.localPath,
                       issue.frontmatter.id,
                       _.copy(transientState = TransientState.None, completedAt = Some(succeeded.mergedAt)),
                     )
                     .mapError(mapBoardError)
            yield ()
        }
      case canceled: IssueEvent.Canceled                      =>
        withExistingIssue(canceled.issueId) {
          case (workspace, issue) =>
            for
              _ <- moveIssue(workspace.localPath, issue.frontmatter.id, BoardColumn.Archive)
              _ <- boardRepository
                     .updateIssue(
                       workspace.localPath,
                       issue.frontmatter.id,
                       _.copy(
                         transientState = TransientState.None,
                         failureReason = Some(canceled.reason),
                         completedAt = Some(canceled.canceledAt),
                       ),
                     )
                     .mapError(mapBoardError)
            yield ()
        }
      case duplicated: IssueEvent.Duplicated                  =>
        withExistingIssue(duplicated.issueId) {
          case (workspace, issue) =>
            for
              _ <- moveIssue(workspace.localPath, issue.frontmatter.id, BoardColumn.Archive)
              _ <- boardRepository
                     .updateIssue(
                       workspace.localPath,
                       issue.frontmatter.id,
                       _.copy(
                         transientState = TransientState.None,
                         failureReason = Some(duplicated.reason),
                         completedAt = Some(duplicated.duplicatedAt),
                       ),
                     )
                     .mapError(mapBoardError)
            yield ()
        }
      case skipped: IssueEvent.Skipped                        =>
        withExistingIssue(skipped.issueId) {
          case (workspace, issue) =>
            for
              _ <- moveIssue(workspace.localPath, issue.frontmatter.id, BoardColumn.Archive)
              _ <- boardRepository
                     .updateIssue(
                       workspace.localPath,
                       issue.frontmatter.id,
                       _.copy(
                         transientState = TransientState.None,
                         failureReason = Some(skipped.reason),
                         completedAt = Some(skipped.skippedAt),
                       ),
                     )
                     .mapError(mapBoardError)
            yield ()
        }
      case failed: IssueEvent.Failed                          =>
        withExistingIssue(failed.issueId) {
          case (workspace, issue) =>
            boardRepository
              .updateIssue(
                workspace.localPath,
                issue.frontmatter.id,
                _.copy(
                  assignedAgent = Some(failed.agent.value),
                  failureReason = Some(failed.errorMessage),
                ),
              )
              .mapError(mapBoardError)
              .unit
        }
      case failed: IssueEvent.MergeFailed                     =>
        withExistingIssue(failed.issueId) {
          case (workspace, issue) =>
            val reason =
              if failed.conflictFiles.isEmpty then "Merge failed"
              else s"Merge failed: conflicts in ${failed.conflictFiles.mkString(", ")}"
            boardRepository
              .updateIssue(
                workspace.localPath,
                issue.frontmatter.id,
                _.copy(
                  failureReason = Some(reason)
                ),
              )
              .mapError(mapBoardError)
              .unit
        }
      case conflict: IssueEvent.MergeConflictRecorded         =>
        withExistingIssue(conflict.issueId) {
          case (workspace, issue) =>
            val reason =
              if conflict.conflictingFiles.isEmpty then "Merge conflicts detected"
              else s"Merge conflicts: ${conflict.conflictingFiles.mkString(", ")}"
            boardRepository
              .updateIssue(
                workspace.localPath,
                issue.frontmatter.id,
                _.copy(
                  failureReason = Some(reason)
                ),
              )
              .mapError(mapBoardError)
              .unit
        }
      case updated: IssueEvent.AcceptanceCriteriaUpdated      =>
        withExistingIssue(updated.issueId) {
          case (workspace, issue) =>
            boardRepository
              .updateIssue(
                workspace.localPath,
                issue.frontmatter.id,
                _.copy(
                  acceptanceCriteria = splitListField(updated.acceptanceCriteria)
                ),
              )
              .mapError(mapBoardError)
              .unit
        }
      case updated: IssueEvent.EstimateUpdated                =>
        withExistingIssue(updated.issueId) {
          case (workspace, issue) =>
            boardRepository
              .updateIssue(
                workspace.localPath,
                issue.frontmatter.id,
                _.copy(estimate = toBoardEstimate(updated.estimate)),
              )
              .mapError(mapBoardError)
              .unit
        }
      case updated: IssueEvent.KaizenSkillUpdated             =>
        withExistingIssue(updated.issueId) {
          case (workspace, issue) =>
            val skillTags = splitListField(updated.kaizenSkill).map(skill => s"skill:$skill")
            boardRepository
              .updateIssue(
                workspace.localPath,
                issue.frontmatter.id,
                fm => fm.copy(tags = (fm.tags ++ skillTags).distinct),
              )
              .mapError(mapBoardError)
              .unit
        }
      case updated: IssueEvent.ProofOfWorkRequirementsUpdated =>
        withExistingIssue(updated.issueId) {
          case (workspace, issue) =>
            boardRepository
              .updateIssue(
                workspace.localPath,
                issue.frontmatter.id,
                _.copy(proofOfWork = sanitizeList(updated.requirements)),
              )
              .mapError(mapBoardError)
              .unit
        }
      case archived: IssueEvent.Archived                      =>
        withExistingIssue(archived.issueId) {
          case (workspace, issue) =>
            for
              _ <- moveIssue(workspace.localPath, issue.frontmatter.id, BoardColumn.Archive)
              _ <- boardRepository
                     .updateIssue(
                       workspace.localPath,
                       issue.frontmatter.id,
                       _.copy(
                         transientState = TransientState.None,
                         failureReason = None,
                         completedAt = Some(archived.archivedAt),
                       ),
                     )
                     .mapError(mapBoardError)
            yield ()
        }
      case reopened: IssueEvent.Reopened                      =>
        withExistingIssue(reopened.issueId) {
          case (workspace, issue) =>
            for
              _ <- moveIssue(workspace.localPath, issue.frontmatter.id, BoardColumn.Backlog)
              _ <- boardRepository
                     .updateIssue(
                       workspace.localPath,
                       issue.frontmatter.id,
                       _.copy(
                         transientState = TransientState.None,
                         failureReason = None,
                         completedAt = None,
                       ),
                     )
                     .mapError(mapBoardError)
            yield ()
        }
      case _: IssueEvent.WorkspaceUnlinked | _: IssueEvent.PromptTemplateUpdated |
           _: IssueEvent.AnalysisAttached | _: IssueEvent.Approved | _: IssueEvent.MergeAttempted |
           _: IssueEvent.CiVerificationResult | _: IssueEvent.ExternalRefLinked | _: IssueEvent.ExternalRefSynced =>
        ZIO.unit

  private def ensureCreatedIssue(created: IssueEvent.Created): IO[PersistenceError, Unit] =
    resolveWorkspaceBoardIssue(created.issueId).flatMap {
      case Some(_) => ZIO.unit
      case None    =>
        workspaceRepository
          .list
          .mapError(mapRepoError)
          .map(_.filter(_.enabled))
          .flatMap {
            case only :: Nil =>
              createBoardIssue(only, created) *> pendingCreatedRef.update(_ - created.issueId)
            case _           =>
              pendingCreatedRef.update(_.updated(created.issueId, created))
          }
    }

  private def ensureCreatedFromPending(linked: IssueEvent.WorkspaceLinked): IO[PersistenceError, Unit] =
    resolveWorkspaceBoardIssue(linked.issueId).flatMap {
      case Some(_) => pendingCreatedRef.update(_ - linked.issueId)
      case None    =>
        for
          pendingOpt <- pendingCreatedRef.get.map(_.get(linked.issueId))
          _          <- pendingOpt match
                          case Some(created) =>
                            workspaceRepository
                              .get(linked.workspaceId)
                              .mapError(mapRepoError)
                              .flatMap {
                                case Some(workspace) =>
                                  createBoardIssue(workspace, created) *> pendingCreatedRef.update(_ - linked.issueId)
                                case None            =>
                                  ZIO.fail(PersistenceError.NotFound("workspace", linked.workspaceId))
                              }
                          case None          =>
                            ZIO.unit
        yield ()
    }

  private def createBoardIssue(
    workspace: Workspace,
    created: IssueEvent.Created,
  ): IO[PersistenceError, Unit] =
    for
      _      <- boardRepository.initBoard(workspace.localPath).mapError(mapBoardError)
      boardId = toBoardIssueId(created.issueId).getOrElse(BoardIssueId(created.issueId.value))
      now     = created.occurredAt
      _      <- boardRepository
                  .createIssue(
                    workspace.localPath,
                    BoardColumn.Backlog,
                    BoardIssue(
                      frontmatter = IssueFrontmatter(
                        id = boardId,
                        title = created.title.trim,
                        priority = toBoardPriority(created.priority),
                        assignedAgent = None,
                        requiredCapabilities = sanitizeList(created.requiredCapabilities),
                        blockedBy = Nil,
                        tags = Nil,
                        acceptanceCriteria = Nil,
                        estimate = None,
                        proofOfWork = Nil,
                        transientState = TransientState.None,
                        branchName = None,
                        failureReason = None,
                        completedAt = None,
                        createdAt = now,
                      ),
                      body = created.description.trim,
                      column = BoardColumn.Backlog,
                      directoryPath = "",
                    ),
                  )
                  .mapError(mapBoardError)
                  .unit
    yield ()

  private def withExistingIssue(
    issueId: IssueId
  )(
    f: (Workspace, BoardIssue) => IO[PersistenceError, Unit]
  ): IO[PersistenceError, Unit] =
    resolveWorkspaceBoardIssue(issueId).flatMap {
      case Some((workspace, issue)) => f(workspace, issue)
      case None                     => ZIO.unit
    }

  private def moveIssue(workspacePath: String, issueId: BoardIssueId, to: BoardColumn): IO[PersistenceError, Unit] =
    boardRepository
      .moveIssue(workspacePath, issueId, to)
      .catchSome { case _: BoardError.ConcurrencyConflict => ZIO.unit }
      .mapError(mapBoardError)
      .unit

  private def resolveWorkspaceBoardIssue(issueId: IssueId): IO[PersistenceError, Option[(Workspace, BoardIssue)]] =
    toBoardIssueId(issueId) match
      case None          => ZIO.none
      case Some(boardId) =>
        workspaceRepository
          .list
          .mapError(mapRepoError)
          .flatMap { workspaces =>
            ZIO
              .foreach(workspaces) { ws =>
                boardRepository.readIssue(ws.localPath, boardId).map(issue => Some(ws -> issue)).catchAll {
                  case _: BoardError.IssueNotFound => ZIO.none
                  case _: BoardError.BoardNotFound => ZIO.none
                  case other                       => ZIO.fail(mapBoardError(other))
                }
              }
              .map(_.collectFirst { case Some(found) => found })
          }

  private def loadAllBoardIssues: IO[PersistenceError, List[AgentIssue]] =
    workspaceRepository
      .list
      .mapError(mapRepoError)
      .flatMap(workspaces =>
        ZIO.foreach(workspaces) { ws =>
          ZIO
            .foreach(boardColumns)(column => boardRepository.listIssues(ws.localPath, column))
            .map(_.flatten.map(issue => boardToDomain(issue, ws.id)))
            .catchAll {
              case _: BoardError.BoardNotFound => ZIO.succeed(Nil)
              case other                       => ZIO.fail(mapBoardError(other))
            }
        }.map(_.flatten)
      )

  private def boardToDomain(issue: BoardIssue, workspaceId: String): AgentIssue =
    val fm                = issue.frontmatter
    val state: IssueState =
      fm.transientState match
        case TransientState.Rework(reason, at)  => IssueState.Rework(at, reason)
        case TransientState.Merging(at)         => IssueState.Merging(at)
        case TransientState.Assigned(agent, at) =>
          IssueState.Assigned(AgentId(agent), at)
        case TransientState.None                =>
          issue.column match
            case BoardColumn.Backlog    => IssueState.Backlog(fm.createdAt)
            case BoardColumn.Todo       => IssueState.Todo(fm.createdAt)
            case BoardColumn.InProgress =>
              val agent = fm.assignedAgent.filter(_.trim.nonEmpty).map(AgentId.apply).getOrElse(AgentId("unassigned"))
              IssueState.InProgress(agent, fm.createdAt)
            case BoardColumn.Review     => IssueState.HumanReview(fm.createdAt)
            case BoardColumn.Done       => IssueState.Done(fm.completedAt.getOrElse(fm.createdAt), "completed")
            case BoardColumn.Archive    =>
              if fm.failureReason.isDefined then
                IssueState.Canceled(
                  fm.completedAt.getOrElse(fm.createdAt),
                  fm.failureReason.getOrElse("canceled"),
                )
              else
                IssueState.Archived(fm.completedAt.getOrElse(fm.createdAt))

    AgentIssue(
      id = IssueId(fm.id.value),
      runId = None,
      conversationId = None,
      title = fm.title,
      description = issue.body,
      issueType = "task",
      priority = boardPriorityToString(fm.priority),
      requiredCapabilities = fm.requiredCapabilities,
      state = state,
      tags = fm.tags,
      blockedBy = fm.blockedBy.map(id => IssueId(id.value)),
      blocking = Nil,
      contextPath = "",
      sourceFolder = "",
      promptTemplate = None,
      acceptanceCriteria = Option.when(fm.acceptanceCriteria.nonEmpty)(fm.acceptanceCriteria.mkString("\n")),
      estimate = fm.estimate.map(boardEstimateToString),
      kaizenSkill = None,
      proofOfWorkRequirements = fm.proofOfWork,
      milestoneRef = None,
      analysisDocIds = Nil,
      mergeConflictFiles = Nil,
      workspaceId = Some(workspaceId),
      externalRef = None,
      externalUrl = None,
    )

  private def issueMatches(filter: IssueFilter, issue: AgentIssue): Boolean =
    val runMatches   = filter.runId.forall(expected => issue.runId.contains(expected))
    val stateMatches = filter.states.isEmpty || filter.states.contains(IssueStateTag.fromState(issue.state))
    val agentMatches = filter.agentId.forall { expected =>
      issue.state match
        case IssueState.Assigned(agent, _)     => agent == expected
        case IssueState.InProgress(agent, _)   => agent == expected
        case IssueState.Completed(agent, _, _) => agent == expected
        case IssueState.Failed(agent, _, _)    => agent == expected
        case _                                 => false
    }
    runMatches && stateMatches && (filter.agentId.isEmpty || agentMatches)

  private def toBoardPriority(priority: String): BoardIssuePriority =
    priority.trim.toLowerCase match
      case "critical" => BoardIssuePriority.Critical
      case "high"     => BoardIssuePriority.High
      case "low"      => BoardIssuePriority.Low
      case _          => BoardIssuePriority.Medium

  private def boardPriorityToString(priority: BoardIssuePriority): String =
    priority match
      case BoardIssuePriority.Critical => "critical"
      case BoardIssuePriority.High     => "high"
      case BoardIssuePriority.Medium   => "medium"
      case BoardIssuePriority.Low      => "low"

  private def toBoardEstimate(estimate: String): Option[BoardIssueEstimate] =
    estimate.trim.toUpperCase match
      case "XS" => Some(BoardIssueEstimate.XS)
      case "S"  => Some(BoardIssueEstimate.S)
      case "M"  => Some(BoardIssueEstimate.M)
      case "L"  => Some(BoardIssueEstimate.L)
      case "XL" => Some(BoardIssueEstimate.XL)
      case _    => None

  private def boardEstimateToString(estimate: BoardIssueEstimate): String =
    estimate match
      case BoardIssueEstimate.XS => "XS"
      case BoardIssueEstimate.S  => "S"
      case BoardIssueEstimate.M  => "M"
      case BoardIssueEstimate.L  => "L"
      case BoardIssueEstimate.XL => "XL"

  private def sanitizeList(values: List[String]): List[String] =
    values.map(_.trim).filter(_.nonEmpty).distinct

  private def splitListField(value: String): List[String] =
    value.split("[\\n,]").toList.map(_.trim).filter(_.nonEmpty)

  private def toBoardIssueId(issueId: IssueId): Option[BoardIssueId] =
    BoardIssueId.fromString(issueId.value).toOption

  private def mapRepoError(error: PersistenceError): PersistenceError =
    error

  private def mapBoardError(error: BoardError): PersistenceError =
    error match
      case BoardError.BoardNotFound(value)            => PersistenceError.QueryFailed("board", s"Not found: $value")
      case BoardError.IssueNotFound(value)            => PersistenceError.QueryFailed("board_issue", s"Not found: $value")
      case BoardError.IssueAlreadyExists(value)       => PersistenceError.QueryFailed("board_issue_exists", value)
      case BoardError.InvalidColumn(value)            => PersistenceError.QueryFailed("board_column", value)
      case BoardError.ParseError(message)             => PersistenceError.QueryFailed("board_parse", message)
      case BoardError.WriteError(path, message)       => PersistenceError.QueryFailed("board_write", s"$path: $message")
      case BoardError.GitOperationFailed(op, message) => PersistenceError.QueryFailed("board_git", s"$op: $message")
      case BoardError.DependencyCycle(issueIds)       => PersistenceError.QueryFailed("board_cycle", issueIds.mkString(","))
      case BoardError.ConcurrencyConflict(message)    => PersistenceError.QueryFailed("board_concurrency", message)

object IssueRepositoryBoard:
  val live: ZLayer[BoardRepository & WorkspaceRepository, Nothing, IssueRepository] =
    ZLayer.fromZIO {
      for
        boardRepo  <- ZIO.service[BoardRepository]
        workspace  <- ZIO.service[WorkspaceRepository]
        historyRef <- Ref.make(Map.empty[IssueId, List[IssueEvent]])
        pendingRef <- Ref.make(Map.empty[IssueId, IssueEvent.Created])
      yield IssueRepositoryBoard(boardRepo, workspace, historyRef, pendingRef)
    }
