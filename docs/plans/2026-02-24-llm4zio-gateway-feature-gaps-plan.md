# LLM Gateway Feature Gaps — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Close the gap between llm4zio library capabilities and gateway UX across 5 milestones: Tool Calling, Streaming Quality, Observability, Agent System + Memory, RAG + Guardrails.

**Architecture:** Each milestone is a vertical slice — library implementation first, then gateway wiring, then UX surface. All HTTP tool-calling uses existing `httpClient.postJson` + zio-json DTOs. UX uses Scalatags + HTMX fragments consistent with existing views.

**Tech Stack:** Scala 3, ZIO 2.x, ZIO HTTP, zio-json, Scalatags, HTMX 2.0.4, Tailwind CSS v4, EclipseStore

---

## Milestone 1: Tool Calling

### Task 1: OpenAI tool calling — DTOs

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/OpenAIModels.scala`

**Context:** OpenAI's chat completions API accepts a `tools` parameter (array of function descriptors) and returns `tool_calls` in `choices[0].message`. We need new request/response DTOs.

**Step 1: Add tool-calling DTOs to OpenAIModels.scala**

Append these case classes at the end of the file (after `OpenAITokenUsage`):

```scala
// Tool calling DTOs
case class OpenAIFunction(
  name: String,
  description: String,
  parameters: zio.json.ast.Json,
) derives JsonCodec

case class OpenAITool(
  `type`: String = "function",
  function: OpenAIFunction,
) derives JsonCodec

case class OpenAIToolCall(
  id: String,
  `type`: String,
  function: OpenAIToolCallFunction,
) derives JsonCodec

case class OpenAIToolCallFunction(
  name: String,
  arguments: String,
) derives JsonCodec

// Extend ChatMessage to support optional tool_calls (for responses)
case class ChatMessageWithTools(
  role: String,
  content: Option[String] = None,
  tool_calls: Option[List[OpenAIToolCall]] = None,
) derives JsonCodec

// Extend ChatChoice to use ChatMessageWithTools
case class ChatChoiceWithTools(
  index: Int = 0,
  message: Option[ChatMessageWithTools] = None,
  finish_reason: Option[String] = None,
) derives JsonCodec

case class ChatCompletionResponseWithTools(
  id: Option[String] = None,
  choices: List[ChatChoiceWithTools],
  usage: Option[OpenAITokenUsage] = None,
  model: Option[String] = None,
) derives JsonCodec

// Request with tools
case class ChatCompletionRequestWithTools(
  model: String,
  messages: List[ChatMessage],
  tools: List[OpenAITool],
  temperature: Option[Double] = None,
  max_tokens: Option[Int] = None,
  stream: Option[Boolean] = Some(false),
) derives JsonCodec
```

**Step 2: Run compilation check**

```bash
cd /Users/riccardo/git/github/riccardomerolla/zio-legacy-modernization-agent
sbt "llm4zio/compile"
```
Expected: `[success]`

**Step 3: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/OpenAIModels.scala
git commit -m "feat(llm4zio): add OpenAI tool calling DTOs"
```

---

### Task 2: OpenAI tool calling — provider implementation

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/OpenAIProvider.scala`

**Context:** Replace the stub `executeWithTools` with real implementation. The API call is `POST /chat/completions` with `tools` param. Response contains `choices[0].message.tool_calls` array.

**Step 1: Write the failing test**

Create `llm4zio/src/test/scala/llm4zio/providers/OpenAIToolCallingSpec.scala`:

```scala
package llm4zio.providers

import zio.*
import zio.json.*
import zio.test.*

import llm4zio.core.*
import llm4zio.tools.*

object OpenAIToolCallingSpec extends ZIOSpecDefault:

  private val stubTool = Tool(
    name = "get_time",
    description = "Returns the current time",
    parameters = """{"type":"object","properties":{},"required":[]}""".fromJson[zio.json.ast.Json].toOption.get,
    execute = _ => ZIO.succeed("""{"time":"12:00"}""".fromJson[zio.json.ast.Json].toOption.get),
  )

  // A mock HttpClient that returns a tool_calls response
  private val toolCallHttpClient: HttpClient = new HttpClient:
    override def get(url: String, headers: Map[String, String], timeout: java.time.Duration): IO[Throwable, String] =
      ZIO.succeed("[]")
    override def postJson(url: String, body: String, headers: Map[String, String], timeout: java.time.Duration): IO[Throwable, String] =
      ZIO.succeed(
        ChatCompletionResponseWithTools(
          id = Some("test-id"),
          choices = List(
            ChatChoiceWithTools(
              message = Some(ChatMessageWithTools(
                role = "assistant",
                content = None,
                tool_calls = Some(List(
                  OpenAIToolCall(
                    id = "call_1",
                    `type` = "function",
                    function = OpenAIToolCallFunction(name = "get_time", arguments = "{}"),
                  )
                )),
              )),
              finish_reason = Some("tool_calls"),
            )
          ),
          usage = None,
          model = Some("gpt-4o"),
        ).toJson
      )

  private val config = LlmConfig(
    provider = LlmProvider.OpenAI,
    model = "gpt-4o",
    baseUrl = Some("https://api.openai.com"),
    apiKey = Some("sk-test"),
  )

  override def spec = suite("OpenAI tool calling")(
    test("executeWithTools returns ToolCallResponse with tool calls") {
      val service = OpenAIProvider.make(config, toolCallHttpClient)
      for
        response <- service.executeWithTools("what time is it?", List(stubTool))
      yield assertTrue(
        response.toolCalls.nonEmpty,
        response.toolCalls.head.name == "get_time",
        response.toolCalls.head.id == "call_1",
        response.finishReason == "tool_calls",
      )
    },
  )
