package prompts

import java.nio.charset.StandardCharsets

import scala.util.matching.Regex

import zio.*

import db.ConfigRepository

enum PromptError:
  case MissingPrompt(name: String)
  case ReadFailed(name: String, details: String)
  case MissingPlaceholders(name: String, placeholders: List[String])
  case UnresolvedPlaceholders(name: String, placeholders: List[String])

  def message: String =
    this match
      case MissingPrompt(name)                        => s"Prompt resource not found: prompts/$name.md"
      case ReadFailed(name, details)                  => s"Failed to read prompt '$name': $details"
      case MissingPlaceholders(name, placeholders)    =>
        s"Prompt '$name' is missing values for placeholders: ${placeholders.mkString(", ")}"
      case UnresolvedPlaceholders(name, placeholders) =>
        s"Prompt '$name' still has unresolved placeholders: ${placeholders.mkString(", ")}"

trait PromptLoader:
  def load(name: String, context: Map[String, String] = Map.empty): IO[PromptError, String]

object PromptLoader:
  val ReloadingSettingKey: String = "prompts.reloading"

  def load(name: String, context: Map[String, String] = Map.empty): ZIO[PromptLoader, PromptError, String] =
    ZIO.serviceWithZIO[PromptLoader](_.load(name, context))

  val live: ZLayer[Any, Nothing, PromptLoader] =
    ZLayer.fromZIO {
      Ref.Synchronized.make(Map.empty[String, String]).map(PromptLoaderCached.apply)
    }

  val reloading: ZLayer[Any, Nothing, PromptLoader] =
    ZLayer.succeed(PromptLoaderReloading())

  val fromSettings: ZLayer[ConfigRepository, Nothing, PromptLoader] =
    ZLayer.fromZIO {
      for
        enabled <- ConfigRepository
                     .getSetting(ReloadingSettingKey)
                     .map(_.flatMap(_.value.toBooleanOption).getOrElse(false))
                     .catchAll(_ => ZIO.succeed(false))
        _       <- ZIO.logInfo(
                     s"PromptLoader mode: ${if enabled then "reloading" else "cached"} (setting '$ReloadingSettingKey')"
                   )
        loader  <-
          if enabled then ZIO.succeed(PromptLoaderReloading())
          else Ref.Synchronized.make(Map.empty[String, String]).map(PromptLoaderCached.apply)
      yield loader
    }

private object PromptLoaderInternals:
  private val PlaceholderRegex: Regex = "\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*\\}\\}".r

  def readPrompt(name: String): IO[PromptError, String] =
    val resourcePath = s"prompts/$name.md"
    ZIO
      .attemptBlockingIO(Option(getClass.getClassLoader.getResourceAsStream(resourcePath)))
      .mapError(err => PromptError.ReadFailed(name, err.getMessage))
      .flatMap {
        case Some(stream) =>
          ZIO
            .attemptBlockingIO {
              try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
              finally stream.close()
            }
            .mapError(err => PromptError.ReadFailed(name, err.getMessage))
        case None         =>
          ZIO.fail(PromptError.MissingPrompt(name))
      }

  def interpolate(name: String, template: String, context: Map[String, String]): IO[PromptError, String] =
    val placeholders = placeholderKeys(template)
    val missing      = placeholders.filterNot(context.contains).toList.sorted

    if missing.nonEmpty then ZIO.fail(PromptError.MissingPlaceholders(name, missing))
    else
      val rendered   = PlaceholderRegex.replaceAllIn(template, mtch => context(mtch.group(1)))
      val unresolved = placeholderKeys(rendered).toList.sorted
      if unresolved.nonEmpty then ZIO.fail(PromptError.UnresolvedPlaceholders(name, unresolved))
      else ZIO.succeed(rendered)

  private def placeholderKeys(content: String): Set[String] =
    PlaceholderRegex.findAllMatchIn(content).map(_.group(1)).toSet

final private case class PromptLoaderCached(cache: Ref.Synchronized[Map[String, String]]) extends PromptLoader:
  override def load(name: String, context: Map[String, String] = Map.empty): IO[PromptError, String] =
    for
      template <- cache.modifyZIO { current =>
                    current.get(name) match
                      case Some(value) => ZIO.succeed(value -> current)
                      case None        =>
                        PromptLoaderInternals.readPrompt(name).map(template =>
                          template -> current.updated(name, template)
                        )
                  }
      rendered <- PromptLoaderInternals.interpolate(name, template, context)
    yield rendered

final private case class PromptLoaderReloading() extends PromptLoader:
  override def load(name: String, context: Map[String, String] = Map.empty): IO[PromptError, String] =
    PromptLoaderInternals.readPrompt(name).flatMap(template =>
      PromptLoaderInternals.interpolate(name, template, context)
    )
