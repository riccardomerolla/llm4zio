package llm4zio.flow

import zio.json.JsonCodec

/** A single unit of work within a [[Plan]]. */
final case class Task(title: String, description: String, completed: Boolean = false) derives JsonCodec

/** An ordered list of [[Task]]s tied to a branch/epic id.
  *
  * Plans persist as plain Markdown (no datastore), so a run can be resumed by re-reading the file and continuing from
  * the first incomplete task.
  */
final case class Plan(epicId: String, tasks: List[Task], brief: Option[String] = None) derives JsonCodec:

  /** The first task not yet completed, or None when the plan is fully done. */
  def nextIncomplete: Option[Task] = tasks.find(!_.completed)

  /** Mark the task with the given title completed; other tasks are unchanged. */
  def complete(title: String): Plan =
    copy(tasks = tasks.map(t => if t.title == title then t.copy(completed = true) else t))

  /** The prompt to hand a coding agent for `task`: the shared codebase [[brief]] (if any) prepended to the task's own
    * description, so a cold agent doesn't re-discover what the planner already learned.
    */
  def taskPrompt(task: Task): String =
    brief.map(_.trim).filter(_.nonEmpty).fold(task.description)(b => s"$b\n\n---\n\n${task.description}")

  /** Render to the canonical Markdown form that [[Plan.parse]] round-trips. A non-empty [[brief]] rides in a trailing
    * `# Brief` section, so the single plan file persists/resumes/cleans it up with no sidecar.
    */
  def render: String =
    val header = s"${Plan.HeaderPrefix}$epicId"
    val body   = tasks.map { t =>
      val box = if t.completed then "[x]" else "[ ]"
      s"## $box ${t.title}\n${t.description}".stripTrailing
    }
    val core   = (header +: body).mkString("\n\n")
    brief.map(_.trim).filter(_.nonEmpty).fold(core)(b => s"$core\n\n${Plan.BriefHeader}\n\n$b")

object Plan:
  private val HeaderPrefix = "# Plan: "
  private val BriefHeader  = "# Brief"
  private val TaskHeader   = """## \[([ xX])\] (.+)""".r

  /** Parse the Markdown produced by [[Plan.render]]. Left on a malformed document. */
  def parse(markdown: String): Either[String, Plan] =
    markdown.strip.linesIterator.toList match
      case head :: rest if head.startsWith(HeaderPrefix) =>
        val (taskBody, brief) = splitBrief(rest.mkString("\n").strip)
        parseTasks(taskBody).map(Plan(head.drop(HeaderPrefix.length).trim, _, brief))
      case _                                             =>
        Left(s"expected a '$HeaderPrefix<epicId>' header")

  /** Split a rendered body into its task block and an optional trailing `# Brief` section. */
  private def splitBrief(body: String): (String, Option[String]) =
    val lines = body.linesIterator.toList
    lines.indexWhere(_.trim == BriefHeader) match
      case -1  => (body, None)
      case idx =>
        val brief = lines.drop(idx + 1).mkString("\n").strip
        (lines.take(idx).mkString("\n").strip, Option.when(brief.nonEmpty)(brief))

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
