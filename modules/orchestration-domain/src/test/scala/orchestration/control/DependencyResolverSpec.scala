package orchestration.control

import java.time.Instant

import zio.*
import zio.test.*

import issues.entity.{ AgentIssue, IssueRepository, IssueState }
import shared.ids.Ids.{ AgentId, IssueId }
import shared.testfixtures.StubIssueRepository

object DependencyResolverSpec extends ZIOSpecDefault:

  private val now: Instant = Instant.parse("2026-04-25T12:00:00Z")

  private def mkTodo(id: String, blockedBy: List[IssueId] = Nil): AgentIssue =
    AgentIssue(
      id                   = IssueId(id),
      runId                = None,
      conversationId       = None,
      title                = id,
      description          = "",
      issueType            = "feature",
      priority             = "medium",
      requiredCapabilities = Nil,
      state                = IssueState.Todo(now),
      tags                 = Nil,
      blockedBy            = blockedBy,
      blocking             = Nil,
      contextPath          = "",
      sourceFolder         = "",
    )

  private def mkDone(id: String): AgentIssue =
    AgentIssue(
      id                   = IssueId(id),
      runId                = None,
      conversationId       = None,
      title                = id,
      description          = "",
      issueType            = "feature",
      priority             = "medium",
      requiredCapabilities = Nil,
      state                = IssueState.Done(now, "ok"),
      tags                 = Nil,
      blockedBy            = Nil,
      blocking             = Nil,
      contextPath          = "",
      sourceFolder         = "",
    )

  private def mkInProgress(id: String): AgentIssue =
    AgentIssue(
      id                   = IssueId(id),
      runId                = None,
      conversationId       = None,
      title                = id,
      description          = "",
      issueType            = "feature",
      priority             = "medium",
      requiredCapabilities = Nil,
      state                = IssueState.InProgress(AgentId("alice"), now),
      tags                 = Nil,
      blockedBy            = Nil,
      blocking             = Nil,
      contextPath          = "",
      sourceFolder         = "",
    )

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("DependencyResolverSpec")(
    test("flags cyclic dependencies and excludes them from readyToDispatch") {
      val i1 = mkTodo("i1", blockedBy = List(IssueId("i2")))
      val i2 = mkTodo("i2", blockedBy = List(IssueId("i1")))
      for
        repo   <- StubIssueRepository.make()
        layer   = ZLayer.succeed[IssueRepository](repo) >>> DependencyResolver.live
        result <- DependencyResolver.readyToDispatch(List(i1, i2)).provideLayer(layer)
      yield assertTrue(result.isEmpty)
    },
    test("readyToDispatch excludes blocked issues with unresolved deps") {
      val done              = mkDone("done")
      val inProgress        = mkInProgress("inProgress")
      val blockedByDone     = mkTodo("blockedByDone", blockedBy = List(done.id))
      val blockedByInProg   = mkTodo("blockedByInProgress", blockedBy = List(inProgress.id))
      val unblocked         = mkTodo("unblocked")
      val issues            = List(done, inProgress, blockedByDone, blockedByInProg, unblocked)
      for
        repo   <- StubIssueRepository.make()
        layer   = ZLayer.succeed[IssueRepository](repo) >>> DependencyResolver.live
        result <- DependencyResolver.readyToDispatch(issues).provideLayer(layer)
      yield assertTrue(
        result.map(_.id).toSet == Set(blockedByDone.id, unblocked.id)
      )
    },
    test("currentReadyToDispatch reads from IssueRepository") {
      val unblockedTodo = mkTodo("unblocked-todo")
      val doneIssue     = mkDone("done-issue")
      for
        repo   <- StubIssueRepository.make(List(unblockedTodo, doneIssue))
        layer   = ZLayer.succeed[IssueRepository](repo) >>> DependencyResolver.live
        result <- DependencyResolver.currentReadyToDispatch.provideLayer(layer)
      yield assertTrue(result.map(_.id) == List(unblockedTodo.id))
    },
    test("dependencyGraph extracts blockedBy as a set per issue") {
      val c = mkTodo("c")
      val b = mkTodo("b", blockedBy = List(c.id))
      val a = mkTodo("a", blockedBy = List(b.id, c.id))
      for
        repo   <- StubIssueRepository.make()
        layer   = ZLayer.succeed[IssueRepository](repo) >>> DependencyResolver.live
        result <- DependencyResolver.dependencyGraph(List(a, b, c)).provideLayer(layer)
      yield assertTrue(
        result(a.id) == Set(b.id, c.id),
        result(b.id) == Set(c.id),
        result(c.id) == Set.empty[IssueId],
      )
    },
  )
