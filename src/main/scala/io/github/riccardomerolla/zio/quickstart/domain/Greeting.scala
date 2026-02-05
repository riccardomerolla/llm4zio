package io.github.riccardomerolla.zio.quickstart.domain

import io.github.riccardomerolla.zio.quickstart.error.QuickstartError

final case class Audience private (value: String):
  def asString: String = value

object Audience:
  def fromString(value: String): Either[QuickstartError.InvalidAudience, Audience] =
    val normalized = Option(value).fold("")(_.trim)
    Either
      .cond(
        normalized.nonEmpty,
        Audience(normalized),
        QuickstartError.InvalidAudience(
          input = value,
          reason = "Audience must be non-empty and may not contain only whitespace"
        )
      )

final case class GreetingTemplate private (value: String):
  def render(audience: Audience): Greeting =
    val message = value.replace(GreetingTemplate.Placeholder, audience.asString)
    Greeting(message)

object GreetingTemplate:
  private val Placeholder = "{name}"

  def make(value: String): Either[QuickstartError.InvalidTemplate, GreetingTemplate] =
    val normalized = Option(value).fold("")(_.trim)
    if normalized.isEmpty then
      Left(QuickstartError.InvalidTemplate(value, "Greeting template cannot be empty"))
    else if !normalized.contains(Placeholder) then
      Left(QuickstartError.InvalidTemplate(value, s"Greeting template must contain the placeholder $Placeholder"))
    else
      Right(GreetingTemplate(normalized))

final case class Greeting(message: String):
  def rendered: String = message

object Greeting:
  def fromTemplate(template: GreetingTemplate, audience: Audience): Greeting =
    template.render(audience)