```

**Step 2: Run test to verify it fails**

```bash
sbt "llm4zio/testOnly llm4zio.providers.OpenAIToolCallingSpec"
```
Expected: FAIL — `executeWithTools` returns error stub.

**Step 3: Implement executeWithTools in OpenAIProvider**

In `OpenAIProvider.scala`, replace the stub `executeWithTools` (lines 52-56) with:

```scala
override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
  for
    baseUrl <- ZIO.fromOption(config.baseUrl).orElseFail(
                 LlmError.ConfigError("Missing baseUrl for OpenAI provider")
               )
    _       <- ZIO.fromOption(config.apiKey).orElseFail(
                 LlmError.AuthenticationError("Missing API key for OpenAI provider")
               )
    openAiTools = tools.map { t =>
                    OpenAITool(function = OpenAIFunction(
                      name = t.name,
                      description = t.description,
                      parameters = t.parameters,
                    ))
                  }
    request = ChatCompletionRequestWithTools(
                model = config.model,
                messages = List(ChatMessage(role = "user", content = prompt)),
                tools = openAiTools,
                temperature = config.temperature.orElse(Some(0.7)),
                max_tokens = config.maxTokens,
              )
    url     = s"${baseUrl.stripSuffix("/")}/chat/completions"
    body   <- httpClient.postJson(
                url = url,
                body = request.toJson,
                headers = authHeaders,
                timeout = config.timeout,
              )
    parsed <- ZIO
                .fromEither(body.fromJson[ChatCompletionResponseWithTools])
                .mapError(err => LlmError.ParseError(s"Failed to decode OpenAI tool response: $err", body))
  yield
    val choice     = parsed.choices.headOption
    val toolCalls  = choice.flatMap(_.message).flatMap(_.tool_calls).getOrElse(Nil)
    val content    = choice.flatMap(_.message).flatMap(_.content)
    val finish     = choice.flatMap(_.finish_reason).getOrElse("stop")
    ToolCallResponse(
      content = content,
      toolCalls = toolCalls.map(tc => ToolCall(id = tc.id, name = tc.function.name, arguments = tc.function.arguments)),
      finishReason = finish,
    )
```

**Step 4: Run test to verify it passes**

```bash
sbt "llm4zio/testOnly llm4zio.providers.OpenAIToolCallingSpec"
```
Expected: PASS

**Step 5: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/OpenAIProvider.scala \
        llm4zio/src/main/scala/llm4zio/providers/OpenAIModels.scala \
        llm4zio/src/test/scala/llm4zio/providers/OpenAIToolCallingSpec.scala
git commit -m "feat(llm4zio): implement tool calling in OpenAI provider"
```

---

### Task 3: Anthropic tool calling — DTOs + implementation

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/AnthropicModels.scala`
- Modify: `llm4zio/src/main/scala/llm4zio/providers/AnthropicProvider.scala`

**Context:** Anthropic's API uses `tools` + `tool_use` content blocks. The response `content` array may contain blocks of `type: "tool_use"` with `id`, `name`, and `input` (JSON object).

**Step 1: Add Anthropic tool DTOs**

Append to `AnthropicModels.scala`:

```scala
// Tool calling DTOs
case class AnthropicToolInputSchema(
  `type`: String = "object",
  properties: zio.json.ast.Json,
  required: Option[List[String]] = None,
) derives JsonCodec

case class AnthropicTool(
  name: String,
  description: String,
  input_schema: AnthropicToolInputSchema,
) derives JsonCodec

// Extend ContentBlock to support tool_use type
case class AnthropicContentBlockFull(
  `type`: String,
  text: Option[String] = None,
  id: Option[String] = None,       // for tool_use blocks
  name: Option[String] = None,     // for tool_use blocks
  input: Option[zio.json.ast.Json] = None, // for tool_use blocks
) derives JsonCodec

case class AnthropicRequestWithTools(
  model: String,
  max_tokens: Int,
  messages: List[ChatMessage],
  tools: List[AnthropicTool],
  temperature: Option[Double] = None,
  system: Option[String] = None,
) derives JsonCodec

case class AnthropicResponseWithTools(
  id: Option[String] = None,
  content: List[AnthropicContentBlockFull],
  model: Option[String] = None,
  usage: Option[AnthropicUsage] = None,
  stop_reason: Option[String] = None,
) derives JsonCodec
```

**Step 2: Write failing test**

Create `llm4zio/src/test/scala/llm4zio/providers/AnthropicToolCallingSpec.scala`:

```scala
package llm4zio.providers

import zio.*
import zio.json.*
import zio.test.*
import llm4zio.core.*
import llm4zio.tools.*

object AnthropicToolCallingSpec extends ZIOSpecDefault:

  private val stubTool = Tool(
    name = "get_weather",
    description = "Get weather",
    parameters = """{"type":"object","properties":{"location":{"type":"string"}},"required":["location"]}"""
      .fromJson[zio.json.ast.Json].toOption.get,
    execute = _ => ZIO.succeed("""{"temp":22}""".fromJson[zio.json.ast.Json].toOption.get),
  )

  private val toolCallHttpClient: HttpClient = new HttpClient:
    override def get(url: String, headers: Map[String, String], timeout: java.time.Duration): IO[Throwable, String] =
      ZIO.succeed("[]")
    override def postJson(url: String, body: String, headers: Map[String, String], timeout: java.time.Duration): IO[Throwable, String] =
      ZIO.succeed(
        AnthropicResponseWithTools(
          id = Some("msg_1"),
          content = List(
            AnthropicContentBlockFull(
              `type` = "tool_use",
              id = Some("toolu_1"),
              name = Some("get_weather"),
              input = Some("""{"location":"Rome"}""".fromJson[zio.json.ast.Json].toOption.get),
            )
          ),
          stop_reason = Some("tool_use"),
        ).toJson
      )

  private val config = LlmConfig(
    provider = LlmProvider.Anthropic,
    model = "claude-3-5-sonnet-20241022",
    baseUrl = Some("https://api.anthropic.com"),
    apiKey = Some("sk-ant-test"),
  )

  override def spec = suite("Anthropic tool calling")(
    test("executeWithTools returns ToolCallResponse with tool_use blocks") {
      val service = AnthropicProvider.make(config, toolCallHttpClient)
      for
        response <- service.executeWithTools("What's the weather in Rome?", List(stubTool))
      yield assertTrue(
        response.toolCalls.nonEmpty,
        response.toolCalls.head.name == "get_weather",
        response.finishReason == "tool_use",
      )
    },
  )
