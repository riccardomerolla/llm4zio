package llm4zio.flow

import scala.io.Source

import zio.json.JsonCodec
import zio.stream.Stream
import zio.{ IO, UIO }

import llm4zio.core.*
import llm4zio.tools.{ AnyTool, JsonSchema }

/** A named review lens: a system prompt + an optional changed-file regex scope. Run by wrapping a base connector with
  * [[asService]], which injects the lens into the reviewer's structured calls.
  */
final case class Reviewer(name: String, systemPrompt: String, files: Option[String]):

  /** True if this reviewer should run given the changed files. No scope ⇒ always. An empty/unknown file list also runs
    * (we can't scope out a reviewer without file information). Otherwise at least one changed file must match.
    */
  def matches(changedFiles: List[String]): Boolean =
    files match
      case None                            => true
      case Some(_) if changedFiles.isEmpty => true
      case Some(regex)                     => changedFiles.exists(_.matches(regex))

  /** Wrap `base` so its structured calls carry this reviewer's lens (system prompt prepended). Streaming and tool
    * methods pass through unchanged.
    */
  def asService(base: LlmService): LlmService = new LlmService:
    def executeStream(prompt: String): Stream[LlmError, LlmChunk]                              = base.executeStream(prompt)
    def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk]          =
      base.executeStreamWithHistory(messages)
    def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
      base.executeWithTools(prompt, tools)
    def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A]   =
      base.executeStructured(s"$systemPrompt\n\n$prompt", schema)
    override def executeStructuredWithUsage[A: JsonCodec](
      prompt: String,
      schema: JsonSchema,
    ): IO[LlmError, (A, Option[TokenUsage], Option[String])] =
      base.executeStructuredWithUsage(s"$systemPrompt\n\n$prompt", schema)
    def isAvailable: UIO[Boolean]                                                              = base.isAvailable

object Reviewer:
  /** Load a reviewer from `llm4zio/review/reviewers/<slug>.md` — frontmatter (`files` scope) + body prompt, delimited
    * by `---` lines. The roster resources are baked into the jar, so absence is a packaging error, not a runtime case.
    */
  def fromResource(slug: String): Reviewer =
    val src        = Source.fromResource(s"llm4zio/review/reviewers/$slug.md")
    val text       =
      try src.mkString
      finally src.close()
    val parts      = text.split("---\n", 3)
    val (fm, body) = if parts.length == 3 then (parts(1), parts(2).trim) else ("", text.trim)
    val files      = fm.linesIterator.collectFirst {
      case l if l.trim.startsWith("files:") => l.split("files:", 2)(1).trim
    }
    Reviewer(slug, body, files.filter(_.nonEmpty))
