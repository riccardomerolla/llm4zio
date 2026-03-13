package orchestration.control

import java.time.Instant

import zio.*
import zio.test.*

import issues.entity.{ AgentIssue, IssueFilter, IssueRepository, IssueState }
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, ConversationId, IssueId, TaskRunId }

object DependencyResolverSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-13T10:30:00Z")

  private def issue(
    id: String,
    state: IssueState,
    blockedBy: List[IssueId] = Nil,
    tags: List[String] = Nil,
  ): AgentIssue =
    AgentIssue(
      id = IssueId(id),
      runId = Some(TaskRunId(s"run-$id")),
      conversationId = Some(ConversationId(s"conv-$id")),
      title = s"Issue $id",
      description = s"Description for $id",
      issueType = "task",
      priority = "medium",
      requiredCapabilities = Nil,
      state = state,
      tags = tags,
      blockedBy = blockedBy,
      blocking = Nil,
      contextPath = "",
      sourceFolder = "",
    )

  final private case class StubIssueRepository(storedIssues: List[AgentIssue]) extends IssueRepository:
    override def append(event: issues.entity.IssueEvent): IO[PersistenceError, Unit] =
      ZIO.dieMessage("append unused in DependencyResolverSpec")

    override def get(id: IssueId): IO[PersistenceError, AgentIssue] =
      ZIO
        .fromOption(storedIssues.find(_.id == id))
        .orElseFail(PersistenceError.NotFound("issue", id.value))

    override def history(id: IssueId): IO[PersistenceError, List[issues.entity.IssueEvent]] =
      ZIO.succeed(Nil)

    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] =
      ZIO.succeed(storedIssues.slice(filter.offset.max(0), filter.offset.max(0) + filter.limit.max(0)))

    override def delete(id: IssueId): IO[PersistenceError, Unit] =
      ZIO.dieMessage("delete unused in DependencyResolverSpec")

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("DependencyResolverSpec")(
      test("dependencyGraph returns blockedBy edges for all issues") {
        val issues = List(
          issue("1", IssueState.Todo(now), blockedBy = List(IssueId("2"), IssueId("3"))),
          issue("2", IssueState.Done(now, "done")),
          issue("3", IssueState.Completed(AgentId("agent-1"), now, "done")),
        )
        val layer  = ZLayer.succeed[IssueRepository](StubIssueRepository(issues)) >>> DependencyResolver.live

        DependencyResolver.dependencyGraph(issues).provideLayer(layer).map(graph =>
          assertTrue(
            graph == Map(
              IssueId("1") -> Set(IssueId("2"), IssueId("3")),
              IssueId("2") -> Set.empty,
              IssueId("3") -> Set.empty,
            )
          )
        )
      },
      test("readyToDispatch returns Todo issues whose dependencies are resolved") {
        val issues = List(
          issue("1", IssueState.Todo(now), blockedBy = List(IssueId("2"), IssueId("3"))),
          issue("2", IssueState.Done(now, "done")),
          issue("3", IssueState.Skipped(now, "not needed")),
          issue("4", IssueState.Todo(now), blockedBy = List(IssueId("5"))),
          issue("5", IssueState.InProgress(AgentId("agent-2"), now)),
          issue("6", IssueState.Open(now)),
        )
        val layer  = ZLayer.succeed[IssueRepository](StubIssueRepository(issues)) >>> DependencyResolver.live

        DependencyResolver.readyToDispatch(issues).provideLayer(layer).map(ready =>
          assertTrue(ready.map(_.id) == List(IssueId("1")))
        )
      },
      test("readyToDispatch treats missing dependencies as blocked") {
        val issues = List(
          issue("1", IssueState.Todo(now), blockedBy = List(IssueId("999"))),
          issue("2", IssueState.Done(now, "done")),
        )
        val layer  = ZLayer.succeed[IssueRepository](StubIssueRepository(issues)) >>> DependencyResolver.live

        DependencyResolver.readyToDispatch(issues).provideLayer(layer).map(ready => assertTrue(ready.isEmpty))
      },
      test("currentIssues tags cyclic issues and currentReadyToDispatch excludes them") {
        val issues = List(
          issue("1", IssueState.Todo(now), blockedBy = List(IssueId("2"))),
          issue("2", IssueState.Todo(now), blockedBy = List(IssueId("1"))),
          issue("3", IssueState.Todo(now), blockedBy = List(IssueId("4"))),
          issue("4", IssueState.Done(now, "done")),
        )

        val layer = ZLayer.succeed[IssueRepository](StubIssueRepository(issues)) >>> DependencyResolver.live

        for
          annotated <- DependencyResolver.currentIssues.provideLayer(layer)
          ready     <- DependencyResolver.currentReadyToDispatch.provideLayer(layer)
        yield assertTrue(
          annotated.find(_.id == IssueId("1")).exists(_.tags.contains(DependencyResolver.cycleErrorTag)),
          annotated.find(_.id == IssueId("2")).exists(_.tags.contains(DependencyResolver.cycleErrorTag)),
          ready.map(_.id) == List(IssueId("3")),
        )
      },
    )
