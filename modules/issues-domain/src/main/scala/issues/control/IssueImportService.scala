package issues.control

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*

import issues.boundary.IssueControllerSupport
import issues.entity.api.*
import issues.entity.{ IssueEvent, IssueRepository }
import shared.errors.PersistenceError
import shared.ids.Ids.IssueId
import taskrun.entity.TaskRepository

/** Folder + GitHub issue-import boundary — extracted from [[issues.boundary.IssueController]] in phase 4F.4.
  *
  * Filed under `issues-domain` (not `orchestration-domain` like [[IssueBulkService]]) because it only needs
  * `IssueRepository` + `TaskRepository`; no orchestration dependency. `issuesDomain` already depends on
  * `taskrunDomain`, so the graph is clean.
  *
  * Responsibilities:
  *   - `.md` folder preview + import (reads a setting `issues.importFolder` for onboarding)
  *   - GitHub issue preview + import via the `gh` CLI
  */
trait IssueImportService:
  def previewFolder(request: FolderImportRequest): IO[PersistenceError, List[FolderImportPreviewItem]]
  def importFolder(request: FolderImportRequest): IO[PersistenceError, BulkIssueOperationResponse]
  def importConfiguredFolder: IO[PersistenceError, BulkIssueOperationResponse]
  def previewGitHub(request: GitHubImportPreviewRequest): IO[PersistenceError, List[GitHubImportPreviewItem]]
  def importGitHub(request: GitHubImportPreviewRequest): IO[PersistenceError, BulkIssueOperationResponse]

object IssueImportService:

  val live: ZLayer[IssueRepository & TaskRepository, Nothing, IssueImportService] =
    ZLayer.fromFunction(IssueImportServiceLive.apply)

