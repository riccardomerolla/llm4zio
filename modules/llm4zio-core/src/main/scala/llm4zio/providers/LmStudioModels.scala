package llm4zio.providers

import zio.json.*
import zio.json.ast.Json

/** Native LM Studio API v1 models
  *
  * These models are for the LM Studio native API at /api/v1/chat (not the OpenAI-compatible endpoint at
  * /v1/chat/completions)
  */

case class LmStudioChatRequest(
  model: String,
  input: LmStudioInputPayload,
  system_prompt: Option[String] = None,
  integrations: Option[List[LmStudioIntegration]] = None,
  stream: Option[Boolean] = None,
  temperature: Option[Double] = None,
  top_p: Option[Double] = None,
  top_k: Option[Int] = None,
  min_p: Option[Double] = None,
  repeat_penalty: Option[Double] = None,
  max_output_tokens: Option[Int] = None,
  reasoning: Option[String] = None,
  context_length: Option[Int] = None,
  store: Option[Boolean] = None,
  previous_response_id: Option[String] = None,
) derives JsonCodec

case class LmStudioMessage(
  role: String,
  content: String,
  name: Option[String] = None,
) derives JsonCodec

case class LmStudioInput(
  `type`: String,
  content: Option[String] = None,
  data_url: Option[String] = None,
) derives JsonCodec

enum LmStudioInputPayload:
  case Text(value: String)
  case Items(value: List[LmStudioInput])

object LmStudioInputPayload:
  given JsonCodec[LmStudioInputPayload] =
    val encoder = JsonEncoder[Json].contramap[LmStudioInputPayload] {
      case LmStudioInputPayload.Text(value)  => Json.Str(value)
      case LmStudioInputPayload.Items(value) =>
        summon[JsonEncoder[List[LmStudioInput]]].toJsonAST(value) match
          case Right(json) => json
          case Left(_)     => Json.Arr(zio.Chunk.empty)
    }
    val decoder = JsonDecoder[Json].mapOrFail {
      case Json.Str(value) => Right(LmStudioInputPayload.Text(value))
      case Json.Arr(value) =>
        summon[JsonDecoder[List[LmStudioInput]]]
          .fromJsonAST(Json.Arr(value))
          .map(LmStudioInputPayload.Items(_))
      case other           => Left(s"Invalid input payload: $other")
    }
    JsonCodec(encoder, decoder)

sealed trait LmStudioIntegration derives JsonCodec

case class LmStudioPluginIntegration(
  `type`: String,
  id: String,
  allowed_tools: Option[List[String]] = None,
) extends LmStudioIntegration derives JsonCodec

case class LmStudioEphemeralMcpIntegration(
  `type`: String,
  server_label: String,
  server_url: String,
  allowed_tools: Option[List[String]] = None,
  headers: Option[Map[String, String]] = None,
) extends LmStudioIntegration derives JsonCodec

case class LmStudioChatResponse(
  model_instance_id: String,
  output: List[LmStudioOutputItem],
  stats: Option[LmStudioStats] = None,
  response_id: Option[String] = None,
) derives JsonCodec

case class LmStudioOutputItem(
  `type`: String,
  content: Option[String] = None,
  tool: Option[String] = None,
  arguments: Option[Json] = None,
  output: Option[String] = None,
  provider_info: Option[Json] = None,
  reason: Option[String] = None,
  metadata: Option[Json] = None,
) derives JsonCodec

case class LmStudioStats(
  input_tokens: Option[Int] = None,
  total_output_tokens: Option[Int] = None,
  reasoning_output_tokens: Option[Int] = None,
  tokens_per_second: Option[Double] = None,
  time_to_first_token_seconds: Option[Double] = None,
  model_load_time_seconds: Option[Double] = None,
) derives JsonCodec

// Model management
case class LmStudioModel(
  id: String,
  path: String,
  `type`: String,
  loaded: Boolean,
  architecture: Option[String] = None,
) derives JsonCodec

case class LmStudioModelsResponse(
  models: List[LmStudioModel]
) derives JsonCodec

case class LmStudioLoadModelRequest(
  model: String,
  context_length: Option[Int] = None,
  gpu_layers: Option[Int] = None,
) derives JsonCodec

case class LmStudioLoadModelResponse(
  success: Boolean,
  model: String,
  message: Option[String] = None,
) derives JsonCodec