```

**Step 3: Run test — expect FAIL**

```bash
sbt "llm4zio/testOnly llm4zio.providers.AnthropicToolCallingSpec"
```

**Step 4: Implement in AnthropicProvider**

Replace stub `executeWithTools` (lines 54-58) with:

```scala
override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
  for
    baseUrl <- ZIO.fromOption(config.baseUrl).orElseFail(
                 LlmError.ConfigError("Missing baseUrl for Anthropic provider")
               )
    apiKey  <- ZIO.fromOption(config.apiKey).orElseFail(
                 LlmError.AuthenticationError("Missing API key for Anthropic provider")
               )
    anthropicTools = tools.map { t =>
                       AnthropicTool(
                         name = t.name,
                         description = t.description,
                         input_schema = AnthropicToolInputSchema(properties = t.parameters),
                       )
                     }
    request = AnthropicRequestWithTools(
                model = config.model,
                max_tokens = config.maxTokens.getOrElse(4096),
                messages = List(ChatMessage(role = "user", content = prompt)),
                tools = anthropicTools,
                temperature = config.temperature,
              )
    url     = s"${baseUrl.stripSuffix("/")}/messages"
    body   <- httpClient.postJson(
                url = url,
                body = request.toJson,
                headers = authHeaders(apiKey),
                timeout = config.timeout,
              )
    parsed <- ZIO
                .fromEither(body.fromJson[AnthropicResponseWithTools])
                .mapError(err => LlmError.ParseError(s"Failed to decode Anthropic tool response: $err", body))
  yield
    val toolUseBlocks = parsed.content.filter(_.`type` == "tool_use")
    val textContent   = parsed.content.find(_.`type` == "text").flatMap(_.text)
    ToolCallResponse(
      content = textContent,
      toolCalls = toolUseBlocks.map { block =>
        ToolCall(
          id = block.id.getOrElse(""),
          name = block.name.getOrElse(""),
          arguments = block.input.map(_.toJson).getOrElse("{}"),
        )
      },
      finishReason = parsed.stop_reason.getOrElse("stop"),
    )
```

**Step 5: Run test — expect PASS**

```bash
sbt "llm4zio/testOnly llm4zio.providers.AnthropicToolCallingSpec"
```

**Step 6: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/AnthropicModels.scala \
        llm4zio/src/main/scala/llm4zio/providers/AnthropicProvider.scala \
        llm4zio/src/test/scala/llm4zio/providers/AnthropicToolCallingSpec.scala
git commit -m "feat(llm4zio): implement tool calling in Anthropic provider"
```

---

### Task 4: Gemini tool calling — DTOs + implementation

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/GeminiModels.scala`
- Modify: `llm4zio/src/main/scala/llm4zio/providers/GeminiApiProvider.scala`

**Context:** Gemini uses `tools` with `functionDeclarations` and returns `functionCall` parts in the response candidates.

**Step 1: Add Gemini tool DTOs**

Append to `GeminiModels.scala`:

```scala
// Tool calling DTOs
case class GeminiFunctionDeclaration(
  name: String,
  description: String,
  parameters: zio.json.ast.Json,
) derives JsonCodec

case class GeminiToolDef(
  functionDeclarations: List[GeminiFunctionDeclaration],
) derives JsonCodec

case class GeminiFunctionCall(
  name: String,
  args: zio.json.ast.Json,
) derives JsonCodec

case class GeminiPartFull(
  text: Option[String] = None,
  functionCall: Option[GeminiFunctionCall] = None,
) derives JsonCodec

case class GeminiContentFull(parts: List[GeminiPartFull]) derives JsonCodec

case class GeminiCandidateFull(
  content: GeminiContentFull,
  finishReason: Option[String] = None,
) derives JsonCodec

case class GeminiGenerateContentResponseFull(
  candidates: List[GeminiCandidateFull],
  usageMetadata: Option[GeminiUsageMetadata] = None,
) derives JsonCodec

case class GeminiGenerateContentRequestWithTools(
  contents: List[GeminiContent],
  tools: List[GeminiToolDef],
  generationConfig: Option[GeminiGenerationConfig] = None,
) derives JsonCodec
```

**Step 2: Write failing test**

Create `llm4zio/src/test/scala/llm4zio/providers/GeminiToolCallingSpec.scala`:

```scala
package llm4zio.providers

import zio.*
import zio.json.*
import zio.test.*
import llm4zio.core.*
import llm4zio.tools.*

