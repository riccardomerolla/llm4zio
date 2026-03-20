package board.control

import java.time.Instant

import zio.*
import zio.test.*

import board.entity.*
import shared.ids.Ids.BoardIssueId

object BoardDependencyResolverSpec extends ZIOSpecDefault:
  private val resolver = BoardDependencyResolverLive()

  private def issue(
    id: String,
    column: BoardColumn,
    priority: IssuePriority,
    createdAt: Instant,
    blockedBy: List[String] = Nil,
  ): BoardIssue =
    BoardIssue(
      frontmatter = IssueFrontmatter(
        id = BoardIssueId(id),
        title = s"Issue $id",
        priority = priority,
        assignedAgent = None,
        requiredCapabilities = Nil,
        blockedBy = blockedBy.map(BoardIssueId.apply),
        tags = Nil,
        acceptanceCriteria = Nil,
        estimate = None,
        proofOfWork = Nil,
        transientState = TransientState.None,
        branchName = None,
        failureReason = None,
        completedAt = None,
        createdAt = createdAt,
      ),
      body = s"Body for $id",
      column = column,
      directoryPath = s"/tmp/$id",
    )

  private def board(issues: List[BoardIssue]): Board =
    Board(
      workspacePath = "/tmp/ws",
      columns = BoardColumn.values.map(column => column -> issues.filter(_.column == column)).toMap,
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("BoardDependencyResolverSpec")(
      test("dependencyGraph reflects blockedBy edges") {
        val b = board(
          List(
            issue("a", BoardColumn.Todo, IssuePriority.Medium, Instant.parse("2026-03-20T10:00:00Z"), List("b", "c")),
            issue("b", BoardColumn.Done, IssuePriority.Low, Instant.parse("2026-03-20T09:00:00Z")),
            issue("c", BoardColumn.Archive, IssuePriority.Low, Instant.parse("2026-03-20T08:00:00Z")),
          )
        )

        assertTrue(
          resolver.dependencyGraph(b) == Map(
            BoardIssueId("a") -> Set(BoardIssueId("b"), BoardIssueId("c")),
            BoardIssueId("b") -> Set.empty,
            BoardIssueId("c") -> Set.empty,
          )
        )
      },
      test("readyToDispatch includes only todo issues with dependencies in done/archive") {
        val b = board(
          List(
            issue(
              "critical-new",
              BoardColumn.Todo,
              IssuePriority.Critical,
              Instant.parse("2026-03-20T11:00:00Z"),
              List("done-1"),
            ),
            issue(
              "critical-old",
              BoardColumn.Todo,
              IssuePriority.Critical,
              Instant.parse("2026-03-20T10:00:00Z"),
              List("arch-1"),
            ),
            issue(
              "high",
              BoardColumn.Todo,
              IssuePriority.High,
              Instant.parse("2026-03-20T09:00:00Z"),
              List("done-1"),
            ),
            issue(
              "blocked",
              BoardColumn.Todo,
              IssuePriority.Low,
              Instant.parse("2026-03-20T08:00:00Z"),
              List("inprog-1"),
            ),
            issue("done-1", BoardColumn.Done, IssuePriority.Low, Instant.parse("2026-03-20T07:00:00Z")),
            issue("arch-1", BoardColumn.Archive, IssuePriority.Low, Instant.parse("2026-03-20T06:00:00Z")),
            issue("inprog-1", BoardColumn.InProgress, IssuePriority.Low, Instant.parse("2026-03-20T05:00:00Z")),
          )
        )

        resolver.readyToDispatch(b).map(ready =>
          assertTrue(
            ready.map(_.frontmatter.id.value) == List("critical-old", "critical-new", "high")
          )
        )
      },
      test("readyToDispatch treats missing dependencies as unresolved") {
        val b = board(
          List(
            issue(
              "todo-1",
              BoardColumn.Todo,
              IssuePriority.Medium,
              Instant.parse("2026-03-20T10:00:00Z"),
              List("missing"),
            )
          )
        )

        resolver.readyToDispatch(b).map(ready => assertTrue(ready.isEmpty))
      },
      test("readyToDispatch fails with DependencyCycle for cyclic graph") {
        val b = board(
          List(
            issue(
              "a",
              BoardColumn.Todo,
              IssuePriority.Medium,
              Instant.parse("2026-03-20T10:00:00Z"),
              List("b"),
            ),
            issue(
              "b",
              BoardColumn.Todo,
              IssuePriority.Medium,
              Instant.parse("2026-03-20T11:00:00Z"),
              List("a"),
            ),
          )
        )

        resolver.readyToDispatch(b).exit.map {
          case Exit.Failure(cause) =>
            cause.failureOption match
              case Some(BoardError.DependencyCycle(ids)) => assertTrue(ids.toSet == Set("a", "b"))
              case _                                     => assertTrue(false)
          case Exit.Success(_)     => assertTrue(false)
        }
      },
    )
