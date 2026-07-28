package llm4zio.tools

import zio.*
import zio.json.*
import zio.json.ast.Json

import llm4zio.core.*

trait ToolRegistry:
  def register(tool: Tool): IO[ToolExecutionError, Unit]
  def registerAll(tools: List[Tool]): IO[ToolExecutionError, Unit]
  def get(name: String): IO[LlmError, Tool]
  def list: UIO[List[Tool]]
  def select(prompt: String, limit: Int = 8): UIO[List[Tool]]
  def validate(call: ToolCall): IO[LlmError, (Tool, Json)]
  def execute(call: ToolCall): IO[LlmError, ToolResult]
  def executeAll(calls: List[ToolCall]): IO[LlmError, List[ToolResult]]

object ToolRegistry:
  def make: UIO[ToolRegistry] =
    for
      state <- Ref.make(Map.empty[String, Tool])
    yield new ToolRegistry:
      override def register(tool: Tool): IO[ToolExecutionError, Unit] =
        state.modify { tools =>
          if tools.contains(tool.name) then
            (ZIO.fail(ToolExecutionError.DuplicateToolName(tool.name)), tools)
          else
            (ZIO.unit, tools + (tool.name -> tool))
        }.flatten

      override def registerAll(tools: List[Tool]): IO[ToolExecutionError, Unit] =
        ZIO.foreachDiscard(tools)(register)

      override def get(name: String): IO[LlmError, Tool] =
        state.get.flatMap { tools =>
          ZIO
            .fromOption(tools.get(name))
            .orElseFail(LlmError.ToolError(name, s"Tool not found: $name"))
        }

      override def list: UIO[List[Tool]] =
        state.get.map(_.values.toList.sortBy(_.name))

      override def select(prompt: String, limit: Int = 8): UIO[List[Tool]] =
        val words = prompt.toLowerCase.split("[^a-z0-9_]+").toSet.filter(_.nonEmpty)
        list.map(_.map(tool => (tool, score(tool, words))).filter(
          _._2 > 0
        ).sortBy((_, toolScore) => -toolScore).take(limit).map(_._1))

      override def validate(call: ToolCall): IO[LlmError, (Tool, Json)] =
        for
          tool <- get(call.name)
          args <- parseArguments(call)
          _    <- ToolSchemaValidator
                    .validate(tool.parameters, args)
                    .mapError(err => LlmError.ToolError(tool.name, err.message))
        yield (tool, args)

      override def execute(call: ToolCall): IO[LlmError, ToolResult] =
        for
          validated   <- validate(call)
          (tool, args) = validated
          grants      <- Grants.current
          missing      = tool.requires.filterNot(grants.allows)
          result      <-
            // A denial goes back to the model in the value channel — probing a boundary is expected behavior and
            // must not fail the agentic loop. The tool body never runs.
            if missing.nonEmpty then
              val violation = ToolExecutionError.SandboxViolation(
                s"Tool '${tool.name}' requires ungranted capabilities: ${missing.toList.map(_.toString).sorted.mkString(", ")}"
              )
              ZIO.succeed(ToolResult(toolCallId = call.id, result = Left(violation.message), denied = true))
            else
              tool.execute(args)
                .mapBoth(
                  err => LlmError.ToolError(tool.name, err.message),
                  identity,
                )
                .either
                .map(r => ToolResult(toolCallId = call.id, result = r.left.map(toMessage)))
        yield result

      override def executeAll(calls: List[ToolCall]): IO[LlmError, List[ToolResult]] =
        ZIO.foreach(calls)(execute)

  private def parseArguments(call: ToolCall): IO[LlmError, Json] =
    ZIO
      .fromEither(call.arguments.fromJson[Json])
      .mapError(err => LlmError.ToolError(call.name, s"Invalid arguments JSON: $err"))

  private def score(tool: Tool, words: Set[String]): Int =
    if words.isEmpty then 0
    else
      words.foldLeft(0) { (acc, word) =>
        val nameBoost        = if tool.name.toLowerCase.contains(word) then 5 else 0
        val tagBoost         = if tool.tags.exists(_.toLowerCase == word) then 4 else 0
        val descriptionBoost = if tool.description.toLowerCase.contains(word) then 2 else 0
        acc + nameBoost + tagBoost + descriptionBoost
      }

  private def toMessage(error: LlmError): String =
    error match
      case LlmError.ToolError(_, message)        => message
      case LlmError.InvalidRequestError(message) => message
      case LlmError.ParseError(message, _)       => message
      case LlmError.ProviderError(message, _)    => message
      case other                                 => other.toString

  val layer: ULayer[ToolRegistry] = ZLayer.fromZIO(make)