object GeminiToolCallingSpec extends ZIOSpecDefault:

  private val stubTool = Tool(
    name = "search",
    description = "Web search",
    parameters = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
      .fromJson[zio.json.ast.Json].toOption.get,
    execute = _ => ZIO.succeed("""{"results":[]}""".fromJson[zio.json.ast.Json].toOption.get),
  )

  private val toolCallHttpClient: HttpClient = new HttpClient:
    override def get(url: String, headers: Map[String, String], timeout: java.time.Duration): IO[Throwable, String] =
      ZIO.succeed("[]")
    override def postJson(url: String, body: String, headers: Map[String, String], timeout: java.time.Duration): IO[Throwable, String] =
      ZIO.succeed(
        GeminiGenerateContentResponseFull(
          candidates = List(
            GeminiCandidateFull(
              content = GeminiContentFull(parts = List(
                GeminiPartFull(functionCall = Some(GeminiFunctionCall(
                  name = "search",
                  args = """{"query":"Scala"}""".fromJson[zio.json.ast.Json].toOption.get,
                )))
              )),
              finishReason = Some("STOP"),
            )
          ),
        ).toJson
      )

  private val config = LlmConfig(
    provider = LlmProvider.GeminiApi,
    model = "gemini-2.0-flash",
    baseUrl = Some("https://generativelanguage.googleapis.com"),
    apiKey = Some("test-key"),
  )

  override def spec = suite("Gemini tool calling")(
    test("executeWithTools returns ToolCallResponse with functionCall") {
      val service = GeminiApiProvider.make(config, toolCallHttpClient)
      for
        response <- service.executeWithTools("Search for Scala", List(stubTool))
      yield assertTrue(
        response.toolCalls.nonEmpty,
        response.toolCalls.head.name == "search",
      )
    },
  )
```

**Step 3: Run — expect FAIL**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiToolCallingSpec"
```

**Step 4: Implement in GeminiApiProvider**

Replace stub `executeWithTools` (lines 44-46) with:

```scala
override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
  for
    baseUrl <- ZIO.fromOption(config.baseUrl).orElseFail(
                 LlmError.ConfigError("Missing baseUrl for Gemini API provider")
               )
    apiKey  <- ZIO.fromOption(config.apiKey).orElseFail(
                 LlmError.AuthenticationError("Missing API key for Gemini API provider")
               )
    geminiTools = List(GeminiToolDef(
                    functionDeclarations = tools.map { t =>
                      GeminiFunctionDeclaration(
                        name = t.name,
                        description = t.description,
                        parameters = t.parameters,
                      )
                    }
                  ))
    request = GeminiGenerateContentRequestWithTools(
                contents = List(GeminiContent(parts = List(GeminiPart(text = prompt)))),
                tools = geminiTools,
              )
    url     = s"${baseUrl.stripSuffix("/")}/v1beta/models/${config.model}:generateContent"
    body   <- httpClient.postJson(
                url = url,
                body = request.toJson,
                headers = Map("x-goog-api-key" -> apiKey),
                timeout = config.timeout,
              )
    parsed <- ZIO
                .fromEither(body.fromJson[GeminiGenerateContentResponseFull])
                .mapError(err => LlmError.ParseError(s"Failed to decode Gemini tool response: $err", body))
  yield
    val parts      = parsed.candidates.headOption.toList.flatMap(_.content.parts)
    val fnCalls    = parts.flatMap(_.functionCall)
    val textParts  = parts.flatMap(_.text)
    val finish     = parsed.candidates.headOption.flatMap(_.finishReason).getOrElse("STOP")
    ToolCallResponse(
      content = textParts.headOption,
      toolCalls = fnCalls.zipWithIndex.map { case (fc, i) =>
        ToolCall(
          id = s"gemini_call_$i",
          name = fc.name,
          arguments = fc.args.toJson,
        )
      },
      finishReason = finish,
    )
```

**Step 5: Run — expect PASS**

```bash
sbt "llm4zio/testOnly llm4zio.providers.GeminiToolCallingSpec"
```

**Step 6: Commit**

```bash
git add llm4zio/src/main/scala/llm4zio/providers/GeminiModels.scala \
        llm4zio/src/main/scala/llm4zio/providers/GeminiApiProvider.scala \
        llm4zio/src/test/scala/llm4zio/providers/GeminiToolCallingSpec.scala
git commit -m "feat(llm4zio): implement tool calling in Gemini API provider"
```

---

### Task 5: Wire ToolConversationManager into ChatController

**Files:**
- Modify: `src/main/scala/conversation/boundary/ChatController.scala`
- Modify: `src/test/scala/web/controllers/ChatControllerGatewaySpec.scala`

**Context:** `ChatController.addUserAndAssistantMessage` currently calls `llmService.execute(mention.content)`. We need to check if the conversation has tools enabled (via metadata flag), and if so route through `ToolConversationManager.run()`. The tool results need to be persisted as separate `ConversationEntry` records with `MessageType.ToolCall` and `MessageType.ToolResult`.

First, check if `MessageType` already has tool-related variants:

```bash
grep -r "MessageType" src/main/scala/ --include="*.scala" | head -20
```

**Step 1: Add ToolCall/ToolResult to MessageType if missing**

Look at `src/main/scala/conversation/entity/` for the `MessageType` enum. If it doesn't have `ToolCall` and `ToolResult` variants, add them.

**Step 2: Write failing test in ChatControllerGatewaySpec**

Add to `ChatControllerGatewaySpec.scala`:

```scala
test("POST /chat/{id}/messages with tools enabled stores tool call and result messages") {
  // When toolsEnabled=true in conversation metadata, the tool loop should run
  // For this test we use a TestLlmWithTools that returns a tool call on first invocation
  // and a final answer on the second invocation
  // Since full wiring is complex, this test verifies the message count includes tool messages
  for
    id  <- createConversation("tools-test")
    _   <- postMessage(id, "use the read_file tool", metadata = Some("""{"toolsEnabled":true}"""))
    msgs <- getMessages(id)
  yield assertTrue(msgs.exists(_.messageType == MessageType.ToolCall) || msgs.size >= 2)
}
```

