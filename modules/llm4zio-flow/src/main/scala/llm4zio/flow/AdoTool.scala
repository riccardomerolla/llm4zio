package llm4zio.flow

import java.nio.charset.StandardCharsets
import java.util.Base64

import zio.*
import zio.json.*
import zio.json.ast.Json

import llm4zio.providers.HttpClient

/** A pure REST request: method + URL + optional body + content type. Built by [[AdoConfig]], executed by [[AdoTool]].
  */
final case class AdoRequest(
  method: String,
  url: String,
  body: Option[String] = None,
  contentType: String = "application/json",
)

/** An Azure Boards work item, reduced to the fields the SDD flow needs. `acceptanceCriteria` is where the spec lives
  * (`Microsoft.VSTS.Common.AcceptanceCriteria`) — drafted by llm4zio, edited by a human, read back as the contract.
  */
final case class WorkItem(
  id: Int,
  title: String,
  description: String,
  acceptanceCriteria: String,
  state: String,
  tags: List[String],
)

/** A pull request created in Azure Repos. `repoId`/`projectId` are the GUIDs needed to link the PR to a work item. */
final case class AdoPullRequest(id: Int, repoId: String, projectId: String, webUrl: String)

/** Connection + pure request-builders for the Azure DevOps REST API.
  *
  * `orgUrl` is e.g. `https://dev.azure.com/<org>`; `project` and `repository` name the project and Git repo; `pat` is a
  * Personal Access Token (or pipeline `System.AccessToken`). All builders are pure so they can be unit-tested;
  * [[AdoTool]] runs them over an [[HttpClient]].
  */
final case class AdoConfig(
  orgUrl: String,
  project: String,
  repository: String,
  pat: String,
  apiVersion: String = "7.1",
):
  /** HTTP Basic auth header: the PAT as the password with an empty username. */
  val authHeader: (String, String) =
    val token = Base64.getEncoder.encodeToString(s":$pat".getBytes(StandardCharsets.UTF_8))
    ("Authorization", s"Basic $token")

  private def witBase: String = s"$orgUrl/$project/_apis/wit"
  private def gitBase: String = s"$orgUrl/$project/_apis/git/repositories/$repository"

  def readWorkItemReq(id: Int): AdoRequest =
    AdoRequest("GET", s"$witBase/workitems/$id?$$expand=relations&api-version=$apiVersion")

  def wiqlReq(query: String): AdoRequest =
    AdoRequest("POST", s"$witBase/wiql?api-version=$apiVersion", Some(AdoConfig.WiqlBody(query).toJson))

  /** A json-patch PATCH that sets (`op: add` = set-or-replace) each field under `/fields/<name>`. */
  def setFieldsReq(id: Int, fields: Map[String, String]): AdoRequest =
    val ops = Json.Arr(fields.toSeq.map((k, v) => AdoConfig.addOp(s"/fields/$k", Json.Str(v)))*)
    AdoRequest(
      "PATCH",
      s"$witBase/workitems/$id?api-version=$apiVersion",
      Some(ops.toJson),
      contentType = "application/json-patch+json",
    )

  def commentReq(id: Int, text: String): AdoRequest =
    AdoRequest(
      "POST",
      s"$witBase/workItems/$id/comments?api-version=$apiVersion-preview.4",
      Some(AdoConfig.CommentBody(text).toJson),
    )

  /** A json-patch POST creating a work item of `workItemType` (the endpoint is literally `workitems/$<Type>`). */
  def createWorkItemReq(workItemType: String, title: String, fields: Map[String, String]): AdoRequest =
    val ops = Json.Arr(
      (("System.Title" -> title) +: fields.toSeq).map((k, v) => AdoConfig.addOp(s"/fields/$k", Json.Str(v)))*
    )
    AdoRequest(
      "POST",
      s"$witBase/workitems/$$$workItemType?api-version=$apiVersion",
      Some(ops.toJson),
      contentType = "application/json-patch+json",
    )

  def createPrReq(sourceRef: String, targetRef: String, title: String, description: String): AdoRequest =
    AdoRequest(
      "POST",
      s"$gitBase/pullrequests?api-version=$apiVersion",
      Some(AdoConfig.PrCreateBody(sourceRef, targetRef, title, description).toJson),
    )

  def prThreadReq(prId: Int, text: String): AdoRequest =
    AdoRequest(
      "POST",
      s"$gitBase/pullRequests/$prId/threads?api-version=$apiVersion",
      Some(AdoConfig.ThreadBody(List(AdoConfig.ThreadComment(text, 1)), 1).toJson),
    )

  /** A json-patch PATCH that appends an `ArtifactLink` relation pointing at the PR's `vstfs` artifact id. */
  def linkPrReq(workItemId: Int, pr: AdoPullRequest): AdoRequest =
    val artifact = s"vstfs:///Git/PullRequestId/${pr.projectId}%2F${pr.repoId}%2F${pr.id}"
    val relation = Json.Obj(
      "rel"        -> Json.Str("ArtifactLink"),
      "url"        -> Json.Str(artifact),
      "attributes" -> Json.Obj("name" -> Json.Str("Pull Request")),
    )
    val ops      = Json.Arr(AdoConfig.addOp("/relations/-", relation))
    AdoRequest(
      "PATCH",
      s"$witBase/workitems/$workItemId?api-version=$apiVersion",
      Some(ops.toJson),
      contentType = "application/json-patch+json",
    )

