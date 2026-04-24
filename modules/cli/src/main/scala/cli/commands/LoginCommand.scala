package cli.commands

import zio.*

import _root_.config.entity.ConfigRepository
import shared.errors.PersistenceError

object LoginCommand:

  /** Stores the provider + API key so ConnectorConfigResolver can pick them up.
    *
    *   - Global (default): `connector.default.provider`, `connector.default.apiKey`
    *   - Agent-scoped: `agent.<name>.connector.provider`, `agent.<name>.connector.apiKey`
    */
  def login(
    provider: String,
    apiKey: String,
    agentName: Option[String],
  ): ZIO[ConfigRepository, PersistenceError | String, String] =
    for
      _     <- validate(provider, apiKey)
      prefix = agentName match
                 case Some(name) => s"agent.$name.connector"
                 case None       => "connector.default"
      _     <- ConfigRepository.upsertSetting(s"$prefix.provider", provider.toLowerCase.trim)
      _     <- ConfigRepository.upsertSetting(s"$prefix.apiKey", apiKey.trim)
    yield
      val scope  = agentName.fold("(global default)")(n => s"(agent=$n)")
      val masked = maskKey(apiKey.trim)
      s"✓ Stored $provider credentials $scope\n  key stored as: $masked\n  reads: $prefix.provider, $prefix.apiKey"

  private def validate(provider: String, apiKey: String): IO[String, Unit] =
    val trimmedProvider = provider.trim
    val trimmedKey      = apiKey.trim
    if trimmedProvider.isEmpty then ZIO.fail("provider must not be empty")
    else if trimmedKey.isEmpty then ZIO.fail("api key must not be empty")
    else ZIO.unit

  def maskKey(key: String): String =
    if key.length <= 8 then "*" * key.length
    else s"${key.take(4)}${"*" * (key.length - 8)}${key.takeRight(4)}"
