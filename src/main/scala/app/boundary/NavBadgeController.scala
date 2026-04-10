package app.boundary

import zio.*
import zio.http.*

import decision.control.DecisionInbox
import decision.entity.{ DecisionFilter, DecisionStatus }
import issues.entity.{ IssueFilter, IssueRepository, IssueStateTag }
import shared.errors.PersistenceError

object NavBadgeController:

  def routes(
    decisionInbox: DecisionInbox,
    issueRepository: IssueRepository,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "nav" / "badges" / "decisions" -> handler { (_: Request) =>
        pendingDecisionCount(decisionInbox)
          .map(count => badgeResponse(count))
          .catchAll(error => ZIO.succeed(errorResponse(error.toString)))
      },
      Method.GET / "nav" / "badges" / "board"     -> handler { (_: Request) =>
        inProgressBoardCount(issueRepository)
          .map(count => badgeResponse(count))
          .catchAll(error => ZIO.succeed(errorResponse(error.toString)))
      },
    )

  private def pendingDecisionCount(decisionInbox: DecisionInbox): IO[PersistenceError, Int] =
    decisionInbox
      .list(DecisionFilter(limit = Int.MaxValue))
      .map(_.count(_.status == DecisionStatus.Pending))

  private def inProgressBoardCount(issueRepository: IssueRepository): IO[PersistenceError, Int] =
    issueRepository
      .list(IssueFilter(states = Set(IssueStateTag.InProgress), limit = Int.MaxValue))
      .map(_.size)

  private def badgeResponse(count: Int): Response =
    val body =
      if count > 0 then
        s"""<span class="ml-auto inline-flex min-w-[1.25rem] items-center justify-center rounded-full bg-amber-500/20 px-1.5 py-0.5 text-[10px] font-semibold text-amber-200">$count</span>"""
      else ""
    Response.text(body).contentType(MediaType.text.html)

  private def errorResponse(message: String): Response =
    Response.text(
      s"""<span class="hidden" data-sidebar-badge-error="$message"></span>"""
    ).contentType(MediaType.text.html)
