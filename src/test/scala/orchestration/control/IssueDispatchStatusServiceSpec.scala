package orchestration.control

import java.time.Instant

import zio.*
import zio.test.*

import agent.entity.{ Agent, AgentRepository }
import issues.entity.api.DispatchStatusResponse
import issues.entity.{ AgentIssue, IssueEvent, IssueFilter, IssueRepository, IssueState }
import shared.errors.PersistenceError
import shared.ids.Ids.{ AgentId, ConversationId, IssueId, TaskRunId }

object IssueDispatchStatusServiceSpec extends ZIOSpecDefault:

  private val now = Instant.parse("2026-03-13T12:30:00Z")

  private def issue(
    id: String,
    state: IssueState = IssueState.Todo(now),
    requiredCapabilities: List[String] = Nil,
    blockedBy: List[IssueId] = Nil,
  ): AgentIssue =
    AgentIssue(
      id = IssueId(id),
      runId = Some(TaskRunId(s"run-$id")),
      conversationId = Some(ConversationId(s"conv-$id")),
      title = s"Issue $id",
      description = s"Description $id",
      issueType = "task",
      priority = "medium",
      requiredCapabilities = requiredCapabilities,
      state = state,
      tags = Nil,
      blockedBy = blockedBy,
      blocking = Nil,
      contextPath = "",
      sourceFolder = "",
      workspaceId = Some("ws-1"),
    )

  private def agent(name: String, capabilities: List[String]): Agent =
    Agent(
      id = AgentId(name),
      name = name,
      description = s"Agent $name",
      cliTool = "codex",
      capabilities = capabilities,
      defaultModel = None,
      systemPrompt = None,
      maxConcurrentRuns = 1,
      envVars = Map.empty,
      timeout = java.time.Duration.ofMinutes(5),
      enabled = true,
      createdAt = now,
      updatedAt = now,
    )

  final private case class StubIssueRepository(
    issues: List[AgentIssue],
    histories: Map[IssueId, List[IssueEvent]] = Map.empty,
  ) extends IssueRepository:
    override def append(event: IssueEvent): IO[PersistenceError, Unit]             = ZIO.dieMessage("unused")
    override def get(id: IssueId): IO[PersistenceError, AgentIssue]                =
      ZIO.fromOption(issues.find(_.id == id)).orElseFail(PersistenceError.NotFound("issue", id.value))
    override def history(id: IssueId): IO[PersistenceError, List[IssueEvent]]      =
      ZIO.succeed(histories.getOrElse(id, Nil))
    override def list(filter: IssueFilter): IO[PersistenceError, List[AgentIssue]] = ZIO.succeed(issues)
    override def delete(id: IssueId): IO[PersistenceError, Unit]                   = ZIO.dieMessage("unused")

  final private case class StubAgentRepository(agents: List[Agent]) extends AgentRepository:
    override def append(event: _root_.agent.entity.AgentEvent): IO[PersistenceError, Unit] =
      ZIO.dieMessage("unused")
    override def get(id: AgentId): IO[PersistenceError, Agent]                             =
      ZIO.fromOption(agents.find(_.id == id)).orElseFail(PersistenceError.NotFound("agent", id.value))
    override def list(includeDeleted: Boolean): IO[PersistenceError, List[Agent]]          =
      ZIO.succeed(agents)
    override def findByName(name: String): IO[PersistenceError, Option[Agent]]             =
      ZIO.succeed(agents.find(_.name.equalsIgnoreCase(name)))

  final private case class StubAgentPoolManager(available: Map[String, Int]) extends AgentPoolManager:
    override def acquireSlot(agentName: String): IO[PoolError, SlotHandle] =
      ZIO.succeed(SlotHandle(s"slot-$agentName", agentName, now))
    override def releaseSlot(handle: SlotHandle): UIO[Unit]                = ZIO.unit
    override def availableSlots(agentName: String): UIO[Int]               =
      ZIO.succeed(available.getOrElse(agentName, available.getOrElse(agentName.toLowerCase, 0)))
    override def resize(agentName: String, newMax: Int): UIO[Unit]         = ZIO.unit

  private def makeService(
    issues: List[AgentIssue],
    agents: List[Agent],
    available: Map[String, Int],
    histories: Map[IssueId, List[IssueEvent]] = Map.empty,
  ): IssueDispatchStatusService =
    IssueDispatchStatusServiceLive(
      issueRepository = StubIssueRepository(issues, histories),
      agentRepository = StubAgentRepository(agents),
      agentPoolManager = StubAgentPoolManager(available),
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("IssueDispatchStatusServiceSpec")(
      test("reports readyForDispatch when a Todo issue has a matching available agent and no blockers") {
        val readyIssue = issue("1", requiredCapabilities = List("scala"))
        val service    = makeService(
          issues = List(readyIssue),
          agents = List(agent("coder", List("scala", "zio"))),
          available = Map("coder" -> 1),
          histories = Map(
            readyIssue.id -> List(
              IssueEvent.Created(
                readyIssue.id,
                readyIssue.title,
                readyIssue.description,
                readyIssue.issueType,
                "medium",
                now.minusSeconds(10),
              )
            )
          ),
        )
        for
          status <- service.statusFor(IssueId("1"))
        yield assertTrue(
          status == DispatchStatusResponse(readyForDispatch = true)
        )
      },
      test("reports capabilityMismatch when no enabled agent satisfies requiredCapabilities") {
        val unmatched = issue("1", requiredCapabilities = List("scala"))
        val service   = makeService(
          issues = List(unmatched),
          agents = List(agent("writer", List("docs"))),
          available = Map("writer" -> 1),
          histories = Map(
            unmatched.id -> List(
              IssueEvent.Created(
                unmatched.id,
                unmatched.title,
                unmatched.description,
                unmatched.issueType,
                "medium",
                now.minusSeconds(10),
              )
            )
          ),
        )
        for
          status <- service.statusFor(IssueId("1"))
        yield assertTrue(
          status.capabilityMismatch,
          !status.waitingForAgent,
          !status.readyForDispatch,
        )
      },
      test("reports dependencyBlocked with unresolved blockedBy issue ids") {
        val blocker = issue("2", state = IssueState.InProgress(AgentId("coder"), now))
        val blocked = issue("1", blockedBy = List(blocker.id))
        val service = makeService(
          issues = List(blocked, blocker),
          agents = List(agent("coder", List("scala"))),
          available = Map("coder" -> 1),
          histories = Map(
            blocked.id -> List(
              IssueEvent.Created(
                blocked.id,
                blocked.title,
                blocked.description,
                blocked.issueType,
                "medium",
                now.minusSeconds(10),
              ),
              IssueEvent.DependencyLinked(blocked.id, blocker.id, now.minusSeconds(5)),
            )
          ),
        )
        for
          status <- service.statusFor(IssueId("1"))
        yield assertTrue(
          status.dependencyBlocked,
          status.blockedByIds == List("2"),
          !status.readyForDispatch,
        )
      },
      test("reports waitingForAgent when matching agents exist but all slots are exhausted") {
        val waiting = issue("1", requiredCapabilities = List("scala"))
        val service = makeService(
          issues = List(waiting),
          agents = List(agent("coder", List("scala"))),
          available = Map("coder" -> 0),
          histories = Map(
            waiting.id -> List(
              IssueEvent.Created(
                waiting.id,
                waiting.title,
                waiting.description,
                waiting.issueType,
                "medium",
                now.minusSeconds(10),
              )
            )
          ),
        )
        for
          status <- service.statusFor(IssueId("1"))
        yield assertTrue(
          status.waitingForAgent,
          !status.capabilityMismatch,
          !status.readyForDispatch,
        )
      },
      test("reports reworkBoosted when a reworked issue has returned to Todo") {
        val reworked = issue("7", requiredCapabilities = List("scala"))
        val service  = makeService(
          issues = List(reworked),
          agents = List(agent("coder", List("scala"))),
          available = Map("coder" -> 1),
          histories = Map(
            reworked.id -> List(
              IssueEvent.Created(
                reworked.id,
                reworked.title,
                reworked.description,
                reworked.issueType,
                "low",
                now.minusSeconds(20),
              ),
              IssueEvent.MovedToRework(reworked.id, now.minusSeconds(10), "rejected", now.minusSeconds(10)),
              IssueEvent.MovedToTodo(reworked.id, now.minusSeconds(5), now.minusSeconds(5)),
            )
          ),
        )
        for
          status <- service.statusFor(reworked.id)
        yield assertTrue(status.reworkBoosted, status.readyForDispatch)
      },
      test("does not report reworkBoosted after a manual priority override") {
        val reworked = issue("8")
        val service  = makeService(
          issues = List(reworked),
          agents = List(agent("coder", List("scala"))),
          available = Map("coder" -> 1),
          histories = Map(
            reworked.id -> List(
              IssueEvent.Created(
                reworked.id,
                reworked.title,
                reworked.description,
                reworked.issueType,
                "medium",
                now.minusSeconds(30),
              ),
              IssueEvent.MovedToRework(reworked.id, now.minusSeconds(20), "rejected", now.minusSeconds(20)),
              IssueEvent.MetadataUpdated(
                issueId = reworked.id,
                title = reworked.title,
                description = reworked.description,
                issueType = reworked.issueType,
                priority = "low",
                requiredCapabilities = Nil,
                contextPath = "",
                sourceFolder = "",
                occurredAt = now.minusSeconds(10),
              ),
              IssueEvent.MovedToTodo(reworked.id, now.minusSeconds(5), now.minusSeconds(5)),
            )
          ),
        )
        for
          status <- service.statusFor(reworked.id)
        yield assertTrue(!status.reworkBoosted)
      },
    )