**Step 3: Implement tool routing in ChatController**

In `addUserAndAssistantMessage`, after the existing `llmResponse` computation, add a branch:

```scala
// Check if tool calling is requested (metadata flag or conversation setting)
toolsEnabled = metadata.flatMap(m => m.fromJson[Map[String, String]].toOption)
                 .flatMap(_.get("toolsEnabled")).contains("true")

llmResponse <- if toolsEnabled then
                 ToolConversationManager
                   .run(
                     prompt = mention.content,
                     thread = ConversationThread.empty,
                     llmService = llmService,
                     toolRegistry = toolRegistry,
                     tools = toolRegistry.all,
                     maxIterations = 8,
                   )
                   .map(result => result.response)
                   .mapError(convertLlmError)
               else
                 llmService
                   .execute(mention.content)
                   .mapError(convertLlmError)
```

Note: You need to inject a `ToolRegistry` into `ChatController`. Check how it's currently wired in `ApplicationDI.scala` and add it to the `ChatController` constructor and `ZLayer`.

**Step 4: Persist tool call messages**

When `toolsEnabled` and the result came from `ToolConversationManager`, also persist the tool call/result messages from the `ToolConversationResult.thread` by iterating over `thread.messages` and saving those with role=Tool as `MessageType.ToolCall` entries.

**Step 5: Run tests**

```bash
sbt "testOnly web.controllers.ChatControllerGatewaySpec"
```
Expected: all pass

**Step 6: Commit**

```bash
git add src/main/scala/conversation/boundary/ChatController.scala \
        src/test/scala/web/controllers/ChatControllerGatewaySpec.scala
git commit -m "feat: wire ToolConversationManager into ChatController"
```

---

### Task 6: Tool call panel in chat view (UX)

**Files:**
- Modify: `src/main/scala/shared/web/` — find the chat message rendering component

**Context:** Messages with `MessageType.ToolCall` or `MessageType.ToolResult` need a different visual treatment than plain text messages. They should show as collapsible cards with the tool name, arguments (collapsed by default), and result.

**Step 1: Find chat message rendering**

```bash
grep -r "MessageType\|chat-message\|messageType" src/main/scala/shared/web/ --include="*.scala" -l
```

**Step 2: Add tool call message styling**

In the relevant view file, add a `renderToolCallMessage` method:

```scala
private def toolCallBlock(toolName: String, args: String, result: String): Frag =
  details(cls := "tool-call-block border border-gray-200 rounded-lg p-3 my-2 bg-gray-50")(
    summary(cls := "cursor-pointer font-mono text-sm font-semibold text-indigo-700")(
      span(cls := "mr-2")("⚙"),
      s"Tool: $toolName"
    ),
    div(cls := "mt-2 space-y-2")(
      div(cls := "text-xs font-semibold text-gray-500 uppercase")("Arguments"),
      pre(cls := "text-xs bg-white border border-gray-200 rounded p-2 overflow-auto")(args),
      div(cls := "text-xs font-semibold text-gray-500 uppercase mt-2")("Result"),
      pre(cls := "text-xs bg-white border border-gray-200 rounded p-2 overflow-auto")(result),
    )
  )
```

**Step 3: Integrate into message rendering**

Wire `toolCallBlock` into the message list renderer so `MessageType.ToolCall` entries render using this component instead of the default text bubble.

**Step 4: Commit**

```bash
git add src/main/scala/shared/web/
git commit -m "feat(ux): tool call panel in chat message view"
```

---

### Task 7: Tool registry browser in Settings (UX)

**Files:**
- Modify: `src/main/scala/shared/web/SettingsView.scala` (add a tools tab section or panel to the AI tab)
- Modify: `src/main/scala/config/boundary/SettingsController.scala` (add API endpoint)

**Context:** Add a `Tools` section to `/settings/ai` that lists the registered built-in tools. Each tool shows its name, description, sandbox level, and tags. This is read-only for now.

**Step 1: Add API endpoint**

In `SettingsController`, add:

```scala
Method.GET / "api" / "tools" -> handler {
  ZIO.succeed(Response.json(toolRegistry.all.map(t =>
    Map(
      "name"        -> t.name,
      "description" -> t.description,
      "sandbox"     -> t.sandbox.toString,
      "tags"        -> t.tags.mkString(","),
    )
  ).toJson))
},
```

**Step 2: Add tools panel to settingsAiTab**

In `SettingsView.aiTab`, after the models table, add:

```scala
div(id := "tools-panel", cls := "mt-8")(
  h3(cls := "text-lg font-semibold text-gray-900 mb-4")("Available Tools"),
  div(
    attr("hx-get") := "/api/tools",
    attr("hx-trigger") := "load",
    attr("hx-target") := "#tools-list",
  )(""),
  div(id := "tools-list")(
    // HTMX will populate this
  )
)
```

**Step 3: Add HTMX fragment endpoint**

```scala
Method.GET / "settings" / "ai" / "tools-fragment" -> handler {
  toolRegistry.allIO.map { tools =>
    htmlFragment(SettingsView.toolsFragment(tools).render)
  }
},
```

**Step 4: Commit**

```bash
git add src/main/scala/shared/web/SettingsView.scala \
        src/main/scala/config/boundary/SettingsController.scala
git commit -m "feat(ux): tool registry browser in settings AI tab"
```

---

## Milestone 2: Streaming Quality + Structured Output

