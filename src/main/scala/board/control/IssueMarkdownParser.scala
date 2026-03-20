package board.control

import java.time.Instant

import zio.*

import board.entity.*
import shared.ids.Ids.BoardIssueId

trait IssueMarkdownParser:
  def parse(raw: String): IO[BoardError, (IssueFrontmatter, String)]
  def render(frontmatter: IssueFrontmatter, body: String): UIO[String]
  def updateFrontmatter(raw: String, update: IssueFrontmatter => IssueFrontmatter): IO[BoardError, String]

object IssueMarkdownParser:
  val live: ULayer[IssueMarkdownParser] = ZLayer.succeed(IssueMarkdownParserLive())

  def parse(raw: String): ZIO[IssueMarkdownParser, BoardError, (IssueFrontmatter, String)] =
    ZIO.serviceWithZIO[IssueMarkdownParser](_.parse(raw))

  def render(frontmatter: IssueFrontmatter, body: String): ZIO[IssueMarkdownParser, Nothing, String] =
    ZIO.serviceWithZIO[IssueMarkdownParser](_.render(frontmatter, body))

  def updateFrontmatter(
    raw: String,
    update: IssueFrontmatter => IssueFrontmatter,
  ): ZIO[IssueMarkdownParser, BoardError, String] =
    ZIO.serviceWithZIO[IssueMarkdownParser](_.updateFrontmatter(raw, update))

