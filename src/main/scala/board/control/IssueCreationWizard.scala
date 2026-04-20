package board.control

import java.nio.file.Paths
import java.time.Instant

import scala.util.matching.Regex

import zio.*
import zio.json.*
import zio.json.ast.Json

import board.entity.*
import llm4zio.core.{ LlmError, LlmService }
import llm4zio.tools.JsonSchema
import shared.errors.FileError
import shared.ids.Ids.BoardIssueId
import shared.services.FileService

enum IssueCreationError:
  case SessionNotFound(sessionId: String)
  case InvalidDraft(details: String)
  case LlmFailure(details: String)
  case FileFailure(details: String)
  case BoardFailure(error: BoardError)

  def message: String =
    this match
      case SessionNotFound(sessionId) => s"Issue creation session not found: $sessionId"
      case InvalidDraft(details)      => details
      case LlmFailure(details)        => s"LLM generation failed: $details"
      case FileFailure(details)       => s"File operation failed: $details"
      case BoardFailure(error)        => error.toString

final case class IssueCreationDraft(
  draftId: String,
  frontmatter: IssueFrontmatter,
  body: String,
  included: Boolean = true,
) derives JsonCodec

final case class IssueCreationPreview(
  summary: String,
  drafts: List[IssueCreationDraft],
) derives JsonCodec

final case class IssueCreationSession(
  sessionId: String,
  workspacePath: String,
  preview: IssueCreationPreview,
  mode: String,
  createdAt: Instant,
) derives JsonCodec

final case class IssueCreationResult(
  sessionId: String,
  issueIds: List[BoardIssueId],
) derives JsonCodec

trait IssueCreationWizard:
  def startNaturalLanguage(workspacePath: String, description: String): IO[IssueCreationError, IssueCreationSession]
  def startCodeAnalysis(workspacePath: String, scopePath: String): IO[IssueCreationError, IssueCreationSession]
  def startTemplate(
    workspacePath: String,
    templateName: String,
    context: Map[String, String],
  ): IO[IssueCreationError, IssueCreationSession]
  def listTemplates(workspacePath: String): IO[IssueCreationError, List[String]]
  def getSession(sessionId: String): IO[IssueCreationError, IssueCreationSession]
  def updatePreview(sessionId: String, preview: IssueCreationPreview): IO[IssueCreationError, IssueCreationSession]
  def confirm(sessionId: String): IO[IssueCreationError, IssueCreationResult]

object IssueCreationWizard:
  def startNaturalLanguage(
    workspacePath: String,
    description: String,
  ): ZIO[IssueCreationWizard, IssueCreationError, IssueCreationSession] =
    ZIO.serviceWithZIO[IssueCreationWizard](_.startNaturalLanguage(workspacePath, description))

  def startCodeAnalysis(
    workspacePath: String,
    scopePath: String,
  ): ZIO[IssueCreationWizard, IssueCreationError, IssueCreationSession] =
    ZIO.serviceWithZIO[IssueCreationWizard](_.startCodeAnalysis(workspacePath, scopePath))

  def startTemplate(
    workspacePath: String,
    templateName: String,
    context: Map[String, String],
  ): ZIO[IssueCreationWizard, IssueCreationError, IssueCreationSession] =
    ZIO.serviceWithZIO[IssueCreationWizard](_.startTemplate(workspacePath, templateName, context))

  def listTemplates(workspacePath: String): ZIO[IssueCreationWizard, IssueCreationError, List[String]] =
    ZIO.serviceWithZIO[IssueCreationWizard](_.listTemplates(workspacePath))

  def getSession(sessionId: String): ZIO[IssueCreationWizard, IssueCreationError, IssueCreationSession] =
    ZIO.serviceWithZIO[IssueCreationWizard](_.getSession(sessionId))

  def updatePreview(
    sessionId: String,
    preview: IssueCreationPreview,
  ): ZIO[IssueCreationWizard, IssueCreationError, IssueCreationSession] =
    ZIO.serviceWithZIO[IssueCreationWizard](_.updatePreview(sessionId, preview))

  def confirm(sessionId: String): ZIO[IssueCreationWizard, IssueCreationError, IssueCreationResult] =
    ZIO.serviceWithZIO[IssueCreationWizard](_.confirm(sessionId))

  val live: URLayer[LlmService & BoardRepository & IssueMarkdownParser & FileService, IssueCreationWizard] =
    ZLayer.fromZIO {
      for
        llm         <- ZIO.service[LlmService]
        repository  <- ZIO.service[BoardRepository]
        parser      <- ZIO.service[IssueMarkdownParser]
        fileService <- ZIO.service[FileService]
        state       <- Ref.Synchronized.make(Map.empty[String, IssueCreationSession])
      yield IssueCreationWizardLive(llm, repository, parser, fileService, state)
    }