object AdoConfig:
  private def addOp(path: String, value: Json): Json =
    Json.Obj("op" -> Json.Str("add"), "path" -> Json.Str(path), "value" -> value)

  private case class WiqlBody(query: String) derives JsonCodec
  private case class CommentBody(text: String) derives JsonCodec
  private case class PrCreateBody(sourceRefName: String, targetRefName: String, title: String, description: String)
    derives JsonCodec
  private case class ThreadComment(content: String, commentType: Int) derives JsonCodec
  private case class ThreadBody(comments: List[ThreadComment], status: Int) derives JsonCodec

/** Thin effectful Azure DevOps client over [[HttpClient]] (mirrors [[GhTool]]'s shape). */
final class AdoTool(config: AdoConfig, http: HttpClient, timeout: Duration = 30.seconds):

  private def run(req: AdoRequest): IO[FlowError, String] =
    http
      .send(req.method, req.url, req.body, Map(config.authHeader), req.contentType, timeout)
      .mapError(e => FlowError.Process(s"ado ${req.method} ${req.url}", e.message))

  /** Read a work item (title, description, acceptance criteria, state, tags). */
  def readWorkItem(id: Int): IO[FlowError, WorkItem] =
    run(config.readWorkItemReq(id))
      .flatMap(j => ZIO.fromEither(AdoTool.parseWorkItem(j)).mapError(FlowError.Process("ado parse work item", _)))

  /** Ids of work items matching a WIQL query — the dispatcher's poll. */
  def wiqlIds(query: String): IO[FlowError, List[Int]] =
    run(config.wiqlReq(query))
      .flatMap(j => ZIO.fromEither(AdoTool.parseWiqlIds(j)).mapError(FlowError.Process("ado parse wiql", _)))

  def setFields(id: Int, fields: Map[String, String]): IO[FlowError, Unit] = run(config.setFieldsReq(id, fields)).unit

  def setState(id: Int, state: String): IO[FlowError, Unit] = setFields(id, Map("System.State" -> state))

  /** Write the drafted/edited spec into the work item's Acceptance Criteria field. */
  def setAcceptanceCriteria(id: Int, text: String): IO[FlowError, Unit] =
    setFields(id, Map("Microsoft.VSTS.Common.AcceptanceCriteria" -> text))

  /** Add a tag without clobbering the others (reads current, appends, writes the whole field). */
  def addTag(id: Int, tag: String): IO[FlowError, Unit] =
    readWorkItem(id).flatMap(wi => setFields(id, Map("System.Tags" -> (wi.tags :+ tag).distinct.mkString("; "))))

  def comment(id: Int, text: String): IO[FlowError, Unit] = run(config.commentReq(id, text)).unit

  /** Create a work item (e.g. a Task per plan task, or an improvement from a review); returns its id. */
  def createWorkItem(workItemType: String, title: String, fields: Map[String, String]): IO[FlowError, Int] =
    run(config.createWorkItemReq(workItemType, title, fields))
      .flatMap(j => ZIO.fromEither(AdoTool.parseWorkItem(j)).mapError(FlowError.Process("ado parse work item", _)))
      .map(_.id)

  def createPr(sourceRef: String, targetRef: String, title: String, body: String): IO[FlowError, AdoPullRequest] =
    run(config.createPrReq(sourceRef, targetRef, title, body))
      .flatMap(j => ZIO.fromEither(AdoTool.parsePr(j, config)).mapError(FlowError.Process("ado parse pr", _)))

  /** Link a PR to a work item (so the board shows the development link). */
  def linkPr(workItemId: Int, pr: AdoPullRequest): IO[FlowError, Unit] = run(config.linkPrReq(workItemId, pr)).unit

  def prThread(prId: Int, text: String): IO[FlowError, Unit] = run(config.prThreadReq(prId, text)).unit

object AdoTool:
  private case class WiJson(id: Int, fields: Map[String, Json]) derives JsonCodec
  private case class WiRef(id: Int) derives JsonCodec
  private case class WiqlJson(workItems: List[WiRef]) derives JsonCodec
  private case class PrProjectJson(id: String) derives JsonCodec
  private case class PrRepoJson(id: String, project: PrProjectJson) derives JsonCodec
  private case class PrJson(pullRequestId: Int, repository: PrRepoJson) derives JsonCodec

  private def fieldStr(fields: Map[String, Json], key: String): String =
    fields.get(key) match
      case Some(Json.Str(s)) => s
      case _                 => ""

  def parseWorkItem(json: String): Either[String, WorkItem] =
    json.fromJson[WiJson].map { wi =>
      val tags = fieldStr(wi.fields, "System.Tags").split(";").map(_.trim).filter(_.nonEmpty).toList
      WorkItem(
        id = wi.id,
        title = fieldStr(wi.fields, "System.Title"),
        description = fieldStr(wi.fields, "System.Description"),
        acceptanceCriteria = fieldStr(wi.fields, "Microsoft.VSTS.Common.AcceptanceCriteria"),
        state = fieldStr(wi.fields, "System.State"),
        tags = tags,
      )
    }

  def parseWiqlIds(json: String): Either[String, List[Int]] =
    json.fromJson[WiqlJson].map(_.workItems.map(_.id))

  def parsePr(json: String, config: AdoConfig): Either[String, AdoPullRequest] =
    json.fromJson[PrJson].map { pr =>
      AdoPullRequest(
        id = pr.pullRequestId,
        repoId = pr.repository.id,
        projectId = pr.repository.project.id,
        webUrl = s"${config.orgUrl}/${config.project}/_git/${config.repository}/pullrequest/${pr.pullRequestId}",
      )
    }
