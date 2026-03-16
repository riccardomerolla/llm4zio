package orchestration.boundary

import java.net.{ URLDecoder, URLEncoder }
import java.nio.charset.StandardCharsets

import zio.*
import zio.http.*

import db.ChatRepository
import orchestration.control.{ PlannerAgentError, PlannerAgentService, PlannerIssueDraft, PlannerPlanPreview }
import shared.web.PlannerView
import workspace.entity.WorkspaceRepository

trait PlannerController:
  def routes: Routes[Any, Response]

object PlannerController:
  val live: ZLayer[PlannerAgentService & WorkspaceRepository & ChatRepository, Nothing, PlannerController] =
    ZLayer.fromFunction(PlannerControllerLive.apply)

final case class PlannerControllerLive(
  plannerAgentService: PlannerAgentService,
  workspaceRepository: WorkspaceRepository,
  chatRepository: ChatRepository,
) extends PlannerController:

  override val routes: Routes[Any, Response] = Routes(
    Method.GET / "planner"                                        -> handler { (req: Request) =>
      renderStartPage(
        initialRequest = req.queryParam("request").getOrElse(""),
        selectedWorkspaceId = req.queryParam("workspace_id").filterNot(_ == "chat"),
      )
    },
    Method.POST / "planner"                                       -> handler { (req: Request) =>
      (for
        form       <- parseMultiForm(req)
        request    <- required(form, "request")
        workspaceId = optional(form, "workspace_id").filterNot(_ == "chat")
        start      <- plannerAgentService.startSession(request, workspaceId)
        response   <- renderDetailPage(
                        start.conversationId,
                        canonicalPath = Some(plannerDetailPath(start.conversationId)),
                        status = Status.Created,
                      )
      yield response)
        .catchAll { error =>
          renderStartPage(
            errorMessage = Some(error.message),
            status = Status.BadRequest,
          )
        }
    },
    Method.GET / "planner" / string("id")                         -> handler { (id: String, _: Request) =>
      withConversationId(id)(conversationId => renderDetailPage(conversationId))
    },
    Method.GET / "planner" / string("id") / "plan-fragment"       -> handler { (id: String, _: Request) =>
      withConversationId(id)(renderPlanFragment)
    },
    Method.POST / "planner" / string("id") / "chat"               -> handler { (id: String, req: Request) =>
      withConversationId(id) { conversationId =>
        (for
          form <- parseMultiForm(req)
          msg  <- required(form, "message")
          _    <- plannerAgentService.appendUserMessage(conversationId, msg)
        yield Response.redirect(URL.decode(plannerDetailPath(conversationId)).toOption.getOrElse(URL.root)))
          .catchAll(error => renderDetailPage(conversationId, Some(error.message), status = Status.BadRequest))
      }
    },
    Method.POST / "planner" / string("id") / "refresh"            -> handler { (id: String, _: Request) =>
      withConversationId(id) { conversationId =>
        plannerAgentService.regeneratePreview(conversationId)
          .as(Response.redirect(URL.decode(plannerDetailPath(conversationId)).toOption.getOrElse(URL.root)))
          .catchAll(error => renderDetailPage(conversationId, Some(error.message), status = Status.BadRequest))
      }
    },
    Method.POST / "planner" / string("id") / "preview"            -> handler { (id: String, req: Request) =>
      withConversationId(id) { conversationId =>
        (for
          form    <- parseMultiForm(req)
          preview <- parsePreviewForm(form)
          _       <- plannerAgentService.updatePreview(conversationId, preview)
        yield Response.redirect(URL.decode(plannerDetailPath(conversationId)).toOption.getOrElse(URL.root)))
          .catchAll(error => renderDetailPage(conversationId, Some(error.message), status = Status.BadRequest))
      }
    },
    Method.POST / "planner" / string("id") / "preview" / "add"    -> handler { (id: String, _: Request) =>
      withConversationId(id) { conversationId =>
        plannerAgentService.addBlankIssue(conversationId)
          .as(Response.redirect(URL.decode(plannerDetailPath(conversationId)).toOption.getOrElse(URL.root)))
          .catchAll(error => renderDetailPage(conversationId, Some(error.message), status = Status.BadRequest))
      }
    },
    Method.POST / "planner" / string("id") / "preview" / "remove" -> handler { (id: String, req: Request) =>
      withConversationId(id) { conversationId =>
        (for
          form    <- parseMultiForm(req)
          draftId <- required(form, "draft_id")
          _       <- plannerAgentService.removeIssue(conversationId, draftId)
        yield Response.redirect(URL.decode(plannerDetailPath(conversationId)).toOption.getOrElse(URL.root)))
          .catchAll(error => renderDetailPage(conversationId, Some(error.message), status = Status.BadRequest))
      }
    },
    Method.POST / "planner" / string("id") / "confirm"            -> handler { (id: String, _: Request) =>
      withConversationId(id) { conversationId =>
        plannerAgentService.confirmPlan(conversationId)
          .map(result =>
            Response.redirect(
              URL.decode(boardRedirect(result.issueIds.map(_.value))).toOption.getOrElse(URL.root)
            )
          )
          .catchAll(error => renderDetailPage(conversationId, Some(error.message), status = Status.BadRequest))
      }
    },
  )

  private def parsePreviewForm(
    form: Map[String, List[String]]
  ): IO[PlannerAgentError, PlannerPlanPreview] =
    val summary      = optional(form, "summary").getOrElse("")
    val draftIds     = values(form, "draft_id")
    val titles       = values(form, "title")
    val descriptions = values(form, "description")
    val issueTypes   = values(form, "issue_type")
    val priorities   = values(form, "priority")
    val estimates    = values(form, "estimate")
    val capabilities = values(form, "required_capabilities")
    val dependencies = values(form, "dependency_draft_ids")
    val acceptance   = values(form, "acceptance_criteria")
    val prompts      = values(form, "prompt_template")
    val skills       = values(form, "kaizen_skills")
    val proof        = values(form, "proof_of_work_requirements")
    val included     = values(form, "included")
    val sizes        = List(
      draftIds.size,
      titles.size,
      descriptions.size,
      issueTypes.size,
      priorities.size,
      estimates.size,
      capabilities.size,
      dependencies.size,
      acceptance.size,
      prompts.size,
      skills.size,
      proof.size,
      included.size,
    ).distinct

    if sizes.size > 1 then
      ZIO.fail(PlannerAgentError.IssueDraftInvalid("Planner preview form fields were incomplete"))
    else
      ZIO.succeed(
        PlannerPlanPreview(
          summary = summary,
          issues = draftIds.indices.toList.map { idx =>
            PlannerIssueDraft(
              draftId = draftIds(idx),
              title = titles(idx),
              description = descriptions(idx),
              issueType = issueTypes(idx),
              priority = priorities(idx),
              estimate = Option(estimates(idx)).map(_.trim).filter(_.nonEmpty),
              requiredCapabilities = splitCsv(capabilities(idx)),
              dependencyDraftIds = splitCsv(dependencies(idx)),
              acceptanceCriteria = acceptance(idx),
              promptTemplate = prompts(idx),
              kaizenSkills = splitCsv(skills(idx)),
              proofOfWorkRequirements = splitCsv(proof(idx)),
              included = included(idx).equalsIgnoreCase("true"),
            )
          },
        )
      )

  private def parseMultiForm(req: Request): IO[PlannerAgentError, Map[String, List[String]]] =
    req.body.asString
      .map { body =>
        body
          .split("&")
          .toList
          .flatMap {
            _.split("=", 2).toList match
              case key :: value :: Nil => Some(urlDecode(key) -> urlDecode(value))
              case key :: Nil          => Some(urlDecode(key) -> "")
              case _                   => None
          }
          .groupMap(_._1)(_._2)
      }
      .mapError(err => PlannerAgentError.PersistenceFailure("parse_form", err.getMessage))

  private def required(form: Map[String, List[String]], key: String): IO[PlannerAgentError, String] =
    ZIO
      .fromOption(optional(form, key))
      .orElseFail(PlannerAgentError.IssueDraftInvalid(s"Missing required field: $key"))

  private def optional(form: Map[String, List[String]], key: String): Option[String] =
    form.get(key).flatMap(_.headOption).map(_.trim).filter(_.nonEmpty)

  private def values(form: Map[String, List[String]], key: String): List[String] =
    form.getOrElse(key, Nil).map(_.trim)

  private def splitCsv(raw: String): List[String] =
    raw.split(",").toList.map(_.trim).filter(_.nonEmpty).distinct

  private def withConversationId(id: String)(f: Long => ZIO[Any, Nothing, Response]): ZIO[Any, Nothing, Response] =
    id.trim.toLongOption match
      case Some(conversationId) => f(conversationId)
      case None                 => ZIO.succeed(Response.text(s"Invalid planner conversation id: $id").status(Status.BadRequest))

  private def html(content: String, status: Status): Response =
    Response(
      status = status,
      headers = Headers(Header.ContentType(MediaType.text.html)),
      body = Body.fromString(content),
    )

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def mapWorkspaceError(error: shared.errors.PersistenceError): PlannerAgentError =
    PlannerAgentError.PersistenceFailure("list_workspaces", error.toString)

  private def renderStartPage(
    errorMessage: Option[String] = None,
    initialRequest: String = "",
    selectedWorkspaceId: Option[String] = None,
    status: Status = Status.Ok,
  ): ZIO[Any, Nothing, Response] =
    workspaceRepository.list
      .mapError(mapWorkspaceError)
      .map { workspaces =>
        html(
          PlannerView.startPage(
            workspaces = workspaces.map(ws => ws.id -> ws.name),
            initialRequest = initialRequest,
            selectedWorkspaceId = selectedWorkspaceId,
            errorMessage = errorMessage,
          ),
          status = status,
        )
      }
      .catchAll(error => ZIO.succeed(Response.text(error.message).status(Status.BadRequest)))

  private def renderDetailPage(
    conversationId: Long,
    errorMessage: Option[String] = None,
    canonicalPath: Option[String] = None,
    status: Status = Status.Ok,
  ): ZIO[Any, Nothing, Response] =
    (for
      preview    <- plannerAgentService.getPreview(conversationId)
      messages   <- chatRepository.getMessages(conversationId).mapError(mapPersistenceError("planner_messages"))
      workspaces <- workspaceRepository.list.mapError(mapWorkspaceError)
    yield html(
      PlannerView.detailPage(
        state = preview,
        messages = messages,
        workspaces = workspaces.map(ws => ws.id -> ws.name),
        errorMessage = errorMessage,
        canonicalPath = canonicalPath,
      ),
      status = status,
    )).catchAll(error => ZIO.succeed(Response.text(error.message).status(Status.BadRequest)))

  private def renderPlanFragment(conversationId: Long): ZIO[Any, Nothing, Response] =
    plannerAgentService.getPreview(conversationId)
      .map(state => html(PlannerView.planPanels(state), status = Status.Ok))
      .catchAll(error => ZIO.succeed(Response.text(error.message).status(Status.BadRequest)))

  private def mapPersistenceError(operation: String)(error: db.PersistenceError): PlannerAgentError =
    PlannerAgentError.PersistenceFailure(operation, error.toString)

  private def plannerDetailPath(conversationId: Long): String =
    s"/planner/$conversationId"

  private def boardRedirect(issueIds: List[String]): String =
    val normalized = issueIds.map(_.trim).filter(_.nonEmpty).distinct
    if normalized.isEmpty then "/board?mode=list"
    else
      val query = URLEncoder.encode(normalized.mkString(","), StandardCharsets.UTF_8)
      s"/board?mode=list&plannerCreated=${normalized.size}&q=$query"
