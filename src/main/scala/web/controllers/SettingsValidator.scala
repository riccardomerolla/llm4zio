package web.controllers

import models.{ AIProvider, TelegramMode }

/** Validation for settings values before persistence
  *
  * Validates user-submitted settings and returns either validated values or error messages.
  */
object SettingsValidator:

  /** Allowed setting key prefixes — anything else is rejected */
  val allowedPrefixes: Set[String] = Set("ai.", "gateway.", "telegram.")

  /** Predicate to check if a key should be saved */
  def isAllowedKey(key: String): Boolean =
    allowedPrefixes.exists(prefix => key.startsWith(prefix))

  /** Validate a single setting key-value pair
    *
    * @param key
    *   Setting key
    * @param value
    *   Setting value
    * @return
    *   Right(normalizedValue) on success, Left(errorMessage) on failure
    */
  def validate(key: String, value: String): Either[String, String] =
    if !isAllowedKey(key) then Left(s"Unknown setting key: $key")
    else
      key match
        // Checkboxes — normalize "on"/"off" to "true"/"false"
        case "gateway.dryRun" | "gateway.verbose" | "telegram.enabled" =>
          normalizeCheckbox(value).map(v => if v then "true" else "false")

        // AI Provider — must be a valid provider enum
        case "ai.provider" =>
          if value.isEmpty then Right("")
          else validateAIProvider(value)

        // Numeric fields — must be positive integers
        case k if k.endsWith(".timeout") | k.endsWith(".interval") | k.endsWith(".batchSize") | k.endsWith(".maxRetries") | k
            .endsWith(".parallelism") | k.endsWith(".acquireTimeout") | k.endsWith(".requestTimeout") =>
          validatePositiveInt(value, fieldName = key)

        // Temperature — must be empty or decimal 0.0 to 2.0
        case "ai.temperature" =>
          if value.isEmpty then Right("")
          else validateTemperature(value)

        // Max tokens — must be empty or positive integer
        case "ai.maxTokens" =>
          if value.isEmpty then Right("")
          else validatePositiveInt(value, fieldName = key)

        // Telegram mode — must be Webhook or Polling
        case "telegram.mode" =>
          if value == "Webhook" || value == "Polling" then Right(value)
          else Left(s"Invalid telegram mode: $value (must be 'Webhook' or 'Polling')")

        // Bot token — if non-empty, must not be whitespace-only
        case "telegram.botToken" =>
          if value.isEmpty then Right("")
          else if value.trim.isEmpty then Left("Bot token cannot be whitespace-only")
          else Right(value)

        // Other string fields — accept as-is (model, baseUrl, apiKey, webhookUrl, etc.)
        case _ =>
          if value.isEmpty then Right("")
          else Right(value)

  /** Normalize checkbox submission (HTML form send "on" for checked) */
  private def normalizeCheckbox(value: String): Either[String, Boolean] =
    value match
      case "on"    => Right(true)
      case "true"  => Right(true)
      case "off"   => Right(false)
      case "false" => Right(false)
      case ""      => Right(false)
      case v       => Left(s"Invalid checkbox value: $v")

  /** Validate AI provider string is a known provider */
  private def validateAIProvider(value: String): Either[String, String] =
    val providers = Seq("GeminiCli", "GeminiApi", "OpenAi", "Anthropic", "LmStudio", "Ollama", "OpenCode")
    if providers.contains(value) then Right(value)
    else Left(s"Invalid AI provider: $value (must be one of: ${providers.mkString(", ")})")

  /** Validate field is a positive integer */
  private def validatePositiveInt(value: String, fieldName: String): Either[String, String] =
    if value.isEmpty then Right("0")
    else
      value.toIntOption match
        case Some(n) if n > 0 => Right(value)
        case Some(_)          => Left(s"$fieldName must be greater than 0")
        case None             => Left(s"$fieldName must be a valid integer")

  /** Validate AI temperature is between 0.0 and 2.0 */
  private def validateTemperature(value: String): Either[String, String] =
    value.toDoubleOption match
      case Some(temp) if temp >= 0.0 && temp <= 2.0 => Right(value)
      case Some(_)                                   => Left("Temperature must be between 0.0 and 2.0")
      case None                                      => Left("Temperature must be a valid decimal number")

  /** Validate multiple settings and collect errors
    *
    * @param settings
    *   Map of submitted key-value pairs
    * @return
    *   (validSettings, errorMap) where errorMap contains error messages keyed by setting key
    */
  def validateAll(
    settings: Map[String, String]
  ): (Map[String, String], Map[String, String]) =
    val results = settings.map { case (key, value) =>
      key -> validate(key, value)
    }

    val valid = results.collect { case (k, Right(v)) => k -> v }
    val errors = results.collect { case (k, Left(e)) => k -> e }

    (valid, errors)
