package gateway.boundary.telegram

import gateway.entity.{ NormalizedMessage, TelegramInlineKeyboardMarkup }
import orchestration.control.WorkflowRunState

final case class FormattedTelegramResponse(
  text: String,
  parseMode: Option[String],
  replyMarkup: Option[TelegramInlineKeyboardMarkup],
  continuationToken: Option[String],
  remaining: Option[String],
)

object ResponseFormatter:
  def format(message: NormalizedMessage): FormattedTelegramResponse =
    val baseText        = formatStructured(message.content, message.metadata)
    val withAttachments = appendAttachments(baseText, message.metadata)
    val normalized      = withAttachments.trim

    FormattedTelegramResponse(
      text = normalized,
      parseMode = parseModeFor(normalized, message.metadata),
      replyMarkup = None,
      continuationToken = None,
      remaining = None,
    )

  def formatContinuation(token: String, remaining: String): FormattedTelegramResponse =
    val normalized = remaining.trim
    FormattedTelegramResponse(
      text = normalized,
      parseMode = parseModeFor(normalized, Map.empty),
      replyMarkup = None,
      continuationToken = None,
      remaining = None,
    )

  private def parseModeFor(content: String, metadata: Map[String, String]): Option[String] =
    metadata.get("telegram.parse_mode").orElse {
      if content.contains("```") then Some("Markdown") else None
    }

  private def formatStructured(content: String, metadata: Map[String, String]): String =
    metadata.get("content.type").map(_.trim.toLowerCase) match
      case Some("table") => toCodeFence(toMonospaceTable(content))
      case Some("list")  => toBulletList(content)
      case _             =>
        if looksLikeCsvTable(content) then toCodeFence(toMonospaceTable(content))
        else if looksLikeMarkdownTable(content) then toCodeFence(toMonospaceTable(content))
        else if looksLikeJson(content) then s"```json\n${content.trim}\n```"
        else if looksLikeCode(content) && !content.contains("```") then s"```\n${content.trim}\n```"
        else content

  private def appendAttachments(content: String, metadata: Map[String, String]): String =
    val attachments = metadata.values.filter(value =>
      value.toLowerCase.endsWith(".pdf") || value.toLowerCase.endsWith(".zip")
    ).toList.distinct

    if attachments.isEmpty then content
    else
      val lines = attachments.map(path => s"- $path").mkString("\n")
      s"$content\n\nGenerated attachments:\n$lines"

  private def looksLikeJson(content: String): Boolean =
    val trimmed = content.trim
    (trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))

  private def looksLikeCode(content: String): Boolean =
    val markers = List("{", "}", ";", "class ", "def ", "public ", "private ", "return ")
    val text    = content.toLowerCase
    markers.count(text.contains) >= 2

  private def looksLikeCsvTable(content: String): Boolean =
    val lines = content.linesIterator.toList.map(_.trim).filter(_.nonEmpty)
    lines.length >= 2 && lines.forall(_.contains(","))

  private def looksLikeMarkdownTable(content: String): Boolean =
    val lines = content.linesIterator.toList.map(_.trim).filter(_.nonEmpty)
    lines.sliding(2).exists {
      case List(header, divider) =>
        val headerCells  = parseMarkdownTableRow(header)
        val dividerCells = parseMarkdownTableRow(divider)
        headerCells.nonEmpty &&
        dividerCells.length == headerCells.length &&
        dividerCells.forall(cell => cell.matches("^:?-{3,}:?$"))
      case _                     => false
    }

  private def toMonospaceTable(content: String): String =
    val rows =
      if looksLikeCsvTable(content) then
        content.linesIterator.toList.map(_.trim).filter(_.nonEmpty).map(_.split(",").toList.map(_.trim))
      else
        content.linesIterator.toList
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(parseMarkdownTableRow)
          .filter(row => row.exists(_.nonEmpty))
          .filterNot(row => row.forall(cell => cell.matches("^:?-{3,}:?$")))
    rows match
      case Nil          => content
      case header :: xs =>
        val widthByCol = (header :: xs)
          .foldLeft(Vector.fill(header.length)(0)) { (acc, row) =>
            acc.zipAll(row.map(_.length).toVector, 0, 0).map { case (w, len) => math.max(w, len) }
          }
        val headerLine = formatRow(normalizeRow(header, widthByCol.length), widthByCol)
        val divider    = widthByCol.map("-" * _).mkString("-+-")
        val body       = xs.map(row => formatRow(normalizeRow(row, widthByCol.length), widthByCol))
        (headerLine :: divider :: body).mkString("\n")

  private def parseMarkdownTableRow(line: String): List[String] =
    val stripped = line.stripPrefix("|").stripSuffix("|")
    stripped.split("\\|", -1).toList.map(_.trim)

  private def normalizeRow(row: List[String], width: Int): List[String] =
    if row.length >= width then row.take(width) else row ++ List.fill(width - row.length)("")

  private def formatRow(row: List[String], widthByCol: Vector[Int]): String =
    row.zip(widthByCol).map { case (value, width) => value.padTo(width, ' ') }.mkString(" | ")

  private def toCodeFence(content: String): String =
    s"```\n${content.trim}\n```"

  private def toBulletList(content: String): String =
    content
      .linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(line => if line.startsWith("-") || line.startsWith("*") then line else s"- $line")
      .mkString("\n")

  def formatTaskProgress(
    runState: WorkflowRunState,
    taskName: String,
    stepName: Option[String],
  ): String =
    runState match
      case WorkflowRunState.Running   =>
        val header = s"""▶ Task "$taskName" started"""
        stepName match
          case Some(step) if step.trim.nonEmpty => s"$header\n→ Step: ${step.trim} — Running..."
          case _                                => header
      case WorkflowRunState.Completed =>
        s"""✓ Task "$taskName" completed"""
      case WorkflowRunState.Failed    =>
        val header = s"""✗ Task "$taskName" failed"""
        stepName match
          case Some(step) if step.trim.nonEmpty => s"$header at step: ${step.trim}"
          case _                                => header
      case WorkflowRunState.Paused    =>
        s"""⏸ Task "$taskName" paused"""
      case WorkflowRunState.Cancelled =>
        s"""⛔ Task "$taskName" cancelled"""
      case WorkflowRunState.Pending   =>
        s"""⏳ Task "$taskName" pending"""