object ToolSchemaValidator:
  def validate(schema: JsonSchema, args: Json): IO[ToolExecutionError, Unit] =
    for
      schemaFields <- asObject(schema, "Tool schema must be a JSON object")
      argFields    <- asObject(args, "Tool arguments must be a JSON object")
      _            <- validateRequired(schemaFields, argFields)
      _            <- validateTypes(schemaFields, argFields)
    yield ()

  private def validateRequired(
    schemaFields: Map[String, Json],
    argFields: Map[String, Json],
  ): IO[ToolExecutionError, Unit] =
    val required = schemaFields.get("required") match
      case Some(Json.Arr(values)) => values.collect { case Json.Str(key) => key }.toSet
      case _                      => Set.empty[String]

    val missing = required.diff(argFields.keySet)
    if missing.isEmpty then ZIO.unit
    else
      ZIO.fail(
        ToolExecutionError.InvalidParameters(s"Missing required parameters: ${missing.toList.sorted.mkString(", ")}")
      )

  private def validateTypes(
    schemaFields: Map[String, Json],
    argFields: Map[String, Json],
  ): IO[ToolExecutionError, Unit] =
    val properties = schemaFields.get("properties") match
      case Some(Json.Obj(fields)) => fields.toMap
      case _                      => Map.empty[String, Json]

    ZIO.foreachDiscard(argFields.toList) {
      case (name, value) =>
        properties.get(name) match
          case None         => ZIO.unit
          case Some(schema) =>
            val expectedType = extractType(schema)
            if isOfType(value, expectedType) then ZIO.unit
            else ZIO.fail(ToolExecutionError.InvalidParameters(s"Invalid type for '$name'. Expected: $expectedType"))
    }

  private def asObject(json: Json, message: String): IO[ToolExecutionError, Map[String, Json]] =
    json match
      case Json.Obj(fields) => ZIO.succeed(fields.toMap)
      case _                => ZIO.fail(ToolExecutionError.InvalidParameters(message))

  private def extractType(schema: Json): String =
    schema match
      case Json.Obj(fields) =>
        fields.toMap.get("type") match
          case Some(Json.Str(value)) => value
          case _                     => "object"
      case _                => "object"

  private def isOfType(value: Json, expectedType: String): Boolean =
    expectedType match
      case "string"  => value.isInstanceOf[Json.Str]
      case "boolean" => value.isInstanceOf[Json.Bool]
      case "integer" => value match
          case Json.Num(number) => number.stripTrailingZeros.scale() <= 0
          case _                => false
      case "number"  => value.isInstanceOf[Json.Num]
      case "array"   => value.isInstanceOf[Json.Arr]
      case "object"  => value.isInstanceOf[Json.Obj]
      case _         => true