final private case class GeneratedIssueDraft(
  id: Option[String],
  title: String,
  priority: String,
  assignedAgent: Option[String],
  requiredCapabilities: List[String],
  blockedBy: List[String],
  tags: List[String],
  acceptanceCriteria: List[String],
  estimate: Option[String],
  proofOfWork: List[String],
  body: String,
) derives JsonCodec

final private case class GeneratedIssueBatch(
  summary: String,
  issues: List[GeneratedIssueDraft],
) derives JsonCodec

final case class IssueCreationWizardLive(
  llmService: LlmService,
  boardRepository: BoardRepository,
  issueMarkdownParser: IssueMarkdownParser,
  fileService: FileService,
  stateRef: Ref.Synchronized[Map[String, IssueCreationSession]],
) extends IssueCreationWizard:

  private val placeholderRegex: Regex = "\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*\\}\\}".r
  private val templatesFolder         = ".board/templates"
  private val maxFilesForAnalysis     = 80
  private val maxCharsPerFile         = 5000
  private val maxTotalChars           = 50000

  override def startNaturalLanguage(workspacePath: String, description: String)
    : IO[IssueCreationError, IssueCreationSession] =
    for
      now     <- Clock.instant
      summary <- generateFromDescription(description)
      preview <- toPreview(summary, now)
      session <- saveSession(workspacePath, mode = "natural-language", preview, now)
    yield session

  override def startCodeAnalysis(workspacePath: String, scopePath: String)
    : IO[IssueCreationError, IssueCreationSession] =
    for
      now      <- Clock.instant
      analysis <- loadCodeScope(scopePath)
      summary  <- generateFromCodeAnalysis(scopePath, analysis)
      preview  <- toPreview(summary, now)
      session  <- saveSession(workspacePath, mode = "code-analysis", preview, now)
    yield session

  override def startTemplate(
    workspacePath: String,
    templateName: String,
    context: Map[String, String],
  ): IO[IssueCreationError, IssueCreationSession] =
    for
      now      <- Clock.instant
      template <- loadTemplate(workspacePath, templateName)
      rendered <- interpolateTemplate(templateName, template, context)
      parsed   <- issueMarkdownParser.parse(rendered).mapError(IssueCreationError.BoardFailure.apply)
      preview   = IssueCreationPreview(
                    summary = s"Prepared issue from template '$templateName'.",
                    drafts = List(
                      IssueCreationDraft(
                        draftId = "issue-1",
                        frontmatter = parsed._1,
                        body = parsed._2.trim,
                        included = true,
                      )
                    ),
                  )
      session  <- saveSession(workspacePath, mode = "template", preview, now)
    yield session

  override def listTemplates(workspacePath: String): IO[IssueCreationError, List[String]] =
    val root = Paths.get(workspacePath).resolve(templatesFolder)
    fileService
      .exists(root)
      .mapError(mapFileError)
      .flatMap {
        case false => ZIO.succeed(Nil)
        case true  =>
          fileService
            .listFiles(root, Set(".md"))
            .runCollect
            .mapError(mapFileError)
            .map(paths => paths.toList.map(_.getFileName.toString).sorted)
      }

  override def getSession(sessionId: String): IO[IssueCreationError, IssueCreationSession] =
    stateRef.get.flatMap { state =>
      state.get(sessionId) match
        case Some(session) => ZIO.succeed(session)
        case None          => ZIO.fail(IssueCreationError.SessionNotFound(sessionId))
    }

  override def updatePreview(sessionId: String, preview: IssueCreationPreview)
    : IO[IssueCreationError, IssueCreationSession] =
    for
      _       <- ZIO.fromEither(validatePreview(preview))
      updated <- stateRef.modifyZIO { state =>
                   state.get(sessionId) match
                     case Some(existing) =>
                       val next = existing.copy(preview = preview)
                       ZIO.succeed(next -> state.updated(sessionId, next))
                     case None           =>
                       ZIO.fail(IssueCreationError.SessionNotFound(sessionId))
                 }
    yield updated

  override def confirm(sessionId: String): IO[IssueCreationError, IssueCreationResult] =
    for
      session <- getSession(sessionId)
      _       <- ZIO.fromEither(validatePreview(session.preview))
      toCreate = session.preview.drafts.filter(_.included)
      _       <- ZIO
                   .fail(IssueCreationError.InvalidDraft("No issue draft selected for creation"))
                   .when(toCreate.isEmpty)
      ids     <- ZIO.foreach(toCreate) { draft =>
                   val issue = BoardIssue(
                     frontmatter = draft.frontmatter,
                     body = draft.body.trim,
                     column = BoardColumn.Backlog,
                     directoryPath = "",
                   )
                   boardRepository
                     .createIssue(session.workspacePath, BoardColumn.Backlog, issue)
                     .mapError(IssueCreationError.BoardFailure.apply)
                     .map(_.frontmatter.id)
                 }
    yield IssueCreationResult(sessionId = sessionId, issueIds = ids)

  private def generateFromDescription(description: String): IO[IssueCreationError, GeneratedIssueBatch] =
    val prompt =
      s"""You are creating board issues from a user request.
         |
         |Request:
         |$description
         |
         |Return one or more concrete issue drafts. Each issue must include:
         |- title
         |- priority (critical|high|medium|low)
         |- requiredCapabilities
         |- tags
         |- acceptanceCriteria
         |- proofOfWork
         |- body (markdown)
         |
         |Use kebab-case ids if you provide ids.
         |""".stripMargin

    llmService
      .executeStructured[GeneratedIssueBatch](prompt, generationSchema)
      .mapError(mapLlmError)

  private def generateFromCodeAnalysis(scopePath: String, analysis: String)
    : IO[IssueCreationError, GeneratedIssueBatch] =
    val prompt =
      s"""Analyze the code scope and propose actionable board issues.
         |
         |Scope: $scopePath
         |
         |Code excerpts:
         |$analysis
         |
         |Focus on tech debt, missing tests, refactoring opportunities.
         |Return issue drafts with practical, testable acceptance criteria.
         |""".stripMargin

    llmService
      .executeStructured[GeneratedIssueBatch](prompt, generationSchema)
      .mapError(mapLlmError)

  private def toPreview(batch: GeneratedIssueBatch, now: Instant): IO[IssueCreationError, IssueCreationPreview] =
    for
      drafts  <- ZIO.foreach(batch.issues.zipWithIndex) {
                   case (generated, idx) =>
                     normalizeDraft(generated, idx + 1, now)
                 }
      _       <- ZIO
                   .fail(IssueCreationError.InvalidDraft("LLM response did not contain any issue draft"))
                   .when(drafts.isEmpty)
      preview <- ZIO.fromEither(
                   validatePreview(
                     IssueCreationPreview(
                       summary =
                         Option(batch.summary).map(_.trim).filter(_.nonEmpty).getOrElse(defaultSummary(drafts.size)),
                       drafts = drafts,
                     )
                   )
                 )
    yield preview

  private def normalizeDraft(
    generated: GeneratedIssueDraft,
    index: Int,
    now: Instant,
  ): IO[IssueCreationError, IssueCreationDraft] =
    val title = generated.title.trim
    val body  = generated.body.trim

    for
      _          <- ZIO.fail(IssueCreationError.InvalidDraft(s"Draft #$index title cannot be empty")).when(title.isEmpty)
      _          <- ZIO.fail(IssueCreationError.InvalidDraft(s"Draft #$index body cannot be empty")).when(body.isEmpty)
      issueId    <- normalizeIssueId(generated.id, title)
      blockedBy  <- ZIO.foreach(generated.blockedBy.map(_.trim).filter(_.nonEmpty))(value =>
                      ZIO
                        .fromEither(BoardIssueId.fromString(value))
                        .mapError(err => IssueCreationError.InvalidDraft(s"Draft #$index blockedBy '$value': $err"))
                    )
      priority   <- normalizePriority(generated.priority, index)
      estimate   <- normalizeEstimate(generated.estimate, index)
      frontmatter = IssueFrontmatter(
                      id = issueId,
                      title = title,
                      priority = priority,
                      assignedAgent = generated.assignedAgent.map(_.trim).filter(_.nonEmpty),
                      requiredCapabilities = sanitizeList(generated.requiredCapabilities),
                      blockedBy = blockedBy,
                      tags = sanitizeList(generated.tags),
                      acceptanceCriteria = sanitizeList(generated.acceptanceCriteria),
                      estimate = estimate,
                      proofOfWork = sanitizeList(generated.proofOfWork),
                      transientState = TransientState.None,
                      branchName = None,
                      failureReason = None,
                      completedAt = None,
                      createdAt = now,
                    )
    yield IssueCreationDraft(
      draftId = s"issue-$index",
      frontmatter = frontmatter,
      body = body,
      included = true,
    )

  private def normalizeIssueId(rawId: Option[String], title: String): IO[IssueCreationError, BoardIssueId] =
    val candidate = rawId.map(_.trim).filter(_.nonEmpty).getOrElse(slugify(title))
    ZIO
      .fromEither(BoardIssueId.fromString(candidate))
      .mapError(err => IssueCreationError.InvalidDraft(s"Issue id '$candidate' is invalid: $err"))

  private def normalizePriority(raw: String, index: Int): IO[IssueCreationError, IssuePriority] =
    raw.trim.toLowerCase match
      case "critical" => ZIO.succeed(IssuePriority.Critical)
      case "high"     => ZIO.succeed(IssuePriority.High)
      case "medium"   => ZIO.succeed(IssuePriority.Medium)
      case "low"      => ZIO.succeed(IssuePriority.Low)
      case other      => ZIO.fail(IssueCreationError.InvalidDraft(s"Draft #$index has invalid priority '$other'"))

  private def normalizeEstimate(raw: Option[String], index: Int): IO[IssueCreationError, Option[IssueEstimate]] =
    raw.map(_.trim.toUpperCase).filter(_.nonEmpty) match
      case None        => ZIO.succeed(None)
      case Some("XS")  => ZIO.succeed(Some(IssueEstimate.XS))
      case Some("S")   => ZIO.succeed(Some(IssueEstimate.S))
      case Some("M")   => ZIO.succeed(Some(IssueEstimate.M))
      case Some("L")   => ZIO.succeed(Some(IssueEstimate.L))
      case Some("XL")  => ZIO.succeed(Some(IssueEstimate.XL))
      case Some(value) => ZIO.fail(IssueCreationError.InvalidDraft(s"Draft #$index has invalid estimate '$value'"))

  private def validatePreview(preview: IssueCreationPreview): Either[IssueCreationError, IssueCreationPreview] =
    val summary = preview.summary.trim
    if summary.isEmpty then Left(IssueCreationError.InvalidDraft("Preview summary cannot be empty"))
    else if preview.drafts.isEmpty then Left(IssueCreationError.InvalidDraft("Preview does not contain any draft"))
    else
      val ids        = preview.drafts.map(_.frontmatter.id.value)
      val duplicates = ids.groupBy(identity).collect { case (id, values) if values.size > 1 => id }.toList.sorted
      if duplicates.nonEmpty then
        Left(IssueCreationError.InvalidDraft(s"Duplicate issue ids: ${duplicates.mkString(", ")}"))
      else
        Right(
          preview.copy(
            summary = summary,
            drafts = preview.drafts.map(draft =>
              draft.copy(
                draftId = draft.draftId.trim,
                body = draft.body.trim,
                frontmatter = draft.frontmatter.copy(
                  title = draft.frontmatter.title.trim,
                  requiredCapabilities = sanitizeList(draft.frontmatter.requiredCapabilities),
                  tags = sanitizeList(draft.frontmatter.tags),
                  acceptanceCriteria = sanitizeList(draft.frontmatter.acceptanceCriteria),
                  proofOfWork = sanitizeList(draft.frontmatter.proofOfWork),
                ),
              )
            ),
          )
        )

  private def saveSession(
    workspacePath: String,
    mode: String,
    preview: IssueCreationPreview,
    createdAt: Instant,
  ): IO[IssueCreationError, IssueCreationSession] =
    for
      sessionId <- Random.nextUUID.map(_.toString)
      session    = IssueCreationSession(
                     sessionId = sessionId,
                     workspacePath = workspacePath,
                     preview = preview,
                     mode = mode,
                     createdAt = createdAt,
                   )
      _         <- stateRef.update(_.updated(sessionId, session))
    yield session

  private def loadCodeScope(scopePath: String): IO[IssueCreationError, String] =
    val path = Paths.get(scopePath)

    fileService
      .listFiles(path, Set(".scala", ".sbt", ".md", ".conf", ".yaml", ".yml", ".json"))
      .take(maxFilesForAnalysis.toLong)
      .runCollect
      .mapError(mapFileError)
      .flatMap { files =>
        ZIO.foldLeft(files.toList.sortBy(_.toString))((List.empty[String], 0)) {
          case ((chunks, total), _) if total >= maxTotalChars =>
            ZIO.succeed(chunks -> total)
          case ((chunks, total), file)                        =>
            fileService
              .readFile(file)
              .mapError(mapFileError)
              .map { content =>
                val relative = path.relativize(file).toString
                val trimmed  = content.take(maxCharsPerFile)
                val block    = s"## File: $relative\\n$trimmed"
                val nextLen  = total + block.length
                if nextLen <= maxTotalChars then ((chunks :+ block), nextLen)
                else (chunks, total)
              }
        }.flatMap {
          case (chunks, _) =>
            if chunks.isEmpty then
              ZIO.fail(IssueCreationError.InvalidDraft(s"No readable files found under $scopePath"))
            else ZIO.succeed(chunks.mkString("\\n\\n"))
        }
      }

  private def loadTemplate(workspacePath: String, templateName: String): IO[IssueCreationError, String] =
    val safeName = templateName.trim
    val path     = Paths.get(workspacePath).resolve(templatesFolder).resolve(safeName)
    fileService.readFile(path).mapError(mapFileError)

  private def interpolateTemplate(
    templateName: String,
    template: String,
    context: Map[String, String],
  ): IO[IssueCreationError, String] =
    val placeholders = placeholderRegex.findAllMatchIn(template).map(_.group(1)).toSet
    val missing      = placeholders.filterNot(context.contains).toList.sorted

    if missing.nonEmpty then
      ZIO.fail(
        IssueCreationError.InvalidDraft(
          s"Template '$templateName' is missing placeholders: ${missing.mkString(", ")}"
        )
      )
    else
      val rendered   = placeholderRegex.replaceAllIn(template, mtch => context(mtch.group(1)))
      val unresolved = placeholderRegex.findAllMatchIn(rendered).map(_.group(1)).toSet.toList.sorted
      if unresolved.nonEmpty then
        ZIO.fail(
          IssueCreationError.InvalidDraft(
            s"Template '$templateName' has unresolved placeholders: ${unresolved.mkString(", ")}"
          )
        )
      else ZIO.succeed(rendered)

  private def defaultSummary(size: Int): String =
    size match
      case 1 => "Generated 1 issue draft."
      case n => s"Generated $n issue drafts."

  private def slugify(value: String): String =
    value.trim.toLowerCase
      .replaceAll("[^a-z0-9]+", "-")
      .replaceAll("-{2,}", "-")
      .stripPrefix("-")
      .stripSuffix("-")
      .nn match
      case ""   => "issue"
      case slug => slug

  private def sanitizeList(values: List[String]): List[String] =
    values.map(_.trim).filter(_.nonEmpty).distinct

  private def mapLlmError(error: LlmError): IssueCreationError =
    IssueCreationError.LlmFailure(error.toString)

  private def mapFileError(error: FileError): IssueCreationError =
    IssueCreationError.FileFailure(error.message)

  private val generationSchema: JsonSchema =
    Json.Obj(
      "type"                 -> Json.Str("object"),
      "properties"           -> Json.Obj(
        "summary" -> Json.Obj("type" -> Json.Str("string")),
        "issues"  -> Json.Obj(
          "type"  -> Json.Str("array"),
          "items" -> Json.Obj(
            "type"                 -> Json.Str("object"),
            "properties"           -> Json.Obj(
              "id"                   -> Json.Obj("type" -> Json.Str("string")),
              "title"                -> Json.Obj("type" -> Json.Str("string")),
              "priority"             -> Json.Obj("type" -> Json.Str("string")),
              "assignedAgent"        -> Json.Obj("type" -> Json.Str("string")),
              "requiredCapabilities" -> Json.Obj(
                "type"  -> Json.Str("array"),
                "items" -> Json.Obj("type" -> Json.Str("string")),
              ),
              "blockedBy"            -> Json.Obj(
                "type"  -> Json.Str("array"),
                "items" -> Json.Obj("type" -> Json.Str("string")),
              ),
              "tags"                 -> Json.Obj(
                "type"  -> Json.Str("array"),
                "items" -> Json.Obj("type" -> Json.Str("string")),
              ),
              "acceptanceCriteria"   -> Json.Obj(
                "type"  -> Json.Str("array"),
                "items" -> Json.Obj("type" -> Json.Str("string")),
              ),
              "estimate"             -> Json.Obj("type" -> Json.Str("string")),
              "proofOfWork"          -> Json.Obj(
                "type"  -> Json.Str("array"),
                "items" -> Json.Obj("type" -> Json.Str("string")),
              ),
              "body"                 -> Json.Obj("type" -> Json.Str("string")),
            ),
            "required"             -> Json.Arr(
              Chunk(
                Json.Str("title"),
                Json.Str("priority"),
                Json.Str("requiredCapabilities"),
                Json.Str("blockedBy"),
                Json.Str("tags"),
                Json.Str("acceptanceCriteria"),
                Json.Str("proofOfWork"),
                Json.Str("body"),
              )
            ),
            "additionalProperties" -> Json.Bool(false),
          ),
        ),
      ),
      "required"             -> Json.Arr(Chunk(Json.Str("summary"), Json.Str("issues"))),
      "additionalProperties" -> Json.Bool(false),
    )
