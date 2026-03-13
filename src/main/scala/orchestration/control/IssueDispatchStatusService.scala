package orchestration.control

import zio.*

import agent.entity.{ Agent, AgentRepository }
import issues.entity.api.DispatchStatusResponse
import issues.entity.{ AgentIssue, IssueEvent, IssueFilter, IssueRepository, IssueState }
import shared.errors.PersistenceError
import shared.ids.Ids.IssueId

trait IssueDispatchStatusService:
  def statusFor(issueId: IssueId): IO[PersistenceError, DispatchStatusResponse]
  def statusesFor(issueIds: List[IssueId]): IO[PersistenceError, Map[IssueId, DispatchStatusResponse]]

object IssueDispatchStatusService:
  def statusFor(issueId: IssueId): ZIO[IssueDispatchStatusService, PersistenceError, DispatchStatusResponse] =
    ZIO.serviceWithZIO[IssueDispatchStatusService](_.statusFor(issueId))

  def statusesFor(
    issueIds: List[IssueId]
  ): ZIO[IssueDispatchStatusService, PersistenceError, Map[IssueId, DispatchStatusResponse]] =
    ZIO.serviceWithZIO[IssueDispatchStatusService](_.statusesFor(issueIds))

  val live: ZLayer[IssueRepository & AgentRepository & AgentPoolManager, Nothing, IssueDispatchStatusService] =
    ZLayer.fromFunction(IssueDispatchStatusServiceLive.apply)

final private case class IssueDispatchStatusServiceLive(
  issueRepository: IssueRepository,
  agentRepository: AgentRepository,
  agentPoolManager: AgentPoolManager,
) extends IssueDispatchStatusService:

  override def statusFor(issueId: IssueId): IO[PersistenceError, DispatchStatusResponse] =
    for
      allIssues <- currentIssues
      issue     <- ZIO
                     .fromOption(allIssues.find(_.id == issueId))
                     .orElseFail(PersistenceError.NotFound("issue", issueId.value))
      allAgents <- agentRepository.list()
      history   <- issueRepository.history(issueId)
      status    <- computeStatus(issue, allIssues, allAgents, history)
    yield status

  override def statusesFor(issueIds: List[IssueId]): IO[PersistenceError, Map[IssueId, DispatchStatusResponse]] =
    if issueIds.isEmpty then ZIO.succeed(Map.empty)
    else
      for
        allIssues <- currentIssues
        allAgents <- agentRepository.list()
        statuses  <- ZIO.foreach(issueIds.distinct) { issueId =>
                       allIssues.find(_.id == issueId) match
                         case Some(issue) =>
                           issueRepository.history(issueId).flatMap(history =>
                             computeStatus(issue, allIssues, allAgents, history).map(issueId -> _)
                           )
                         case None        => ZIO.succeed(issueId -> DispatchStatusResponse())
                     }
      yield statuses.toMap

  private def currentIssues: IO[PersistenceError, List[AgentIssue]] =
    issueRepository.list(IssueFilter())

  private def computeStatus(
    issue: AgentIssue,
    allIssues: List[AgentIssue],
    allAgents: List[Agent],
    history: List[IssueEvent],
  ): IO[PersistenceError, DispatchStatusResponse] =
    issue.state match
      case IssueState.Todo(_) =>
        val blockedByIds = unresolvedDependencies(issue, allIssues).map(_.id.value)
        val matching     = matchingAgents(issue, allAgents)
        val boost        = ReworkPriority.boostStatus(history)
        for
          availableCounts <- ZIO.foreach(matching)(agent =>
                               agentPoolManager.availableSlots(agent.name).map(available => agent.name -> available)
                             )
          waitingForAgent  = matching.nonEmpty && blockedByIds.isEmpty && availableCounts.forall(_._2 <= 0)
          capabilityMiss   = matching.isEmpty
          ready            = !waitingForAgent && !capabilityMiss && blockedByIds.isEmpty
        yield DispatchStatusResponse(
          waitingForAgent = waitingForAgent,
          capabilityMismatch = capabilityMiss,
          dependencyBlocked = blockedByIds.nonEmpty,
          blockedByIds = blockedByIds,
          readyForDispatch = ready,
          reworkBoosted = boost.active,
        )
      case _                  =>
        ZIO.succeed(DispatchStatusResponse())

  private def unresolvedDependencies(issue: AgentIssue, allIssues: List[AgentIssue]): List[AgentIssue] =
    val issuesById = allIssues.map(other => other.id -> other).toMap
    issue.blockedBy.flatMap(issuesById.get).filterNot(isResolved)

  private def isResolved(issue: AgentIssue): Boolean =
    issue.state match
      case IssueState.Done(_, _)         => true
      case IssueState.Completed(_, _, _) => true
      case IssueState.Skipped(_, _)      => true
      case _                             => false

  private def matchingAgents(issue: AgentIssue, allAgents: List[Agent]): List[Agent] =
    val required = issue.requiredCapabilities.map(_.trim.toLowerCase).filter(_.nonEmpty).distinct
    allAgents.filter(agent =>
      agent.enabled && {
        val caps = agent.capabilities.map(_.trim.toLowerCase).filter(_.nonEmpty).toSet
        required.isEmpty || required.forall(caps.contains)
      }
    )
