package board.control

import zio.*

import board.entity.*
import shared.ids.Ids.BoardIssueId

trait BoardDependencyResolver:
  def dependencyGraph(board: Board): Map[BoardIssueId, Set[BoardIssueId]]
  def readyToDispatch(board: Board): IO[BoardError, List[BoardIssue]]

object BoardDependencyResolver:
  def dependencyGraph(board: Board): ZIO[BoardDependencyResolver, Nothing, Map[BoardIssueId, Set[BoardIssueId]]] =
    ZIO.serviceWith[BoardDependencyResolver](_.dependencyGraph(board))

  def readyToDispatch(board: Board): ZIO[BoardDependencyResolver, BoardError, List[BoardIssue]] =
    ZIO.serviceWithZIO[BoardDependencyResolver](_.readyToDispatch(board))

  val live: ULayer[BoardDependencyResolver] = ZLayer.succeed(BoardDependencyResolverLive())

final private case class BoardDependencyResolverLive() extends BoardDependencyResolver:
  override def dependencyGraph(board: Board): Map[BoardIssueId, Set[BoardIssueId]] =
    board.columns.values.flatten.iterator.map(issue => issue.frontmatter.id -> issue.frontmatter.blockedBy.toSet).toMap

  override def readyToDispatch(board: Board): IO[BoardError, List[BoardIssue]] =
    val issues     = board.columns.values.flatten.toList
    val issueById  = issues.iterator.map(issue => issue.frontmatter.id -> issue).toMap
    val graph      = dependencyGraph(board)
    val cyclicDeps = findCyclicIssues(graph)

    if cyclicDeps.nonEmpty then
      ZIO.fail(BoardError.DependencyCycle(cyclicDeps.toList.map(_.value).sorted))
    else
      ZIO.succeed {
        issues
          .filter(isTodo)
          .filter(issue => issue.frontmatter.blockedBy.forall(depId => issueById.get(depId).exists(isResolved)))
          .sortBy(issue => (priorityRank(issue.frontmatter.priority), issue.frontmatter.createdAt))
      }

  private def isTodo(issue: BoardIssue): Boolean =
    issue.column == BoardColumn.Todo

  private def isResolved(issue: BoardIssue): Boolean =
    issue.column == BoardColumn.Done || issue.column == BoardColumn.Archive

  private def priorityRank(priority: IssuePriority): Int =
    priority match
      case IssuePriority.Critical => 0
      case IssuePriority.High     => 1
      case IssuePriority.Medium   => 2
      case IssuePriority.Low      => 3

  private def findCyclicIssues(graph: Map[BoardIssueId, Set[BoardIssueId]]): Set[BoardIssueId] =
    enum VisitState:
      case Visiting
      case Visited

    def visit(
      nodeId: BoardIssueId,
      seen: Map[BoardIssueId, VisitState],
      stack: List[BoardIssueId],
      cycles: Set[BoardIssueId],
    ): (Map[BoardIssueId, VisitState], Set[BoardIssueId]) =
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

    graph.keys.foldLeft((Map.empty[BoardIssueId, VisitState], Set.empty[BoardIssueId])) {
      case ((seen, cycles), nodeId) => visit(nodeId, seen, Nil, cycles)
    }._2
