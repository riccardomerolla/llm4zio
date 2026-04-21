package issues.control

import java.time.Instant

import scala.util.matching.Regex

import zio.*
import zio.json.*

import _root_.config.entity.ConfigRepository
import issues.entity.*
import issues.entity.api.*
import shared.errors.PersistenceError

/** Service layer for issue-template and agent-pipeline management.
  *
  * Extracted from `IssueController` in phase 4F.1 so that both the template CRUD routes and the issue-creation routes
  * that resolve templates can share the same logic and state.
  */
trait IssueTemplateService:
  def listTemplates: IO[PersistenceError, List[IssueTemplate]]
  def getTemplate(id: String): IO[PersistenceError, IssueTemplate]
  def createTemplate(request: IssueTemplateUpsertRequest): IO[PersistenceError, IssueTemplate]
  def updateTemplate(id: String, request: IssueTemplateUpsertRequest): IO[PersistenceError, IssueTemplate]
  def deleteTemplate(id: String): IO[PersistenceError, Unit]

  def listPipelines: IO[PersistenceError, List[AgentPipeline]]
  def getPipeline(id: String): IO[PersistenceError, AgentPipeline]
  def createPipeline(request: PipelineCreateRequest): IO[PersistenceError, AgentPipeline]

  def normalizeVariableValues(values: Map[String, String]): Map[String, String]
  def resolveTemplateVariables(template: IssueTemplate, provided: Map[String, String]): Map[String, String]
  def validateTemplateVariables(template: IssueTemplate, values: Map[String, String]): IO[PersistenceError, Unit]
  def applyTemplateVariables(source: String, values: Map[String, String]): String

object IssueTemplateService:

  val live: ZLayer[ConfigRepository, Nothing, IssueTemplateService] =
    ZLayer.fromFunction(IssueTemplateServiceLive.apply)

  val templateSettingPrefix: String = "issue.template.custom."
  val pipelineSettingPrefix: String = "pipeline.custom."

  val templatePattern: Regex = "\\{\\{\\s*([a-zA-Z0-9_-]+)\\s*\\}\\}".r

  val builtInTemplates: List[IssueTemplate] = List(
    IssueTemplate(
      id = "bug-fix",
      name = "Bug Fix",
      description = "Patch a defect with root cause analysis and validation.",
      issueType = "bug",
      priority = IssuePriority.High,
      tags = List("bug", "fix"),
      titleTemplate = "Fix {{component}} failure in {{area}}",
      descriptionTemplate =
        """# Problem
          |{{problem}}
          |
          |# Root Cause
          |{{root_cause}}
          |
          |# Acceptance Criteria
          |- [ ] Reproduce the issue
          |- [ ] Implement fix in {{component}}
          |- [ ] Add regression coverage for {{area}}
          |""".stripMargin,
      variables = List(
        TemplateVariable("component", "Component", Some("Subsystem affected by the bug"), required = true),
        TemplateVariable("area", "Area", Some("Functional area where the bug happens"), required = true),
        TemplateVariable("problem", "Problem Summary", Some("What is broken"), required = true),
        TemplateVariable("root_cause", "Root Cause", Some("Known or suspected cause"), required = false),
      ),
      isBuiltin = true,
    ),
    IssueTemplate(
      id = "feature",
      name = "Feature",
      description = "Define a feature request with user value and deliverables.",
      issueType = "feature",
      priority = IssuePriority.Medium,
      tags = List("feature"),
      titleTemplate = "Implement {{feature_name}}",
      descriptionTemplate =
        """# Goal
          |{{goal}}
          |
          |# User Value
          |{{user_value}}
          |
          |# Scope
          |{{scope}}
          |
          |# Acceptance Criteria
          |- [ ] Feature available for {{target_user}}
          |- [ ] Documentation updated
          |""".stripMargin,
      variables = List(
        TemplateVariable("feature_name", "Feature Name", required = true),
        TemplateVariable("goal", "Goal", required = true),
        TemplateVariable("user_value", "User Value", required = true),
        TemplateVariable("scope", "Scope", required = true),
        TemplateVariable("target_user", "Target User", required = true),
      ),
      isBuiltin = true,
    ),
    IssueTemplate(
      id = "refactor",
      name = "Refactor",
      description = "Track structural improvements without behavior changes.",
      issueType = "refactor",
      priority = IssuePriority.Medium,
      tags = List("refactor", "tech-debt"),
      titleTemplate = "Refactor {{module}} for {{objective}}",
      descriptionTemplate =
        """# Objective
          |{{objective}}
          |
          |# Current Pain
          |{{pain}}
          |
          |# Refactor Plan
          |{{plan}}
          |
          |# Safety Checks
          |- [ ] No behavioral regressions
          |- [ ] Tests updated for {{module}}
          |""".stripMargin,
      variables = List(
        TemplateVariable("module", "Module", required = true),
        TemplateVariable("objective", "Objective", required = true),
        TemplateVariable("pain", "Current Pain", required = true),
        TemplateVariable("plan", "Refactor Plan", required = true),
      ),
      isBuiltin = true,
    ),
    IssueTemplate(
      id = "code-review",
      name = "Code Review",
      description = "Request a targeted review with explicit risk focus.",
      issueType = "review",
      priority = IssuePriority.Medium,
      tags = List("review"),
      titleTemplate = "Review {{scope}} changes",
      descriptionTemplate =
        """# Context
          |{{context}}
          |
          |# Review Scope
          |{{scope}}
          |
          |# Focus Areas
          |- Correctness
          |- Regressions
          |- Test coverage gaps
          |
          |# Notes
          |{{notes}}
          |""".stripMargin,
      variables = List(
        TemplateVariable("scope", "Scope", required = true),
        TemplateVariable("context", "Context", required = true),
        TemplateVariable("notes", "Notes", required = false),
      ),
      isBuiltin = true,
    ),
  )

