package llm4zio.flow

import zio.json.JsonCodec

/** A single unit of work within a [[Plan]]. */
final case class Task(title: String, description: String, completed: Boolean = false) derives JsonCodec

/** An ordered list of [[Task]]s tied to a branch/epic id.
  *
  * Plans persist as plain Markdown (no datastore), so a run can be resumed by re-reading the file and continuing from
  * the first incomplete task.
  */
final case class Plan(epicId: String, tasks: List[Task]) derives JsonCodec:

  /** The first task not yet completed, or None when the plan is fully done. */
  def nextIncomplete: Option[Task] = tasks.find(!_.completed)

  /** Mark the task with the given title completed; other tasks are unchanged. */
  def complete(title: String): Plan =
    copy(tasks = tasks.map(t => if t.title == title then t.copy(completed = true) else t))

  /** Render to the canonical Markdown form that [[Plan.parse]] round-trips. */
  def render: String =
    val header = s"${Plan.HeaderPrefix}$epicId"
    val body   = tasks.map { t =>
      val box = if t.completed then "[x]" else "[ ]"
      s"## $box ${t.title}\n${t.description}".stripTrailing
    }
    (header +: body).mkString("\n\n")

object Plan:
  private val HeaderPrefix = "# Plan: "
  private val TaskHeader   = """## \[([ xX])\] (.+)""".r

  /** Parse the Markdown produced by [[Plan.render]]. Left on a malformed document. */
  def parse(markdown: String): Either[String, Plan] =
    markdown.strip.linesIterator.toList match
      case head :: rest if head.startsWith(HeaderPrefix) =>
        parseTasks(rest.mkString("\n").strip).map(Plan(head.drop(HeaderPrefix.length).trim, _))
      case _                                             =>
        Left(s"expected a '$HeaderPrefix<epicId>' header")

  private def parseTasks(body: String): Either[String, List[Task]] =
    if body.isEmpty then Right(Nil)
    else
      val chunks = body.split("(?m)^(?=## )").toList.map(_.strip).filter(_.nonEmpty)
      chunks.foldRight[Either[String, List[Task]]](Right(Nil)) { (chunk, acc) =>
        for
          rest <- acc
          task <- parseTask(chunk)
        yield task :: rest
      }

  private def parseTask(chunk: String): Either[String, Task] =
    chunk.linesIterator.toList match
      case TaskHeader(box, title) :: descLines =>
        Right(Task(title.trim, descLines.mkString("\n").strip, completed = box.equalsIgnoreCase("x")))
      case head :: _                           => Left(s"malformed task header: $head")
      case Nil                                 => Left("empty task chunk")