final case class IssueImportServiceLive(
  issueRepository: IssueRepository,
  taskRepository: TaskRepository,
) extends IssueImportService:

  import IssueControllerSupport.parseMarkdownIssue

  override def previewFolder(request: FolderImportRequest): IO[PersistenceError, List[FolderImportPreviewItem]] =
    for
      files <- markdownFiles(request.folder)
      now   <- Clock.instant
      items <- ZIO.foreach(files) { file =>
                 for
                   markdown <- ZIO
                                 .attemptBlocking(Files.readString(file, StandardCharsets.UTF_8))
                                 .mapError(e => PersistenceError.QueryFailed(file.toString, e.getMessage))
                   parsed    = parseMarkdownIssue(file, markdown, now)
                 yield FolderImportPreviewItem(
                   fileName = file.getFileName.toString,
                   title = parsed.title,
                   issueType = parsed.issueType,
                   priority = parsed.priority,
                 )
               }
    yield items

  override def importFolder(request: FolderImportRequest): IO[PersistenceError, BulkIssueOperationResponse] =
    for
      files   <- markdownFiles(request.folder)
      results <- ZIO.foreach(files) { file =>
                   (for
                     now      <- Clock.instant
                     markdown <- ZIO
                                   .attemptBlocking(Files.readString(file, StandardCharsets.UTF_8))
                                   .mapError(e => PersistenceError.QueryFailed(file.toString, e.getMessage))
                     event     = parseMarkdownIssue(file, markdown, now)
                     _        <- issueRepository.append(event).mapError(mapIssueRepoError)
                   yield ()).either
                 }
    yield toBulkResponse(files.size, results)

  override def importConfiguredFolder: IO[PersistenceError, BulkIssueOperationResponse] =
    for
      configuredFolder <- folderFromSettings
      result           <- importFolder(FolderImportRequest(configuredFolder))
    yield result

  override def previewGitHub(
    request: GitHubImportPreviewRequest
  ): IO[PersistenceError, List[GitHubImportPreviewItem]] =
    ghListIssues(request).map { raw =>
      raw.fromJson[List[GitHubImportPreviewItem]].getOrElse(Nil)
    }

  override def importGitHub(request: GitHubImportPreviewRequest): IO[PersistenceError, BulkIssueOperationResponse] =
    for
      items   <- previewGitHub(request)
      results <- ZIO.foreach(items) { item =>
                   (for
                     now    <- Clock.instant
                     issueId = IssueId.generate
                     _      <- issueRepository
                                 .append(
                                   IssueEvent.Created(
                                     issueId = issueId,
                                     title = s"[GH#${item.number}] ${item.title}",
                                     description = item.body,
                                     issueType = "github",
                                     priority = "medium",
                                     occurredAt = now,
                                     requiredCapabilities = Nil,
                                   )
                                 )
                                 .mapError(mapIssueRepoError)
                     _      <- issueRepository
                                 .append(
                                   IssueEvent.ExternalRefLinked(
                                     issueId = issueId,
                                     externalRef = s"GH:${request.repo}#${item.number}",
                                     externalUrl = Some(item.url),
                                     occurredAt = now,
                                   )
                                 )
                                 .mapError(mapIssueRepoError)
                   yield ()).either
                 }
    yield toBulkResponse(items.size, results)

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def folderFromSettings: IO[PersistenceError, String] =
    taskRepository
      .getSetting("issues.importFolder")
      .flatMap(opt =>
        ZIO
          .fromOption(opt.map(_.value.trim).filter(_.nonEmpty))
          .orElseFail(PersistenceError.QueryFailed("settings", "'issues.importFolder' is empty or missing"))
      )

  private def markdownFiles(folderSetting: String): IO[PersistenceError, List[Path]] =
    for
      folderPath <- ZIO
                      .fromOption(Option(folderSetting).map(_.trim).filter(_.nonEmpty))
                      .orElseFail(PersistenceError.QueryFailed("folder", "Folder path is required"))
      folder     <- ZIO
                      .attempt(Paths.get(folderPath))
                      .mapError(e => PersistenceError.QueryFailed("folder", e.getMessage))
      files      <- ZIO
                      .attemptBlocking {
                        if !Files.exists(folder) then List.empty[Path]
                        else
                          Files
                            .list(folder)
                            .iterator()
                            .asScala
                            .filter(path =>
                              Files.isRegularFile(path) && path.getFileName.toString.toLowerCase.endsWith(".md")
                            )
                            .toList
                      }
                      .mapError(e => PersistenceError.QueryFailed("folder", e.getMessage))
    yield files

  private def ghListIssues(request: GitHubImportPreviewRequest): IO[PersistenceError, String] =
    val safeLimit = request.limit.max(1).min(200)
    val safeState = request.state.trim.toLowerCase match
      case "closed" => "closed"
      case "all"    => "all"
      case _        => "open"
    final case class GhIssueLabel(name: String) derives JsonCodec
    final case class GhIssueItem(
      number: Long,
      title: String,
      body: String,
      labels: List[GhIssueLabel] = Nil,
      state: String,
      url: String,
    ) derives JsonCodec
    ZIO
      .attemptBlocking {
        val args    = List(
          "gh",
          "issue",
          "list",
          "--repo",
          request.repo.trim,
          "--state",
          safeState,
          "--limit",
          safeLimit.toString,
          "--json",
          "number,title,body,labels,state,url",
        )
        val process = new ProcessBuilder(args*).redirectErrorStream(true).start()
        val output  = scala.io.Source.fromInputStream(process.getInputStream, "UTF-8").mkString
        val exit    = process.waitFor()
        exit -> output
      }
      .mapError(err => PersistenceError.QueryFailed("github_import", Option(err.getMessage).getOrElse(err.toString)))
      .flatMap {
        case (exit, output) =>
          if exit == 0 then
            output.fromJson[List[GhIssueItem]] match
              case Right(values) =>
                ZIO.succeed(
                  values.map { item =>
                    GitHubImportPreviewItem(
                      number = item.number,
                      title = item.title,
                      body = item.body,
                      labels = item.labels.map(_.name),
                      state = item.state,
                      url = item.url,
                    )
                  }.toJson
                )
              case Left(_)       => ZIO.succeed("[]")
          else
            ZIO.fail(PersistenceError.QueryFailed("github_import", output.trim))
      }

  private def toBulkResponse(
    requested: Int,
    results: List[Either[PersistenceError, Unit]],
  ): BulkIssueOperationResponse =
    val errors = results.collect { case Left(err) => err.toString }
    BulkIssueOperationResponse(
      requested = requested,
      succeeded = results.count(_.isRight),
      failed = errors.size,
      errors = errors,
    )

  private def mapIssueRepoError(e: PersistenceError): PersistenceError =
    e match
      case PersistenceError.NotFound(entity, id)               =>
        PersistenceError.QueryFailed(s"$entity", s"Not found: $id")
      case other                                               => other
