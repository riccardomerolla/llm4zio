package board.entity

import zio.*

import shared.ids.Ids.BoardIssueId

trait BoardRepository:
  def initBoard(workspacePath: String): IO[BoardError, Unit]
  def readBoard(workspacePath: String): IO[BoardError, Board]
  def readIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, BoardIssue]
  def createIssue(workspacePath: String, column: BoardColumn, issue: BoardIssue): IO[BoardError, BoardIssue]
  def moveIssue(workspacePath: String, issueId: BoardIssueId, toColumn: BoardColumn): IO[BoardError, BoardIssue]
  def updateIssue(
    workspacePath: String,
    issueId: BoardIssueId,
    update: IssueFrontmatter => IssueFrontmatter,
  ): IO[BoardError, BoardIssue]
  def deleteIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit]
  def listIssues(workspacePath: String, column: BoardColumn): IO[BoardError, List[BoardIssue]]
  def invalidateWorkspace(workspacePath: String): UIO[Unit]

object BoardRepository:
  def initBoard(workspacePath: String): ZIO[BoardRepository, BoardError, Unit] =
    ZIO.serviceWithZIO[BoardRepository](_.initBoard(workspacePath))

  def readBoard(workspacePath: String): ZIO[BoardRepository, BoardError, Board] =
    ZIO.serviceWithZIO[BoardRepository](_.readBoard(workspacePath))

  def readIssue(workspacePath: String, issueId: BoardIssueId): ZIO[BoardRepository, BoardError, BoardIssue] =
    ZIO.serviceWithZIO[BoardRepository](_.readIssue(workspacePath, issueId))

  def createIssue(
    workspacePath: String,
    column: BoardColumn,
    issue: BoardIssue,
  ): ZIO[BoardRepository, BoardError, BoardIssue] =
    ZIO.serviceWithZIO[BoardRepository](_.createIssue(workspacePath, column, issue))

  def moveIssue(
    workspacePath: String,
    issueId: BoardIssueId,
    toColumn: BoardColumn,
  ): ZIO[BoardRepository, BoardError, BoardIssue] =
    ZIO.serviceWithZIO[BoardRepository](_.moveIssue(workspacePath, issueId, toColumn))

  def updateIssue(
    workspacePath: String,
    issueId: BoardIssueId,
    update: IssueFrontmatter => IssueFrontmatter,
  ): ZIO[BoardRepository, BoardError, BoardIssue] =
    ZIO.serviceWithZIO[BoardRepository](_.updateIssue(workspacePath, issueId, update))

  def deleteIssue(workspacePath: String, issueId: BoardIssueId): ZIO[BoardRepository, BoardError, Unit] =
    ZIO.serviceWithZIO[BoardRepository](_.deleteIssue(workspacePath, issueId))

  def listIssues(workspacePath: String, column: BoardColumn): ZIO[BoardRepository, BoardError, List[BoardIssue]] =
    ZIO.serviceWithZIO[BoardRepository](_.listIssues(workspacePath, column))

  def invalidateWorkspace(workspacePath: String): ZIO[BoardRepository, Nothing, Unit] =
    ZIO.serviceWithZIO[BoardRepository](_.invalidateWorkspace(workspacePath))
