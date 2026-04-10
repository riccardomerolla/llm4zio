package specification.boundary

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import zio.*
import zio.http.*

import issues.entity.{ AgentIssue, IssueFilter, IssueRepository }
import shared.errors.PersistenceError
import shared.ids.Ids.SpecificationId
import specification.entity.*

trait SpecificationsController:
  def routes: Routes[Any, Response]

object SpecificationsController:

  def routes: ZIO[SpecificationsController, Nothing, Routes[Any, Response]] =
    ZIO.serviceWith[SpecificationsController](_.routes)

  val live: ZLayer[SpecificationRepository & IssueRepository, Nothing, SpecificationsController] =
    ZLayer {
      for
        specificationRepository <- ZIO.service[SpecificationRepository]
        issueRepository         <- ZIO.service[IssueRepository]
      yield make(specificationRepository, issueRepository)
    }

  def make(
    specificationRepository: SpecificationRepository,
    issueRepository: IssueRepository,
  ): SpecificationsController =
    new SpecificationsController:
      override val routes: Routes[Any, Response] = Routes(
        Method.GET / "specifications"                             -> handler { (_: Request) =>
          listPage(specificationRepository).catchAll(error => ZIO.succeed(persistErr(error)))
        },
        Method.GET / "specifications" / string("id")              -> handler { (id: String, req: Request) =>
          detailPage(id, req, specificationRepository, issueRepository).catchAll(error =>
            ZIO.succeed(persistErr(error))
          )
        },
        Method.GET / "specifications" / string("id") / "diff"     -> handler { (id: String, req: Request) =>
          diffPage(id, req, specificationRepository).catchAll(error => ZIO.succeed(persistErr(error)))
        },
        Method.POST / "specifications" / string("id") / "revise"  -> handler { (id: String, req: Request) =>
          revise(id, req, specificationRepository).catchAll(error => ZIO.succeed(persistErr(error)))
        },
        Method.POST / "specifications" / string("id") / "approve" -> handler { (id: String, req: Request) =>
          approve(id, specificationRepository).catchAll(error => ZIO.succeed(persistErr(error)))
        },
      )

  private def listPage(specificationRepository: SpecificationRepository): IO[PersistenceError, Response] =
    specificationRepository.list.map { specifications =>
      htmlResponse(
        SpecificationsView.page(
          specifications.map(spec =>
            SpecificationListItem(
              id = spec.id.value,
              title = spec.title,
              status = spec.status,
              version = spec.version,
              linkedPlanRef = spec.linkedPlanRef,
              linkedIssueCount = spec.linkedIssueIds.size,
              updatedAt = spec.updatedAt,
            )
          )
        )
      )
    }

  private def detailPage(
    id: String,
    req: Request,
    specificationRepository: SpecificationRepository,
    issueRepository: IssueRepository,
  ): IO[PersistenceError, Response] =
    for
      specification <- specificationRepository.get(SpecificationId(id))
      linkedIssues  <- loadLinkedIssues(specification, issueRepository)
      diff          <- loadRequestedOrDefaultDiff(specification, req, specificationRepository)
    yield htmlResponse(SpecificationsView.detailPage(specification, linkedIssues, diff))

  private def diffPage(
    id: String,
    req: Request,
    specificationRepository: SpecificationRepository,
  ): IO[PersistenceError, Response] =
    for
      specification <- specificationRepository.get(SpecificationId(id))
      diff          <- loadRequiredDiff(specification, req, specificationRepository)
    yield htmlResponse(SpecificationsView.diffPage(specification, diff))

  private def revise(
    id: String,
    req: Request,
    specificationRepository: SpecificationRepository,
  ): IO[PersistenceError, Response] =
    for
      specification <- specificationRepository.get(SpecificationId(id))
      form          <- parseForm(req)
      title         <- required(form, "title")
      content       <- required(form, "content")
      now           <- Clock.instant
      author         = SpecificationAuthor(
                         kind = SpecificationAuthorKind.Human,
                         id = "web",
                         displayName = "Web Editor",
                       )
      _             <- ZIO.when(title != specification.title || content != specification.content) {
                         specificationRepository.append(
                           SpecificationEvent.Revised(
                             specificationId = specification.id,
                             version = specification.version + 1,
                             title = title,
                             beforeContent = specification.content,
                             afterContent = content,
                             author = author,
                             status = SpecificationStatus.InRefinement,
                             linkedPlanRef = specification.linkedPlanRef,
                             occurredAt = now,
                           )
                         )
                       }
    yield redirect(s"/specifications/$id")

  private def approve(
    id: String,
    specificationRepository: SpecificationRepository,
  ): IO[PersistenceError, Response] =
    for
      specification <- specificationRepository.get(SpecificationId(id))
      now           <- Clock.instant
      _             <- specificationRepository.append(
                         SpecificationEvent.Approved(
                           specificationId = specification.id,
                           approvedBy = SpecificationAuthor(
                             kind = SpecificationAuthorKind.Human,
                             id = "web",
                             displayName = "Web Reviewer",
                           ),
                           occurredAt = now,
                         )
                       )
    yield redirect(s"/specifications/$id")

  private def loadLinkedIssues(
    specification: Specification,
    issueRepository: IssueRepository,
  ): IO[PersistenceError, List[AgentIssue]] =
    issueRepository
      .list(IssueFilter(limit = Int.MaxValue))
      .map(_.filter(issue => specification.linkedIssueIds.contains(issue.id)))

  private def loadRequestedOrDefaultDiff(
    specification: Specification,
    req: Request,
    specificationRepository: SpecificationRepository,
  ): IO[PersistenceError, Option[SpecificationDiff]] =
    selectedVersions(req) match
      case Some((fromVersion, toVersion)) =>
        specificationRepository.diff(specification.id, fromVersion, toVersion).map(Some(_))
      case None                           =>
        if specification.version >= 2 then
          specificationRepository.diff(specification.id, specification.version - 1, specification.version).map(Some(_))
        else ZIO.succeed(None)

  private def loadRequiredDiff(
    specification: Specification,
    req: Request,
    specificationRepository: SpecificationRepository,
  ): IO[PersistenceError, SpecificationDiff] =
    selectedVersions(req) match
      case Some((fromVersion, toVersion)) =>
        specificationRepository.diff(specification.id, fromVersion, toVersion)
      case None                           =>
        if specification.version >= 2 then
          specificationRepository.diff(specification.id, specification.version - 1, specification.version)
        else
          ZIO.fail(PersistenceError.QueryFailed("specification_diff", "A diff requires at least two versions"))

  private def selectedVersions(req: Request): Option[(Int, Int)] =
    for
      fromValue <- req.queryParam("from").flatMap(_.trim.toIntOption)
      toValue   <- req.queryParam("to").flatMap(_.trim.toIntOption)
    yield (fromValue, toValue)

  private def parseForm(req: Request): IO[PersistenceError, Map[String, List[String]]] =
    req.body.asString
      .map(_.split("&").toList.filter(_.nonEmpty))
      .map(_.flatMap { pair =>
        pair.split("=", 2).toList match
          case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
          case key :: Nil          => Some(urlDecode(key) -> "")
          case _                   => None
      }.groupMap(_._1)(_._2))
      .mapError(err => PersistenceError.QueryFailed("parse_specification_form", err.getMessage))

  private def required(form: Map[String, List[String]], key: String): IO[PersistenceError, String] =
    ZIO
      .fromOption(form.get(key).flatMap(_.headOption).map(_.trim).filter(_.nonEmpty))
      .orElseFail(PersistenceError.QueryFailed("specification_form", s"Missing required field: $key"))

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def redirect(location: String): Response =
    Response(status = Status.SeeOther, headers = Headers(Header.Location(URL.decode(location).getOrElse(URL.root))))

  private def htmlResponse(content: String): Response =
    Response.text(content).contentType(MediaType.text.html)

  private def persistErr(error: PersistenceError): Response =
    error match
      case PersistenceError.NotFound(entity, entityId)    =>
        Response.text(s"$entity with id $entityId not found").status(Status.NotFound)
      case PersistenceError.StoreUnavailable(message)     =>
        Response.text(s"Database unavailable: $message").status(Status.ServiceUnavailable)
      case PersistenceError.QueryFailed(_, cause)         =>
        Response.text(s"Specification query failed: $cause").status(Status.BadRequest)
      case PersistenceError.SerializationFailed(_, cause) =>
        Response.text(s"Specification data serialization failed: $cause").status(Status.InternalServerError)
