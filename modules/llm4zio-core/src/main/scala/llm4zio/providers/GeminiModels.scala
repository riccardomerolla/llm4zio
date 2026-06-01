package llm4zio.providers

import zio.json.*
import zio.json.ast.Json

// Gemini API request/response models
case class GeminiGenerationConfig(
  responseMimeType: Option[String] = None,
  responseSchema: Option[Json] = None,
) derives JsonCodec

case class GeminiGenerateContentRequest(
  contents: List[GeminiContent],
  generationConfig: Option[GeminiGenerationConfig] = None,
) derives JsonCodec

case class GeminiGenerateContentResponse(
  candidates: List[GeminiCandidate] = Nil,
  usageMetadata: Option[GeminiUsageMetadata] = None,
) derives JsonCodec

case class GeminiCandidate(
  content: GeminiContent,
  finishReason: Option[String] = None,
) derives JsonCodec

case class GeminiContent(parts: List[GeminiPart]) derives JsonCodec

case class GeminiPart(text: Option[String] = None) derives JsonCodec

case class GeminiUsageMetadata(
  promptTokenCount: Option[Int] = None,
  candidatesTokenCount: Option[Int] = None,
  totalTokenCount: Option[Int] = None,
) derives JsonCodec

// Tool calling DTOs
case class GeminiFunctionDeclaration(
  name: String,
  description: String,
  parameters: zio.json.ast.Json,
) derives JsonCodec

case class GeminiToolDef(
  functionDeclarations: List[GeminiFunctionDeclaration]
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
  candidates: List[GeminiCandidateFull] = Nil,
  usageMetadata: Option[GeminiUsageMetadata] = None,
) derives JsonCodec

case class GeminiGenerateContentRequestWithTools(
  contents: List[GeminiContent],
  tools: List[GeminiToolDef],
  generationConfig: Option[GeminiGenerationConfig] = None,
) derives JsonCodec
