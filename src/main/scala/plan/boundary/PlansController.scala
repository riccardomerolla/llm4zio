package plan.boundary

import zio.*
import zio.http.*

import issues.entity.{ IssueFilter, IssueRepository }
import plan.entity.PlanRepository
import shared.errors.PersistenceError
import shared.ids.Ids.PlanId
import shared.web.{ PlanListItem, PlansView }
import specification.entity.SpecificationRepository

object PlansController:

  def routes(
    planRepository: PlanRepository,
    specificationRepository: SpecificationRepository,
    issueRepository: IssueRepository,
  ): Routes[Any, Response]                =
    Routes(
      Method.GET / "plans"                -> handler { (_: Request) =>
        listPage(planRepository).catchAll(error => ZIO.succeed(persistErr(error)))
      },
      Method.GET / "plans" / string("id") -> handler { (id: String, _: Request) =>
        detailPage(id, planRepository, specificationRepository, issueRepository)
          .catchAll(error => ZIO.succeed(persistErr(error)))
      },
    )

  private def listPage(planRepository: PlanRepository): IO[PersistenceError, Response] =
    planRepository.list.map { plans =>
      htmlResponse(
        PlansView.page(
          plans.map(plan =>
            PlanListItem(
              id = plan.id.value,
              summary = plan.summary,
              status = plan.status,
              version = plan.version,
              specificationId = plan.specificationId.map(_.value),
              linkedIssueCount = plan.linkedIssueIds.size,
              workspaceId = plan.workspaceId,
              updatedAt = plan.updatedAt,
            )
          )
        )
      )
    }

  private def detailPage(
    id: String,
    planRepository: PlanRepository,
    specificationRepository: SpecificationRepository,
    issueRepository: IssueRepository,
  ): IO[PersistenceError, Response] =
    for
      plan          <- planRepository.get(PlanId(id))
      specification <- ZIO.foreach(plan.specificationId)(specificationRepository.get)
      linkedIssues  <- issueRepository
                         .list(IssueFilter(limit = Int.MaxValue))
                         .map(_.filter(issue => plan.linkedIssueIds.contains(issue.id)))
    yield htmlResponse(PlansView.detailPage(plan, specification, linkedIssues))

  private def htmlResponse(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def persistErr(error: PersistenceError): Response =
    error match
      case PersistenceError.NotFound(entity, entityId)    =>
        Response.text(s"$entity with id $entityId not found").status(Status.NotFound)
      case PersistenceError.StoreUnavailable(message)     =>
        Response.text(s"Database unavailable: $message").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)         =>
        Response.text(s"Plan query failed: $cause").status(Status.BadRequest)
      case PersistenceError.SerializationFailed(_, cause) =>
        Response.text(s"Plan serialization failed: $cause").status(Status.InternalServerError)