### Task 8: True SSE streaming for OpenAI provider

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/OpenAIProvider.scala`
- Modify: `llm4zio/src/main/scala/llm4zio/providers/OpenAIModels.scala` (add SSE chunk DTOs)

**Context:** Replace `ZStream.fromZIO(execute(prompt))` wrapper with real SSE parsing. OpenAI sends `data: {"choices":[{"delta":{"content":"..."}}]}\n\n` per chunk. The `HttpClient` needs a streaming variant — check if `HttpClient` trait has a `stream` or `getStream` method. If not, add one.

**Step 1: Check HttpClient for streaming support**

Read `llm4zio/src/main/scala/llm4zio/providers/HttpClient.scala` to see if a streaming method exists.

**Step 2: Add streaming to HttpClient if missing**

If `HttpClient` has no `postJsonStream` method, add to the trait:

```scala
def postJsonStream(
  url: String,
  body: String,
  headers: Map[String, String],
  timeout: java.time.Duration,
): ZStream[Any, Throwable, String]
```

And implement in `ZioHttpClient` using ZIO HTTP's `Client.streaming`.

**Step 3: Add SSE chunk DTOs**

Add to `OpenAIModels.scala`:

```scala
case class OpenAIDelta(content: Option[String] = None) derives JsonCodec
case class OpenAIStreamChoice(delta: OpenAIDelta, finish_reason: Option[String] = None) derives JsonCodec
case class OpenAIStreamChunk(choices: List[OpenAIStreamChoice]) derives JsonCodec
```

**Step 4: Write failing test**

Create `llm4zio/src/test/scala/llm4zio/providers/OpenAIStreamingSpec.scala`:

```scala
test("executeStream emits real delta chunks (not one collected chunk)") {
  // Mock HttpClient returns multiple SSE lines
  val chunks = List("Hello", " world", "!")
  val sseBody = chunks.map(c =>
    s"""data: {"choices":[{"delta":{"content":"$c"},"finish_reason":null}]}\n\n"""
  ).mkString + "data: [DONE]\n\n"
  // ... mock client returning sseBody as stream
  // assert stream emits 3 chunks
  for
    chunks <- service.executeStream("hi").runCollect
  yield assertTrue(chunks.size == 3)
}
```

**Step 5: Implement real streaming in OpenAIProvider.executeStream**

```scala
override def executeStream(prompt: String): ZStream[Any, LlmError, LlmChunk] =
  ZStream.unwrap {
    for
      baseUrl <- ZIO.fromOption(config.baseUrl).orElseFail(LlmError.ConfigError("Missing baseUrl"))
      _       <- ZIO.fromOption(config.apiKey).orElseFail(LlmError.AuthenticationError("Missing API key"))
      request  = ChatCompletionRequest(
                   model = config.model,
                   messages = List(ChatMessage(role = "user", content = prompt)),
                   stream = Some(true),
                   temperature = config.temperature.orElse(Some(0.7)),
                   max_tokens = config.maxTokens,
                 )
      url      = s"${baseUrl.stripSuffix("/")}/chat/completions"
    yield httpClient
            .postJsonStream(url, request.toJson, authHeaders, config.timeout)
            .mapError(err => LlmError.ProviderError("openai", err.getMessage, None))
            .filter(line => line.startsWith("data: ") && line != "data: [DONE]")
            .map(_.stripPrefix("data: "))
            .mapZIO { json =>
              ZIO.fromEither(json.fromJson[OpenAIStreamChunk])
                .mapError(err => LlmError.ParseError(s"Stream parse error: $err", json))
            }
            .flatMap { chunk =>
              val delta  = chunk.choices.headOption.flatMap(_.delta.content).getOrElse("")
              val finish = chunk.choices.headOption.flatMap(_.finish_reason)
              ZStream.succeed(LlmChunk(delta = delta, finishReason = finish))
            }
  }
```

**Step 6: Commit**

```bash
git commit -m "feat(llm4zio): true SSE streaming for OpenAI provider"
```

---

### Task 9: True SSE streaming for Anthropic provider

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/AnthropicProvider.scala`
- Modify: `llm4zio/src/main/scala/llm4zio/providers/AnthropicModels.scala`

**Context:** Anthropic sends `event: content_block_delta\ndata: {"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}\n\n`.

Add DTOs:
```scala
case class AnthropicStreamDelta(`type`: String, text: Option[String] = None) derives JsonCodec
case class AnthropicStreamEvent(`type`: String, delta: Option[AnthropicStreamDelta] = None) derives JsonCodec
```

Parse `data:` lines, filter for `content_block_delta` events, extract `delta.text`. Pattern mirrors Task 8.

**Step: Commit**

```bash
git commit -m "feat(llm4zio): true SSE streaming for Anthropic provider"
```

---

### Task 10: True SSE streaming for Gemini provider

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/providers/GeminiApiProvider.scala`

**Context:** Gemini streaming uses `:streamGenerateContent` endpoint. Response is a JSON array streamed as newline-delimited JSON objects (NDJSON). Each object has the same `GeminiGenerateContentResponse` shape. Accumulate `candidates[0].content.parts[0].text` deltas.

**Step: Commit**

```bash
git commit -m "feat(llm4zio): true SSE streaming for Gemini provider"
```

---

### Task 11: Structured output mode in ChatController + UX toggle

**Files:**
- Modify: `src/main/scala/conversation/boundary/ChatController.scala`
- Modify: `src/main/scala/shared/web/` (chat composer)

**Context:** When a user sends a message with metadata `{"structuredOutput":true,"schema":"..."}`, route through `llmService.executeStructured[zio.json.ast.Json](prompt, schema)` and store the result as `MessageType.Json` (add if missing). In the UI, show a toggle button in the composer and a "JSON Schema" expandable text area.

**Step 1: Add mode to ChatController**

```scala
structuredMode = metadata.flatMap(_.fromJson[Map[String,String]].toOption)
                   .flatMap(_.get("structuredOutput")).contains("true")