object ToolProviderMapper:
  def toProviderFormat(provider: LlmProvider, tools: List[Tool]): Json =
    provider match
      case LlmProvider.OpenAI    => toOpenAI(tools)
      case LlmProvider.Anthropic => toAnthropic(tools)
      case LlmProvider.GeminiApi => toGemini(tools)
      case _                     => Json.Arr(Chunk.empty)

  def toOpenAI(tools: List[Tool]): Json =
    Json.Arr(Chunk.fromIterable(tools.map { tool =>
      Json.Obj(
        "type"     -> Json.Str("function"),
        "function" -> Json.Obj(
          "name"        -> Json.Str(tool.name),
          "description" -> Json.Str(tool.description),
          "parameters"  -> tool.parameters,
        ),
      )
    }))

  def toAnthropic(tools: List[Tool]): Json =
    Json.Arr(Chunk.fromIterable(tools.map { tool =>
      Json.Obj(
        "name"         -> Json.Str(tool.name),
        "description"  -> Json.Str(tool.description),
        "input_schema" -> tool.parameters,
      )
    }))

  def toGemini(tools: List[Tool]): Json =
    Json.Obj(
      "functionDeclarations" -> Json.Arr(Chunk.fromIterable(tools.map { tool =>
        Json.Obj(
          "name"        -> Json.Str(tool.name),
          "description" -> Json.Str(tool.description),
          "parameters"  -> tool.parameters,
        )
      }))
    )

// maxDenials: capability-denial budget — None (default) lets the model keep probing denied tools until it gives up
// or hits maxIterations; Some(n) aborts the loop once n denials have accumulated, so a model stuck on a boundary
// can't burn the whole iteration budget.
case class ToolLoopConfig(maxIterations: Int = 8, maxDenials: Option[Int] = None)

object ToolCallingExecutor:
  def run(
    prompt: String,
    tools: List[Tool],
    llmService: LlmService,
    registry: ToolRegistry,
    config: ToolLoopConfig = ToolLoopConfig(),
  ): IO[LlmError, LlmResponse] =
    loop(
      prompt = prompt,
      tools = tools,
      llmService = llmService,
      registry = registry,
      iteration = 0,
      denials = 0,
      config = config,
    )

  private def loop(
    prompt: String,
    tools: List[Tool],
    llmService: LlmService,
    registry: ToolRegistry,
    iteration: Int,
    denials: Int,
    config: ToolLoopConfig,
  ): IO[LlmError, LlmResponse] =
    if iteration >= config.maxIterations then
      ZIO.fail(LlmError.InvalidRequestError(s"Tool call loop reached max iterations (${config.maxIterations})"))
    else
      for
        response      <- llmService.executeWithTools(prompt, tools)
        finalResponse <-
          if response.toolCalls.isEmpty then
            ZIO.succeed(
              LlmResponse(
                content = response.content.getOrElse(""),
                metadata = Map(
                  "finish_reason"   -> response.finishReason,
                  "tool_iterations" -> iteration.toString,
                ),
              )
            )
          else
            for
              results     <- registry.executeAll(response.toolCalls)
              totalDenials = denials + results.count(_.denied)
              _           <- ZIO
                               .fail(LlmError.InvalidRequestError(
                                 s"Tool loop exceeded the capability-denial budget (${config.maxDenials.getOrElse(0)})"
                               ))
                               .when(config.maxDenials.exists(totalDenials >= _))
              nextPrompt   = buildFollowUpPrompt(prompt, response, results)
              continued   <- loop(
                               prompt = nextPrompt,
                               tools = tools,
                               llmService = llmService,
                               registry = registry,
                               iteration = iteration + 1,
                               denials = totalDenials,
                               config = config,
                             )
            yield continued
      yield finalResponse

  private def buildFollowUpPrompt(
    previousPrompt: String,
    response: ToolCallResponse,
    results: List[ToolResult],
  ): String =
    val renderedResults =
      results.map { result =>
        val value = result.result match
          case Right(json)    => json.toJson
          case Left(errorMsg) => s"{\"error\": ${errorMsg.toJson}}"
        s"tool_call_id=${result.toolCallId} result=$value"
      }.mkString("\n")

    s"""$previousPrompt
       |
       |Assistant requested tool execution with finishReason='${response.finishReason}'.
       |Tool results:
       |$renderedResults
       |
       |Continue the task. If no further tools are needed, respond with final user-facing content.
       |""".stripMargin
