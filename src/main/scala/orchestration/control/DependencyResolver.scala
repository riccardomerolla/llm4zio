package orchestration.control

import zio.*

import issues.entity.{ AgentIssue, IssueFilter, IssueRepository, IssueState }
import shared.errors.PersistenceError
import shared.ids.Ids.IssueId

trait DependencyResolver:
  def dependencyGraph(issues: List[AgentIssue]): Map[IssueId, Set[IssueId]]
  def readyToDispatch(issues: List[AgentIssue]): List[AgentIssue]
  def currentIssues: IO[PersistenceError, List[AgentIssue]]
  def currentReadyToDispatch: IO[PersistenceError, List[AgentIssue]]

object DependencyResolver:
  val cycleErrorTag: String = "error:dependency-cycle"

  def dependencyGraph(issues: List[AgentIssue]): ZIO[DependencyResolver, Nothing, Map[IssueId, Set[IssueId]]] =
    ZIO.serviceWith[DependencyResolver](_.dependencyGraph(issues))

  def readyToDispatch(issues: List[AgentIssue]): ZIO[DependencyResolver, Nothing, List[AgentIssue]] =
    ZIO.serviceWith[DependencyResolver](_.readyToDispatch(issues))

  def currentIssues: ZIO[DependencyResolver, PersistenceError, List[AgentIssue]] =
    ZIO.serviceWithZIO[DependencyResolver](_.currentIssues)

  def currentReadyToDispatch: ZIO[DependencyResolver, PersistenceError, List[AgentIssue]] =
    ZIO.serviceWithZIO[DependencyResolver](_.currentReadyToDispatch)

  val live: ZLayer[IssueRepository, Nothing, DependencyResolver] =
    ZLayer.fromFunction(DependencyResolverLive.apply)

final private case class DependencyResolverLive(issueRepository: IssueRepository) extends DependencyResolver:
  override def dependencyGraph(issues: List[AgentIssue]): Map[IssueId, Set[IssueId]] =
    issues.iterator.map(issue => issue.id -> issue.blockedBy.toSet).toMap

  override def readyToDispatch(issues: List[AgentIssue]): List[AgentIssue] =
    val normalized = annotateCycles(issues)
    val issueById  = normalized.iterator.map(issue => issue.id -> issue).toMap

    normalized.filter { issue =>
      isTodo(issue) &&
      !issue.tags.contains(DependencyResolver.cycleErrorTag) &&
      issue.blockedBy.forall(depId => issueById.get(depId).exists(isDependencyResolved))
    }

  override def currentIssues: IO[PersistenceError, List[AgentIssue]] =
    issueRepository.list(IssueFilter(limit = Int.MaxValue)).map(annotateCycles)

  override def currentReadyToDispatch: IO[PersistenceError, List[AgentIssue]] =
    currentIssues.map(readyToDispatch)

  private def annotateCycles(issues: List[AgentIssue]): List[AgentIssue] =
    val cyclicIds = findCyclicIssues(dependencyGraph(issues))
    issues.map { issue =>
      if cyclicIds.contains(issue.id) then
        issue.copy(tags = (issue.tags :+ DependencyResolver.cycleErrorTag).distinct)
      else issue
    }

  private def isTodo(issue: AgentIssue): Boolean =
    issue.state match
      case _: IssueState.Todo => true
      case _                  => false

  private def isDependencyResolved(issue: AgentIssue): Boolean =
    issue.state match
      case _: IssueState.Done      => true
      case _: IssueState.Completed => true
      case _: IssueState.Skipped   => true
      case _                       => false

  private def findCyclicIssues(graph: Map[IssueId, Set[IssueId]]): Set[IssueId] =
    enum VisitState:
      case Visiting
      case Visited

    def visit(
      nodeId: IssueId,
      seen: Map[IssueId, VisitState],
      stack: List[IssueId],
      cycles: Set[IssueId],
    ): (Map[IssueId, VisitState], Set[IssueId]) =
      seen.get(nodeId) match
        case Some(VisitState.Visited)  => (seen, cycles)
        case Some(VisitState.Visiting) =>
          val cyclePath = nodeId :: stack.takeWhile(_ != nodeId)
          (seen, cycles ++ cyclePath)
        case None                      =>
          val withVisiting             = seen.updated(nodeId, VisitState.Visiting)
          val (afterDeps, foundCycles) = graph.getOrElse(nodeId, Set.empty).foldLeft((withVisiting, cycles)) {
            case ((stateAcc, cycleAcc), depId) =>
              visit(depId, stateAcc, nodeId :: stack, cycleAcc)
          }
          (afterDeps.updated(nodeId, VisitState.Visited), foundCycles)

    graph.keys.foldLeft((Map.empty[IssueId, VisitState], Set.empty[IssueId])) {
      case ((seen, cycles), nodeId) => visit(nodeId, seen, Nil, cycles)
    }._2