schemaJson     = metadata.flatMap(_.fromJson[Map[String,String]].toOption)
                   .flatMap(_.get("schema"))
                   .flatMap(_.fromJson[zio.json.ast.Json].toOption)

llmResponse <- if structuredMode && schemaJson.isDefined then
                 llmService
                   .executeStructured[zio.json.ast.Json](mention.content, schemaJson.get)
                   .map(json => LlmResponse(content = json.toJson, metadata = Map("structured" -> "true")))
                   .mapError(convertLlmError)
               else
                 // ... existing branches
```

**Step 2: Add toggle to chat composer**

In the chat view, add a button that sets a hidden field `structuredOutput=true` and reveals a schema textarea.

**Step 3: Commit**

```bash
git commit -m "feat: structured output mode in chat composer and controller"
```

---

## Milestone 3: Observability + Provider Fallback

### Task 12: Instrument LLM calls with LlmMetrics

**Files:**
- Modify: `llm4zio/src/main/scala/llm4zio/core/LlmService.scala` (factory layer)
- Check: `llm4zio/src/main/scala/llm4zio/observability/LlmMetrics.scala`

**Context:** Wrap the `LlmService.fromConfig` factory in a metrics layer. Before each call record `recordRequest`, after success record `recordSuccess(tokens, latencyMs)`, after failure record `recordError`.

**Step 1: Read LlmMetrics to understand the API**

```bash
cat llm4zio/src/main/scala/llm4zio/observability/LlmMetrics.scala
```

**Step 2: Create MeteredLlmService wrapper**

Create `llm4zio/src/main/scala/llm4zio/observability/MeteredLlmService.scala`:

```scala
package llm4zio.observability

import zio.*
import zio.json.*
import zio.stream.*
import llm4zio.core.*
import llm4zio.tools.*

final class MeteredLlmService(delegate: LlmService, metrics: LlmMetrics) extends LlmService:
  override def execute(prompt: String): IO[LlmError, LlmResponse] =
    timed(delegate.execute(prompt))

  override def executeStream(prompt: String): Stream[LlmError, LlmChunk] =
    delegate.executeStream(prompt) // streaming metrics tracked separately via chunk count

  override def executeWithHistory(messages: List[Message]): IO[LlmError, LlmResponse] =
    timed(delegate.executeWithHistory(messages))

  override def executeStreamWithHistory(messages: List[Message]): Stream[LlmError, LlmChunk] =
    delegate.executeStreamWithHistory(messages)

  override def executeWithTools(prompt: String, tools: List[AnyTool]): IO[LlmError, ToolCallResponse] =
    Clock.instant.flatMap { start =>
      delegate.executeWithTools(prompt, tools)
        .tapBoth(
          err => Clock.instant.flatMap(end => metrics.recordError(end.toEpochMilli - start.toEpochMilli)),
          res => Clock.instant.flatMap(end => metrics.recordSuccess(None, end.toEpochMilli - start.toEpochMilli)),
        )
    }

  override def executeStructured[A: JsonCodec](prompt: String, schema: JsonSchema): IO[LlmError, A] =
    timed(delegate.executeStructured(prompt, schema))

  override def isAvailable: UIO[Boolean] = delegate.isAvailable

  private def timed[E <: LlmError, A](effect: IO[E, A])(implicit ev: A =:= LlmResponse = null): IO[E, A] =
    Clock.instant.flatMap { start =>
      effect.tapBoth(
        err => Clock.instant.flatMap(end => metrics.recordError(end.toEpochMilli - start.toEpochMilli)).orDie,
        res =>
          Clock.instant.flatMap { end =>
            val usage = res.asInstanceOf[LlmResponse].usage
            metrics.recordSuccess(usage, end.toEpochMilli - start.toEpochMilli).orDie
          },
      )
    }
```

Note: Adjust `timed` to handle the generic response type — you may need separate typed helpers for each overload.

**Step 3: Wire into ApplicationDI**

In `src/main/scala/app/ApplicationDI.scala`, wrap the `LlmService` layer with `MeteredLlmService`.

**Step 4: Commit**

```bash
git commit -m "feat(llm4zio): instrument LLM calls with LlmMetrics"
```

---

### Task 13: Provider fallback chain

**Files:**
- Modify: `src/main/scala/app/ConfigAwareLlmService.scala` (or wherever it's defined — search for `ConfigAwareLlmService`)

**Context:** When the primary provider returns `AuthenticationError` or `RateLimitError`, retry with the next provider in a configured fallback list.

**Step 1: Find ConfigAwareLlmService**

```bash
grep -r "ConfigAwareLlmService" src/ --include="*.scala" -l
```

**Step 2: Add fallback logic**

```scala
private def withFallback(effect: IO[LlmError, LlmResponse], providers: List[LlmService]): IO[LlmError, LlmResponse] =
  effect.catchSome {
    case _: LlmError.AuthenticationError | _: LlmError.RateLimitError =>
      providers match
        case next :: rest => withFallback(next.execute(""), rest) // rebuild with actual prompt
        case Nil          => effect
  }
