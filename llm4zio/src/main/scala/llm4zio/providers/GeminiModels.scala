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
  candidates: List[GeminiCandidate],
  usageMetadata: Option[GeminiUsageMetadata] = None,
) derives JsonCodec

case class GeminiCandidate(content: GeminiContent) derives JsonCodec

case class GeminiContent(parts: List[GeminiPart]) derives JsonCodec

case class GeminiPart(text: String) derives JsonCodec

case class GeminiUsageMetadata(
  promptTokenCount: Option[Int] = None,
  candidatesTokenCount: Option[Int] = None,
  totalTokenCount: Option[Int] = None,
) derives JsonCodec
