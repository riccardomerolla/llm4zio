package config.entity

import zio.json.*

import llm4zio.core.LlmProvider

enum ProviderAvailability derives JsonCodec:
  case Healthy, Degraded, Unhealthy, Unknown

enum AuthStatus derives JsonCodec:
  case Valid, Missing, Invalid, Unknown

final case class ProviderProbeStatus(
  provider: LlmProvider,
  availability: ProviderAvailability,
  auth: AuthStatus,
  statusMessage: String,
  checkedAt: java.time.Instant,
  rateLimitHeadroom: Option[Double] = None,
) derives JsonCodec

final case class ProviderModelGroup(
  provider: LlmProvider,
  models: List[AIModel],
) derives JsonCodec

final case class ModelRegistryResponse(
  providers: List[ProviderModelGroup]
) derives JsonCodec

enum ModelServiceError derives JsonCodec:
  case ProbeFailed(provider: LlmProvider, message: String)