final case class IssueMarkdownParserLive() extends IssueMarkdownParser:
  override def parse(raw: String): IO[BoardError, (IssueFrontmatter, String)] =
    for
      (yaml, markdownBody) <- splitFrontmatter(raw)
      fields               <- parseYamlFields(yaml)
      frontmatter          <- parseFrontmatter(fields)
    yield (frontmatter, markdownBody)

  override def render(frontmatter: IssueFrontmatter, body: String): UIO[String] =
    ZIO.succeed {
      val yamlLines = List(
        s"id: ${frontmatter.id.value}",
        s"title: ${frontmatter.title}",
        s"priority: ${renderPriority(frontmatter.priority)}",
        s"assignedAgent: ${renderOpt(frontmatter.assignedAgent)}",
        s"requiredCapabilities: ${renderList(frontmatter.requiredCapabilities)}",
        s"blockedBy: ${renderList(frontmatter.blockedBy.map(_.value))}",
        s"tags: ${renderList(frontmatter.tags)}",
        s"acceptanceCriteria: ${renderList(frontmatter.acceptanceCriteria)}",
        s"estimate: ${renderEstimate(frontmatter.estimate)}",
        s"proofOfWork: ${renderList(frontmatter.proofOfWork)}",
        s"transientState: ${renderTransientState(frontmatter.transientState)}",
        s"branchName: ${renderOpt(frontmatter.branchName)}",
        s"failureReason: ${renderOpt(frontmatter.failureReason)}",
        s"completedAt: ${renderInstantOpt(frontmatter.completedAt)}",
        s"createdAt: ${frontmatter.createdAt}",
      )

      s"---\n${yamlLines.mkString("\n")}\n---\n$body"
    }

  override def updateFrontmatter(raw: String, update: IssueFrontmatter => IssueFrontmatter): IO[BoardError, String] =
    for
      (frontmatter, body) <- parse(raw)
      rendered            <- render(update(frontmatter), body)
    yield rendered

  private def splitFrontmatter(raw: String): IO[BoardError, (String, String)] =
    val lines = raw.split("\n", -1).toList
    lines match
      case Nil                                => ZIO.fail(BoardError.ParseError("ISSUE.md is empty"))
      case head :: tail if head.trim == "---" =>
        val closingIndexInTail = tail.indexWhere(_.trim == "---")
        if closingIndexInTail < 0 then ZIO.fail(BoardError.ParseError("Missing closing '---' delimiter in frontmatter"))
        else
          val yaml = tail.take(closingIndexInTail).mkString("\n")
          val body = tail.drop(closingIndexInTail + 1).mkString("\n")
          ZIO.succeed((yaml, body))
      case _                                  =>
        ZIO.fail(BoardError.ParseError("Frontmatter must start with '---' delimiter"))

  private def parseYamlFields(yaml: String): IO[BoardError, Map[String, String]] =
    val init   = Right(Map.empty[String, String]): Either[BoardError, Map[String, String]]
    val parsed = yaml.linesIterator.zipWithIndex.foldLeft(init) {
      case (acc, (line, index)) =>
        acc.flatMap { current =>
          val trimmed = line.trim
          if trimmed.isEmpty || trimmed.startsWith("#") then Right(current)
          else
            val parts = line.split(":", 2)
            if parts.length != 2 then
              Left(BoardError.ParseError(s"Invalid frontmatter line ${index + 1}: '$line'"))
            else
              val key   = parts(0).trim
              val value = parts(1).trim
              if key.isEmpty then Left(BoardError.ParseError(s"Missing key at frontmatter line ${index + 1}"))
              else Right(current.updated(key, value))
        }
    }
    ZIO.fromEither(parsed)

  private def parseFrontmatter(fields: Map[String, String]): IO[BoardError, IssueFrontmatter] =
    for
      idString             <- requiredField(fields, "id")
      id                   <- parseBoardIssueId(idString)
      title                <- requiredField(fields, "title")
      priorityRaw          <- requiredField(fields, "priority")
      priority             <- parsePriority(priorityRaw)
      assignedAgent        <- parseOptionalString(fields, "assignedAgent")
      requiredCapabilities <- parseListField(fields, "requiredCapabilities")
      blockedByRaw         <- parseListField(fields, "blockedBy")
      blockedBy            <- parseBoardIssueIds(blockedByRaw)
      tags                 <- parseListField(fields, "tags")
      acceptanceCriteria   <- parseListField(fields, "acceptanceCriteria")
      estimate             <- parseEstimateOptional(fields.get("estimate"))
      proofOfWork          <- parseListField(fields, "proofOfWork")
      transientStateRaw    <- requiredField(fields, "transientState")
      transientState       <- parseTransientState(transientStateRaw)
      branchName           <- parseOptionalString(fields, "branchName")
      failureReason        <- parseOptionalString(fields, "failureReason")
      completedAt          <- parseInstantOptional(fields, "completedAt")
      createdAtRaw         <- requiredField(fields, "createdAt")
      createdAt            <- parseInstant("createdAt", createdAtRaw)
    yield IssueFrontmatter(
      id = id,
      title = title,
      priority = priority,
      assignedAgent = assignedAgent,
      requiredCapabilities = requiredCapabilities,
      blockedBy = blockedBy,
      tags = tags,
      acceptanceCriteria = acceptanceCriteria,
      estimate = estimate,
      proofOfWork = proofOfWork,
      transientState = transientState,
      branchName = branchName,
      failureReason = failureReason,
      completedAt = completedAt,
      createdAt = createdAt,
    )

  private def requiredField(fields: Map[String, String], key: String): IO[BoardError, String] =
    fields
      .get(key)
      .map(_.trim)
      .filter(_.nonEmpty)
      .filterNot(isNullLiteral)
      .fold[IO[BoardError, String]](ZIO.fail(BoardError.ParseError(s"Missing required field '$key'")))(ZIO.succeed)

  private def parseOptionalString(fields: Map[String, String], key: String): IO[BoardError, Option[String]] =
    ZIO.succeed(fields.get(key).map(_.trim).filter(value => value.nonEmpty && !isNullLiteral(value)))

  private def parseListField(fields: Map[String, String], key: String): IO[BoardError, List[String]] =
    fields.get(key) match
      case None        => ZIO.fail(BoardError.ParseError(s"Missing list field '$key'"))
      case Some(value) => parseListValue(key, value)

  private def parseListValue(key: String, value: String): IO[BoardError, List[String]] =
    val trimmed = value.trim
    if isNullLiteral(trimmed) || trimmed == "[]" then ZIO.succeed(Nil)
    else if trimmed.startsWith("[") && trimmed.endsWith("]") then
      val inner = trimmed.drop(1).dropRight(1).trim
      if inner.isEmpty then ZIO.succeed(Nil)
      else ZIO.succeed(inner.split(",").toList.map(_.trim).filter(_.nonEmpty))
    else ZIO.fail(BoardError.ParseError(s"Field '$key' must use flow-list syntax [a, b], found '$value'"))

  private def parsePriority(value: String): IO[BoardError, IssuePriority] =
    value.trim.toLowerCase match
      case "critical" => ZIO.succeed(IssuePriority.Critical)
      case "high"     => ZIO.succeed(IssuePriority.High)
      case "medium"   => ZIO.succeed(IssuePriority.Medium)
      case "low"      => ZIO.succeed(IssuePriority.Low)
      case other      => ZIO.fail(BoardError.ParseError(s"Unsupported priority '$other'"))

  private def parseEstimateOptional(raw: Option[String]): IO[BoardError, Option[IssueEstimate]] =
    raw match
      case None                                                           => ZIO.succeed(None)
      case Some(value) if value.trim.isEmpty || isNullLiteral(value.trim) => ZIO.succeed(None)
      case Some(value)                                                    => parseEstimate(value).map(Some(_))

  private def parseEstimate(value: String): IO[BoardError, IssueEstimate] =
    value.trim.toUpperCase match
      case "XS"  => ZIO.succeed(IssueEstimate.XS)
      case "S"   => ZIO.succeed(IssueEstimate.S)
      case "M"   => ZIO.succeed(IssueEstimate.M)
      case "L"   => ZIO.succeed(IssueEstimate.L)
      case "XL"  => ZIO.succeed(IssueEstimate.XL)
      case other => ZIO.fail(BoardError.ParseError(s"Unsupported estimate '$other'"))

  private def parseBoardIssueId(value: String): IO[BoardError, BoardIssueId] =
    ZIO.fromEither(BoardIssueId.fromString(value).left.map(BoardError.ParseError.apply))

  private def parseBoardIssueIds(values: List[String]): IO[BoardError, List[BoardIssueId]] =
    ZIO.foreach(values)(parseBoardIssueId)

  private def parseInstant(field: String, value: String): IO[BoardError, Instant] =
    ZIO
      .attempt(Instant.parse(value.trim))
      .mapError(err => BoardError.ParseError(s"Invalid timestamp for '$field': ${err.getMessage}"))

  private def parseInstantOptional(fields: Map[String, String], key: String): IO[BoardError, Option[Instant]] =
    fields.get(key) match
      case None        => ZIO.succeed(None)
      case Some(value) =>
        val trimmed = value.trim
        if trimmed.isEmpty || isNullLiteral(trimmed) then ZIO.succeed(None)
        else parseInstant(key, trimmed).map(Some(_))

  private def parseTransientState(value: String): IO[BoardError, TransientState] =
    val trimmed = value.trim
    if trimmed.equalsIgnoreCase("none") || isNullLiteral(trimmed) then ZIO.succeed(TransientState.None)
    else if trimmed.toLowerCase.startsWith("review(") && trimmed.endsWith(")") then
      // Backward compatibility: legacy board issues could persist `review(agent,ts)`.
      // Current model does not expose a dedicated Review transient state, and board column
      // already encodes HumanReview. Normalize to `none` so existing issues remain readable.
      ZIO.succeed(TransientState.None)
    else if trimmed.toLowerCase.startsWith("assigned(") && trimmed.endsWith(")") then
      val inner      = trimmed.drop("assigned(".length).dropRight(1)
      val splitIndex = inner.lastIndexOf(',')
      if splitIndex <= 0 then ZIO.fail(BoardError.ParseError(s"Invalid assigned transientState '$value'"))
      else
        val agent = inner.take(splitIndex).trim
        val atRaw = inner.drop(splitIndex + 1).trim
        if agent.isEmpty then ZIO.fail(BoardError.ParseError(s"Invalid assigned transientState '$value'"))
        else parseInstant("transientState.assigned.at", atRaw).map(at => TransientState.Assigned(agent, at))
    else if trimmed.toLowerCase.startsWith("merging(") && trimmed.endsWith(")") then
      val atRaw = trimmed.drop("merging(".length).dropRight(1).trim
      parseInstant("transientState.merging.at", atRaw).map(TransientState.Merging.apply)
    else if trimmed.toLowerCase.startsWith("rework(") && trimmed.endsWith(")") then
      val inner      = trimmed.drop("rework(".length).dropRight(1)
      val splitIndex = inner.lastIndexOf(',')
      if splitIndex <= 0 then ZIO.fail(BoardError.ParseError(s"Invalid rework transientState '$value'"))
      else
        val reason = inner.take(splitIndex).trim
        val atRaw  = inner.drop(splitIndex + 1).trim
        if reason.isEmpty then ZIO.fail(BoardError.ParseError(s"Invalid rework transientState '$value'"))
        else parseInstant("transientState.rework.at", atRaw).map(at => TransientState.Rework(reason, at))
    else ZIO.fail(BoardError.ParseError(s"Unsupported transientState '$value'"))

  private def renderPriority(priority: IssuePriority): String =
    priority match
      case IssuePriority.Critical => "critical"
      case IssuePriority.High     => "high"
      case IssuePriority.Medium   => "medium"
      case IssuePriority.Low      => "low"

  private def renderEstimate(estimate: Option[IssueEstimate]): String =
    estimate match
      case None                   => "null"
      case Some(IssueEstimate.XS) => "xs"
      case Some(IssueEstimate.S)  => "s"
      case Some(IssueEstimate.M)  => "m"
      case Some(IssueEstimate.L)  => "l"
      case Some(IssueEstimate.XL) => "xl"

  private def renderTransientState(state: TransientState): String =
    state match
      case TransientState.None                => "none"
      case TransientState.Assigned(agent, at) => s"assigned($agent,$at)"
      case TransientState.Merging(at)         => s"merging($at)"
      case TransientState.Rework(reason, at)  => s"rework($reason,$at)"

  private def renderList(values: List[String]): String =
    if values.isEmpty then "[]" else s"[${values.mkString(", ")}]"

  private def renderOpt(value: Option[String]): String = value.getOrElse("null")

  private def renderInstantOpt(value: Option[Instant]): String = value.map(_.toString).getOrElse("null")

  private def isNullLiteral(value: String): Boolean = value.equalsIgnoreCase("null")