```

**Step 3: Commit**

```bash
git commit -m "feat(llm4zio): provider fallback chain on auth/rate-limit errors"
```

---

### Task 14-16: Observability UX (token panel, metrics widget, fallback config UI)

**Files:**
- Modify: `src/main/scala/shared/web/DashboardView.scala` (or equivalent)
- Modify: `src/main/scala/shared/web/SettingsView.scala`
- Add HTMX fragment endpoints in `SettingsController` / `HealthController`

**Task 14 — Token usage panel per conversation:**

Add an HTMX fragment endpoint `GET /api/conversations/{id}/tokens` that returns prompt/completion/cost breakdown. In the chat view, add a collapsible token info section below each assistant message showing `tokens: N (prompt: M / completion: K)`.

**Task 15 — LLM metrics widget in /settings/system:**

Add a `GET /api/llm/metrics` endpoint returning `LlmMetrics.Snapshot`. In `HealthController`/`SettingsView.systemTab`, add an "LLM Performance" card showing: total requests, avg latency (ms), error rate (%), top provider.

**Task 16 — Provider fallback config in /settings/ai:**

Add a drag-to-reorder fallback list in the AI settings tab. Persist the ordered list as `ai.fallback.providers` setting. Show current status (green/red) for each provider based on `ModelService.probeProviders`.

**Commit after each task:**
```bash
git commit -m "feat(ux): [task description]"
```

---

## Milestone 4: Agent System + Conversation Memory

### Task 17: Wire AgentDispatcher to ChatController

**Files:**
- Modify: `src/main/scala/conversation/boundary/ChatController.scala`
- Read: `src/main/scala/gateway/control/AgentDispatcher.scala` (or equivalent)

**Context:** When `@agent-name` mention is detected, currently only metadata is tagged. We need to route to the actual `Agent.execute()` via `AgentDispatcher`. The `AgentDispatcher` is in the gateway layer.

**Step 1: Find AgentDispatcher**

```bash
grep -r "AgentDispatcher\|AgentCoordinator" src/ --include="*.scala" -l
```

**Step 2: Inject and call agent**

In `addUserAndAssistantMessage`, when `mention.agentName.isDefined`, call:

```scala
agentResult <- agentDispatcher.dispatch(
                 agentName = mention.agentName.get,
                 input = mention.content,
                 context = AgentContext.fromConversation(conversationId, history),
               )
```

Convert `AgentResult` to `LlmResponse`-shaped output.

**Step 3: Commit**

```bash
git commit -m "feat: wire AgentDispatcher to ChatController for @mentions"
```

---

### Task 18: Context window management

**Files:**
- Modify: `src/main/scala/conversation/boundary/ChatController.scala`
- Read: `llm4zio/src/main/scala/llm4zio/core/ContextManagement.scala`

**Context:** Before calling `llmService.executeWithHistory(messages)`, apply `ContextWindowManager.trim(messages, strategy, maxTokens)` to avoid exceeding context limits.

**Step 1: Add trim call before executeWithHistory**

```scala
trimmedMessages <- ContextWindowManager.trim(
                     messages = conversationHistory,
                     strategy = ContextTrimmingStrategy.SlidingWindow,
                     maxTokens = config.maxTokens.getOrElse(4096),
                   )
llmResponse <- llmService.executeWithHistory(trimmedMessages)
```

**Step 2: Commit**

```bash
git commit -m "feat: apply context window trimming before LLM calls"
```

---

### Task 19: Persist ConversationThread checkpoints

**Files:**
- Modify: `src/main/scala/conversation/boundary/ChatController.scala`
- Modify: EclipseStore persistence layer for conversations

**Context:** After each successful LLM response, save a `ConversationCheckpoint` to EclipseStore. On conversation resume (`GET /chat/{id}`), load and restore the latest checkpoint.

---

### Task 20-22: Agent/Context UX

**Task 20 — Agent browser:** Add `/settings/agents` page listing all registered agents with their capabilities, version, description. Each agent card has a "Start conversation" button linking to `/chat/new?agent=name`.

**Task 21 — Context window indicator:** Add a token usage progress bar in the chat header (HTMX-polled from `GET /api/conversations/{id}/context-window`). Add a dropdown to select trimming strategy (stored in conversation settings).

**Task 22 — Checkpoint/branching UI:** Add a "Save checkpoint" button in chat. List past checkpoints in a side panel. "Restore" creates a new conversation branch from that checkpoint.

---

## Milestone 5: RAG + Guardrails

### Task 23: Wire EmbeddingService + VectorStore

**Files:**
- Create: `src/main/scala/rag/control/RagService.scala`
- Modify: `src/main/scala/conversation/boundary/ChatController.scala`

**Context:** `RagService.retrieve(query, topK)` calls `embeddingService.embed(query)` then `vectorStore.search(embedding, topK)`. The retrieved chunks are prepended to the system message before the LLM call.

---

### Task 24: Guardrails framework

**Files:**
- Create: `llm4zio/src/main/scala/llm4zio/guardrails/Guardrail.scala`

```scala
trait Guardrail:
  def validate(input: String): IO[GuardrailViolation, String]

case class GuardrailViolation(rule: String, message: String)

object Guardrail:
  def maxLength(n: Int): Guardrail = input =>
    if input.length > n then ZIO.fail(GuardrailViolation("max_length", s"Input exceeds $n chars"))
    else ZIO.succeed(input)
```

---

### Task 25-27: RAG/Guardrails UX

**Task 25 — Knowledge base management:** `/settings/knowledge` page — upload documents (PDF, TXT, MD), view indexed chunk count, search index, delete.

**Task 26 — RAG per conversation toggle:** In conversation settings panel, enable RAG, select knowledge base, adjust top-K slider.

**Task 27 — Guardrails config in /settings/ai:** Toggles for max_length, profanity filter, JSON validation. Applied per provider or globally.

---

## Running All Tests

After each milestone:

```bash
sbt clean compile test
```

Check with formatter:

```bash
sbt scalafmtCheck
```

---

## GitHub Issues

After this plan is implemented task-by-task, create 27 GitHub issues (one per numbered task above). See the design document for issue titles and milestone assignments:

`docs/plans/2026-02-24-llm4zio-gateway-feature-gaps-design.md`
