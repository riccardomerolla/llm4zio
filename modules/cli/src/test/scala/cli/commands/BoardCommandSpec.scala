package cli.commands

import java.time.Instant

import zio.*
import zio.test.*
import zio.test.Assertion.*

import board.entity.*
import shared.ids.Ids.BoardIssueId

object BoardCommandSpec extends ZIOSpecDefault:

  def makeFrontmatter(id: String, title: String): IssueFrontmatter =
    IssueFrontmatter(
      id = BoardIssueId(id),
      title = title,
      priority = IssuePriority.Medium,
      assignedAgent = None,
      requiredCapabilities = Nil,
      blockedBy = Nil,
      tags = Nil,
      acceptanceCriteria = Nil,
      estimate = None,
      proofOfWork = Nil,
      transientState = TransientState.None,
      branchName = None,
      failureReason = None,
      completedAt = None,
      createdAt = Instant.EPOCH,
    )

  def makeIssue(id: String, title: String, column: BoardColumn, body: String = ""): BoardIssue =
    BoardIssue(
      frontmatter = makeFrontmatter(id, title),
      body = body,
      column = column,
      directoryPath = s"/tmp/board/${column.folderName}",
    )

  val sampleIssues: List[BoardIssue] = List(
    makeIssue("issue-1", "First todo task", BoardColumn.Todo),
    makeIssue("issue-2", "In progress work", BoardColumn.InProgress),
    makeIssue("issue-3", "Backlog item", BoardColumn.Backlog),
  )

  val sampleBoard: Board = Board(
    workspacePath = "/tmp/workspace",
    columns = sampleIssues.groupBy(_.column),
  )

  final class StubBoardRepo(
    board: Board,
    issues: Map[BoardIssueId, BoardIssue],
  ) extends BoardRepository:

    override def initBoard(workspacePath: String): IO[BoardError, Unit] =
      ZIO.unit

    override def readBoard(workspacePath: String): IO[BoardError, Board] =
      ZIO.succeed(board)

    override def readIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, BoardIssue] =
      issues.get(issueId) match
        case Some(issue) => ZIO.succeed(issue)
        case None        => ZIO.fail(BoardError.IssueNotFound(issueId.value))

    override def createIssue(
      workspacePath: String,
      column: BoardColumn,
      issue: BoardIssue,
    ): IO[BoardError, BoardIssue] =
      ZIO.succeed(issue)

    override def moveIssue(
      workspacePath: String,
      issueId: BoardIssueId,
      toColumn: BoardColumn,
    ): IO[BoardError, BoardIssue] =
      readIssue(workspacePath, issueId)

    override def updateIssue(
      workspacePath: String,
      issueId: BoardIssueId,
      update: IssueFrontmatter => IssueFrontmatter,
    ): IO[BoardError, BoardIssue] =
      readIssue(workspacePath, issueId).map(i => i.copy(frontmatter = update(i.frontmatter)))

    override def deleteIssue(workspacePath: String, issueId: BoardIssueId): IO[BoardError, Unit] =
      ZIO.unit

    override def listIssues(workspacePath: String, column: BoardColumn): IO[BoardError, List[BoardIssue]] =
      ZIO.succeed(issues.values.filter(_.column == column).toList)

    override def invalidateWorkspace(workspacePath: String): UIO[Unit] =
      ZIO.unit

  val stubBoardRepo: BoardRepository =
    val issueMap = sampleIssues.map(i => i.frontmatter.id -> i).toMap
    StubBoardRepo(sampleBoard, issueMap)

  val stubLayer: ZLayer[Any, Nothing, BoardRepository] = ZLayer.succeed(stubBoardRepo)

  def spec = suite("BoardCommand")(
    test("listBoard shows issues grouped by column") {
      for result <- BoardCommand.listBoard("/tmp/workspace").provide(stubLayer)
      yield
        assertTrue(result.contains("BACKLOG")) &&
          assertTrue(result.contains("TODO")) &&
          assertTrue(result.contains("IN_PROGRESS") || result.contains("INPROGRESS") || result.contains(
            "InProgress".toUpperCase
          )) &&
          assertTrue(result.contains("issue-1")) &&
          assertTrue(result.contains("First todo task")) &&
          assertTrue(result.contains("issue-3")) &&
          assertTrue(result.contains("Backlog item"))
    },
    test("showIssue shows issue details") {
      for result <- BoardCommand.showIssue("/tmp/workspace", "issue-2").provide(stubLayer)
      yield
        assertTrue(result.contains("issue-2")) &&
          assertTrue(result.contains("In progress work")) &&
          assertTrue(result.contains("InProgress"))
    },
    test("showIssue returns error for unknown issue") {
      for result <- BoardCommand.showIssue("/tmp/workspace", "does-not-exist").exit.provide(stubLayer)
      yield assert(result)(fails(equalTo(BoardError.IssueNotFound("does-not-exist"))))
    },
  )