final case class IssueTemplateServiceLive(configRepository: ConfigRepository) extends IssueTemplateService:
  import IssueTemplateService.*

  override def listTemplates: IO[PersistenceError, List[IssueTemplate]] =
    for
      rows      <- configRepository.getSettingsByPrefix(templateSettingPrefix)
      customRaw <- ZIO.foreach(rows.sortBy(_.key)) { row =>
                     ZIO
                       .fromEither(row.value.fromJson[IssueTemplate])
                       .map { parsed =>
                         val id = row.key.stripPrefix(templateSettingPrefix)
                         parsed.copy(
                           id = id,
                           isBuiltin = false,
                           createdAt = Some(row.updatedAt),
                           updatedAt = Some(row.updatedAt),
                         )
                       }
                       .either
                       .flatMap {
                         case Right(template) => ZIO.succeed(Some(template))
                         case Left(error)     =>
                           ZIO.logWarning(
                             s"Skipping invalid issue template setting key=${row.key}: $error"
                           ) *> ZIO.succeed(None)
                       }
                   }
    yield builtInTemplates ++ customRaw.flatten

  override def getTemplate(id: String): IO[PersistenceError, IssueTemplate] =
    listTemplates.flatMap { templates =>
      ZIO
        .fromOption(templates.find(_.id == id))
        .orElseFail(PersistenceError.QueryFailed("issue_template", s"Template not found: $id"))
    }

  override def listPipelines: IO[PersistenceError, List[AgentPipeline]] =
    for
      rows      <- configRepository.getSettingsByPrefix(pipelineSettingPrefix)
      pipelines <- ZIO.foreach(rows.sortBy(_.key)) { row =>
                     ZIO
                       .fromEither(row.value.fromJson[AgentPipeline])
                       .map(_.copy(id = row.key.stripPrefix(pipelineSettingPrefix), updatedAt = row.updatedAt))
                       .either
                       .flatMap {
                         case Right(p) => ZIO.succeed(Some(p))
                         case Left(e)  =>
                           ZIO.logWarning(s"Skipping invalid pipeline setting key=${row.key}: $e") *>
                             ZIO.succeed(None)
                       }
                   }
    yield pipelines.flatten

  override def getPipeline(id: String): IO[PersistenceError, AgentPipeline] =
    listPipelines.flatMap { values =>
      ZIO
        .fromOption(values.find(_.id == id))
        .orElseFail(PersistenceError.QueryFailed("pipeline", s"Pipeline not found: $id"))
    }

  override def createPipeline(request: PipelineCreateRequest): IO[PersistenceError, AgentPipeline] =
    for
      _        <- validatePipelineName(request.name)
      _        <- validatePipelineSteps(request.steps)
      now      <- Clock.instant
      cleanName = request.name.trim
      id        = s"${normalizeTemplateId(cleanName)}-${now.toEpochMilli}"
      pipeline  = AgentPipeline(
                    id = id,
                    name = cleanName,
                    steps = request.steps.map(step =>
                      step.copy(
                        agentId = step.agentId.trim,
                        promptOverride = step.promptOverride.map(_.trim).filter(_.nonEmpty),
                      )
                    ),
                    createdAt = now,
                    updatedAt = now,
                  )
      _        <- configRepository.upsertSetting(pipelineSettingPrefix + id, pipeline.toJson)
    yield pipeline

  override def createTemplate(request: IssueTemplateUpsertRequest): IO[PersistenceError, IssueTemplate] =
    for
      now       <- Clock.instant
      templateId = request.id.map(normalizeTemplateId).filter(_.nonEmpty).getOrElse(s"custom-${now.toEpochMilli}")
      _         <- ensureCustomTemplateIdAllowed(templateId)
      template  <- buildTemplate(templateId, request, now)
      _         <- configRepository.upsertSetting(templateSettingPrefix + templateId, template.toJson)
    yield template

  override def updateTemplate(id: String, request: IssueTemplateUpsertRequest): IO[PersistenceError, IssueTemplate] =
    for
      normalized <- ZIO.succeed(normalizeTemplateId(id))
      _          <- ensureCustomTemplateIdAllowed(normalized)
      existing   <- configRepository.getSetting(templateSettingPrefix + normalized)
      _          <- ZIO
                      .fromOption(existing)
                      .orElseFail(PersistenceError.QueryFailed("issue_template", s"Template not found: $normalized"))
      now        <- Clock.instant
      template   <- buildTemplate(normalized, request.copy(id = Some(normalized)), now)
      _          <- configRepository.upsertSetting(templateSettingPrefix + normalized, template.toJson)
    yield template

  override def deleteTemplate(id: String): IO[PersistenceError, Unit] =
    val normalized = normalizeTemplateId(id)
    if builtInTemplates.exists(_.id == normalized) then
      ZIO.fail(PersistenceError.QueryFailed("issue_template", s"Built-in template cannot be deleted: $normalized"))
    else
      configRepository.deleteSetting(templateSettingPrefix + normalized)

  override def normalizeVariableValues(values: Map[String, String]): Map[String, String] =
    values.collect { case (k, v) if k.trim.nonEmpty => k.trim -> v }

  override def resolveTemplateVariables(
    template: IssueTemplate,
    provided: Map[String, String],
  ): Map[String, String] =
    template.variables.foldLeft(provided) { (acc, variable) =>
      val current = acc.get(variable.name).map(_.trim).filter(_.nonEmpty)
      val merged  = current.orElse(variable.defaultValue.map(_.trim).filter(_.nonEmpty))
      merged match
        case Some(value) => acc.updated(variable.name, value)
        case None        => acc
    }

  override def validateTemplateVariables(
    template: IssueTemplate,
    values: Map[String, String],
  ): IO[PersistenceError, Unit] =
    ZIO.foreachDiscard(template.variables) { variable =>
      val value = values.get(variable.name).map(_.trim).filter(_.nonEmpty)
      ZIO
        .fail(
          PersistenceError.QueryFailed(
            "issue_template",
            s"Missing required template variable: ${variable.name}",
          )
        )
        .when(variable.required && value.isEmpty)
    }

  override def applyTemplateVariables(source: String, values: Map[String, String]): String =
    templatePattern.replaceAllIn(source, m => values.getOrElse(m.group(1), ""))

  private def validatePipelineName(name: String): IO[PersistenceError, Unit] =
    ZIO
      .fail(PersistenceError.QueryFailed("pipeline", "Pipeline name is required"))
      .when(name.trim.isEmpty)
      .unit

  private def validatePipelineSteps(steps: List[PipelineStep]): IO[PersistenceError, Unit] =
    for
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("pipeline", "Pipeline must contain at least one step"))
             .when(steps.isEmpty)
      _ <- ZIO.foreachDiscard(steps.zipWithIndex) {
             case (step, idx) =>
               ZIO
                 .fail(PersistenceError.QueryFailed("pipeline", s"Pipeline step ${idx + 1} requires an agentId"))
                 .when(step.agentId.trim.isEmpty)
           }
    yield ()

  private def buildTemplate(
    id: String,
    request: IssueTemplateUpsertRequest,
    timestamp: Instant,
  ): IO[PersistenceError, IssueTemplate] =
    for
      normalizedTags <- ZIO.succeed(request.tags.map(_.trim).filter(_.nonEmpty))
      normalizedVars <- ZIO.succeed(request.variables.map(v =>
                          v.copy(
                            name = v.name.trim,
                            label = v.label.trim,
                            description = v.description.map(_.trim).filter(_.nonEmpty),
                            defaultValue = v.defaultValue.map(_.trim).filter(_.nonEmpty),
                          )
                        ))
      _              <- validateTemplatePayload(request, normalizedVars)
    yield IssueTemplate(
      id = id,
      name = request.name.trim,
      description = request.description.trim,
      issueType = request.issueType.trim,
      priority = request.priority,
      tags = normalizedTags,
      titleTemplate = request.titleTemplate,
      descriptionTemplate = request.descriptionTemplate,
      variables = normalizedVars,
      isBuiltin = false,
      createdAt = Some(timestamp),
      updatedAt = Some(timestamp),
    )

  private def validateTemplatePayload(
    request: IssueTemplateUpsertRequest,
    variables: List[TemplateVariable],
  ): IO[PersistenceError, Unit] =
    for
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", "Template name is required"))
             .when(request.name.trim.isEmpty)
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", "Template description is required"))
             .when(request.description.trim.isEmpty)
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", "Template issueType is required"))
             .when(request.issueType.trim.isEmpty)
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", "Template titleTemplate is required"))
             .when(request.titleTemplate.trim.isEmpty)
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", "Template descriptionTemplate is required"))
             .when(request.descriptionTemplate.trim.isEmpty)
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", "Template variable names must be unique"))
             .when(variables.map(_.name).distinct.size != variables.size)
      _ <- ZIO.foreachDiscard(variables) { variable =>
             ZIO
               .fail(PersistenceError.QueryFailed("issue_template", "Template variable name cannot be empty"))
               .when(variable.name.trim.isEmpty) *>
               ZIO
                 .fail(PersistenceError.QueryFailed("issue_template", "Template variable label cannot be empty"))
                 .when(variable.label.trim.isEmpty)
           }
    yield ()

  private def normalizeTemplateId(id: String): String =
    id.trim.toLowerCase.replaceAll("[^a-z0-9_-]+", "-").replaceAll("-{2,}", "-").stripPrefix("-").stripSuffix("-")

  private def ensureCustomTemplateIdAllowed(id: String): IO[PersistenceError, Unit] =
    for
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", "Template id cannot be empty"))
             .when(id.trim.isEmpty)
      _ <- ZIO
             .fail(PersistenceError.QueryFailed("issue_template", s"Template id reserved by built-in template: $id"))
             .when(builtInTemplates.exists(_.id == id))
    yield ()
