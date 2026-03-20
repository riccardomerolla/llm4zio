package board.control

import java.time.{ Duration, Instant }

import zio.*

import board.entity.*
import shared.ids.Ids.BoardIssueId
import workspace.control.GitService

final case class CachedBoard(board: Board, gitHeadSha: String, cachedAt: Instant)

final case class BoardCache(
  underlying: BoardRepository,
  gitService: GitService,
  cacheRef: Ref[Map[String, CachedBoard]],
  ttl: Duration,
) extends BoardRepository:
  override def initBoard(workspacePath: String): IO[BoardError, Unit] =
    underlying.initBoard(workspacePath) <* invalidate(workspacePath)

  override def readBoard(workspacePath: String): IO[BoardError, Board] =
    for
      now        <- Clock.instant
      currentSha <- gitService.headSha(workspacePath).mapError(err =>
                      BoardError.GitOperationFailed("git rev-parse HEAD", err.toString)
                    )
      cached     <- cacheRef.get.map(_.get(workspacePath))
      board      <- cached match
                      case Some(value) if isFresh(value, currentSha, now) => ZIO.succeed(value.board)
                      case _                                              =>
                        for
                          loaded <- underlying.readBoard(workspacePath)
                          _      <- cacheRef.update(_.updated(workspacePath, CachedBoard(loaded, currentSha, now)))
                        yield loaded
    yield board

  override def readIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, BoardIssue] =
    readBoard(workspacePath).flatMap(board =>
      board.columns.values.flatten.find(_.frontmatter.id == issueId) match
        case Some(issue) => ZIO.succeed(issue)
        case None        => underlying.readIssue(workspacePath, issueId)
    )

  override def createIssue(workspacePath: String, column: BoardColumn, issue: BoardIssue): IO[BoardError, BoardIssue] =
    underlying.createIssue(workspacePath, column, issue) <* invalidate(workspacePath)

  override def moveIssue(workspacePath: String, issueId: BoardIssueId, toColumn: BoardColumn)
    : IO[BoardError, BoardIssue] =
    underlying.moveIssue(workspacePath, issueId, toColumn) <* invalidate(workspacePath)

  override def updateIssue(
    workspacePath: String,
    issueId: BoardIssueId,
    update: IssueFrontmatter => IssueFrontmatter,
  ): IO[BoardError, BoardIssue] =
    underlying.updateIssue(workspacePath, issueId, update) <* invalidate(workspacePath)

  override def deleteIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit] =
    underlying.deleteIssue(workspacePath, issueId) <* invalidate(workspacePath)

  override def listIssues(workspacePath: String, column: BoardColumn): IO[BoardError, List[BoardIssue]] =
    readBoard(workspacePath).flatMap(board =>
      board.columns.get(column) match
        case Some(issues) => ZIO.succeed(issues)
        case None         => underlying.listIssues(workspacePath, column)
    )

  override def invalidateWorkspace(workspacePath: String): UIO[Unit] =
    invalidate(workspacePath)

  private def invalidate(workspacePath: String): UIO[Unit] =
    cacheRef.update(_ - workspacePath).unit

  private def isFresh(cachedBoard: CachedBoard, currentSha: String, now: Instant): Boolean =
    cachedBoard.gitHeadSha == currentSha && !isExpired(cachedBoard.cachedAt, now)

  private def isExpired(cachedAt: Instant, now: Instant): Boolean =
    Duration.between(cachedAt, now).compareTo(ttl) > 0

object BoardCache:
  val defaultTtl: Duration = Duration.ofSeconds(30)

  def make(
    underlying: BoardRepository,
    gitService: GitService,
    ttl: Duration = defaultTtl,
  ): UIO[BoardCache] =
    Ref.make(Map.empty[String, CachedBoard]).map(ref => BoardCache(underlying, gitService, ref, ttl))

  def live(ttl: Duration = defaultTtl): URLayer[BoardRepository & GitService, BoardRepository] =
    ZLayer.fromZIO {
      for
        underlying <- ZIO.service[BoardRepository]
        gitService <- ZIO.service[GitService]
        cache      <- BoardCache.make(underlying, gitService, ttl)
      yield cache
    }
