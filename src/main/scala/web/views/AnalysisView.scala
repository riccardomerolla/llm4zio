package web.views

import zio.json.*

import db.{ CobolAnalysisRow, CobolFileRow }
import models.*
import scalatags.Text.all.*

object AnalysisView:

  def list(runId: Long, files: List[CobolFileRow], analyses: List[CobolAnalysisRow]): String =
    val analysisByFile = analyses.groupBy(_.fileId)
    Layout.page(s"Analysis — Run #$runId", "/analysis")(
      h1(cls := "text-2xl font-bold text-white mb-6")(s"Analysis for Run #$runId"),
      // Search bar with HTMX
      div(cls := "mb-6")(
        div(cls := "relative")(
          input(
            `type`               := "search",
            name                 := "q",
            placeholder          := "Search files...",
            cls                  := "block w-full rounded-md bg-white/5 border-0 py-2 pl-10 pr-3 text-white shadow-sm ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500 sm:text-sm/6",
            attr("hx-get")       := s"/api/analysis/search?runId=$runId",
            attr("hx-trigger")   := "keyup changed delay:300ms",
            attr("hx-target")    := "#file-list",
            attr("hx-swap")      := "innerHTML",
            attr("hx-indicator") := "#search-indicator",
          ),
          div(cls := "pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3")(
            Components.svgIcon(
              "m21 21-5.197-5.197m0 0A7.5 7.5 0 1 0 5.196 5.196a7.5 7.5 0 0 0 10.607 10.607Z",
              "size-5 text-gray-400",
            )
          ),
        ),
        span(id := "search-indicator", cls := "htmx-indicator text-xs text-gray-500 mt-1 inline-block")(
          "Searching..."
        ),
      ),
      // File list
      div(id := "file-list")(
        fileListContent(files, analysisByFile)
      ),
    )

  def detail(file: CobolFileRow, analysis: CobolAnalysisRow): String =
    Layout.page(s"Analysis — ${file.name}", s"/analysis/${file.id}")(
      // Header
      div(cls := "mb-8")(
        div(cls := "flex items-center gap-3 mb-2")(
          h1(cls := "text-2xl font-bold text-white")(file.name),
          Components.fileTypeBadge(file.fileType),
        ),
        p(cls := "text-sm text-gray-400")(
          s"${file.path}  •  ${file.lineCount} lines  •  ${file.size} bytes  •  ${file.encoding}"
        ),
      ),
      detailBody(analysis.analysisJson),
    )

  def searchFragment(files: List[CobolFileRow]): String =
    if files.isEmpty then
      div(cls := "text-center py-8 text-gray-400")("No files match your search.").render
    else
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg overflow-hidden")(
        table(cls := "min-w-full divide-y divide-white/10")(
          tbody(cls := "divide-y divide-white/5")(
            files.map(fileRow(_, Map.empty))
          )
        )
      ).render

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private def detailBody(json: String): Frag =
    json.fromJson[CobolAnalysis].toOption match
      case Some(ca) =>
        frag(
          divisionsSection(ca.divisions),
          variablesSection(ca.variables),
          proceduresSection(ca.procedures),
          copybooksSection(ca.copybooks),
          complexitySection(ca.complexity),
          rawJsonSection(json),
        )
      case None     =>
        json.fromJson[SpringBootProject].toOption match
          case Some(project) =>
            frag(
              transformOverviewSection(project),
              entitiesSection(project.entities),
              servicesSection(project.services),
              controllersSection(project.controllers),
              repositoriesSection(project.repositories),
              buildConfigSection(project.configuration, project.buildFile),
              rawJsonSection(json),
            )
          case None          =>
            json.fromJson[ValidationReport].toOption match
              case Some(report) =>
                frag(
                  validationOverviewSection(report),
                  compileResultSection(report.compileResult),
                  coverageSection(report.coverageMetrics),
                  issuesSection(report.issues),
                  semanticValidationSection(report.semanticValidation),
                  rawJsonSection(json),
                )
              case None         =>
                div(cls := "bg-red-500/10 border border-red-500/30 rounded-lg p-4")(
                  p(cls := "text-red-300 mb-2")("Failed to parse analysis JSON."),
                  pre(cls := "text-xs text-gray-400 overflow-x-auto whitespace-pre-wrap font-mono")(json),
                )

  private def fileListContent(files: List[CobolFileRow], analysisByFile: Map[Long, List[CobolAnalysisRow]]): Frag =
    if files.isEmpty then Components.emptyState("No files found for this run.")
    else
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg overflow-hidden")(
        table(cls := "min-w-full divide-y divide-white/10")(
          thead(cls := "bg-white/5")(
            tr(
              th(cls := "py-3 pl-6 pr-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                "File"
              ),
              th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                "Type"
              ),
              th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                "Lines"
              ),
              th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                "Analysis"
              ),
              th(cls := "relative py-3 pl-3 pr-6"),
            )
          ),
          tbody(cls := "divide-y divide-white/5")(
            files.map(fileRow(_, analysisByFile))
          ),
        )
      )

  private def fileRow(file: CobolFileRow, analysisByFile: Map[Long, List[CobolAnalysisRow]]): Frag =
    val count = analysisByFile.getOrElse(file.id, Nil).size
    tr(cls := "hover:bg-white/5")(
      td(cls := "py-4 pl-6 pr-3 text-sm")(
        div(cls := "font-medium text-white")(file.name),
        div(cls := "text-xs text-gray-500 truncate max-w-xs")(file.path),
      ),
      td(cls := "whitespace-nowrap px-3 py-4 text-sm")(Components.fileTypeBadge(file.fileType)),
      td(cls := "whitespace-nowrap px-3 py-4 text-sm text-gray-400 tabular-nums")(file.lineCount.toString),
      td(cls := "whitespace-nowrap px-3 py-4 text-sm text-gray-400")(
        if count > 0 then span(cls := "text-green-400")(s"$count record(s)") else span(cls := "text-gray-500")("—")
      ),
      td(cls := "relative whitespace-nowrap py-4 pl-3 pr-6 text-right text-sm")(
        a(href := s"/analysis/${file.id}", cls := "text-indigo-400 hover:text-indigo-300 font-medium")("View")
      ),
    )

  private def divisionsSection(divisions: CobolDivisions): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")("Divisions"),
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-2")(
        divisionCard("Identification", divisions.identification),
        divisionCard("Environment", divisions.environment),
        divisionCard("Data", divisions.data),
        divisionCard("Procedure", divisions.procedure),
      ),
    )

  private def divisionCard(name: String, content: Option[String]): Frag =
    div(cls := "bg-white/5 rounded-md p-3")(
      h3(cls := "text-sm font-medium text-gray-300 mb-1")(name),
      content match
        case Some(c) => pre(cls := "text-xs text-gray-400 whitespace-pre-wrap font-mono max-h-40 overflow-y-auto")(c)
        case None    => p(cls := "text-xs text-gray-500 italic")("Not present"),
    )

  private def variablesSection(variables: List[Variable]): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")(s"Variables (${variables.length})"),
      if variables.isEmpty then p(cls := "text-sm text-gray-400")("No variables found.")
      else
        div(cls := "overflow-x-auto")(
          table(cls := "min-w-full divide-y divide-white/10 text-sm")(
            thead(
              tr(
                th(cls := "py-2 pr-3 text-left text-xs font-semibold text-gray-400")("Level"),
                th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Name"),
                th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Type"),
                th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Picture"),
                th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Usage"),
              )
            ),
            tbody(cls := "divide-y divide-white/5")(
              variables.map { v =>
                tr(
                  td(cls := "py-2 pr-3 text-gray-400 tabular-nums")(v.level.toString.padTo(2, ' ')),
                  td(cls := "px-3 py-2 text-white font-mono")(v.name),
                  td(cls := "px-3 py-2 text-gray-400")(v.dataType),
                  td(cls := "px-3 py-2 text-gray-400 font-mono")(v.picture.getOrElse("—")),
                  td(cls := "px-3 py-2 text-gray-400")(v.usage.getOrElse("—")),
                )
              }
            ),
          )
        ),
    )

  private def proceduresSection(procedures: List[Procedure]): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")(s"Procedures (${procedures.length})"),
      if procedures.isEmpty then p(cls := "text-sm text-gray-400")("No procedures found.")
      else
        div(cls := "space-y-3")(
          procedures.map { proc =>
            div(cls := "bg-white/5 rounded-md p-4")(
              h3(cls := "text-sm font-semibold text-white mb-2")(proc.name),
              if proc.paragraphs.nonEmpty then
                div(cls := "mb-2")(
                  span(cls := "text-xs font-medium text-gray-400")("Paragraphs: "),
                  span(cls := "text-xs text-gray-300")(proc.paragraphs.mkString(", ")),
                )
              else frag(),
              if proc.statements.nonEmpty then
                div(
                  span(cls := "text-xs font-medium text-gray-400")(s"${proc.statements.length} statement(s)")
                )
              else frag(),
            )
          }
        ),
    )

  private def copybooksSection(copybooks: List[String]): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")(s"Copybook Dependencies (${copybooks.length})"),
      if copybooks.isEmpty then p(cls := "text-sm text-gray-400")("No copybook dependencies.")
      else
        div(cls := "flex flex-wrap gap-2")(
          copybooks.map { cb =>
            span(
              cls := "inline-flex items-center rounded-md bg-purple-500/10 px-2.5 py-1 text-xs font-medium text-purple-400 ring-1 ring-inset ring-purple-500/20"
            )(cb)
          }
        ),
    )

  private def complexitySection(metrics: ComplexityMetrics): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")("Complexity Metrics"),
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-3")(
        metricCard("Cyclomatic Complexity", metrics.cyclomaticComplexity.toString),
        metricCard("Lines of Code", metrics.linesOfCode.toString),
        metricCard("Procedures", metrics.numberOfProcedures.toString),
      ),
    )

  private def metricCard(label: String, value: String): Frag =
    div(cls := "bg-white/5 rounded-md p-4 text-center")(
      p(cls := "text-2xl font-semibold text-white")(value),
      p(cls := "text-xs text-gray-400 mt-1")(label),
    )

  private def rawJsonSection(json: String): Frag =
    tag("details")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg mb-6")(
      tag("summary")(cls := "px-6 py-4 text-sm font-semibold text-gray-300 cursor-pointer hover:text-white")(
        "Raw JSON"
      ),
      div(cls := "px-6 pb-4")(
        pre(
          cls := "text-xs text-gray-400 whitespace-pre-wrap font-mono max-h-96 overflow-y-auto bg-black/20 rounded-md p-4"
        )(
          json
        )
      ),
    )

  // ---------------------------------------------------------------------------
  // Transform Report sections
  // ---------------------------------------------------------------------------

  private def transformOverviewSection(project: SpringBootProject): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-2")("Transform Report"),
      p(cls := "text-sm text-gray-400 mb-4")(
        s"Project: ${project.projectName}  •  Source: ${project.sourceProgram}  •  Generated: ${project.generatedAt}"
      ),
      div(cls := "grid grid-cols-2 gap-4 sm:grid-cols-5")(
        metricCard("Entities", project.entities.length.toString),
        metricCard("Services", project.services.length.toString),
        metricCard("Controllers", project.controllers.length.toString),
        metricCard("Repositories", project.repositories.length.toString),
        metricCard("Dependencies", project.configuration.dependencies.length.toString),
      ),
    )

  private def entitiesSection(entities: List[JavaEntity]): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")(s"Entities (${entities.length})"),
      if entities.isEmpty then p(cls := "text-sm text-gray-400")("No entities generated.")
      else
        div(cls := "space-y-4")(
          entities.map { entity =>
            tag("details")(cls := "bg-white/5 rounded-md")(
              tag("summary")(cls := "px-4 py-3 cursor-pointer hover:bg-white/5")(
                div(cls := "flex items-center gap-3")(
                  span(cls := "text-sm font-semibold text-white")(entity.className),
                  span(cls := "text-xs text-gray-400")(entity.packageName),
                  frag(entity.annotations.map(annotationBadge)),
                )
              ),
              div(cls := "px-4 pb-4")(
                if entity.fields.nonEmpty then
                  table(cls := "min-w-full divide-y divide-white/10 text-sm mb-3")(
                    thead(
                      tr(
                        th(cls := "py-2 pr-3 text-left text-xs font-semibold text-gray-400")("Field"),
                        th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Java Type"),
                        th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("COBOL Source"),
                        th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Annotations"),
                      )
                    ),
                    tbody(cls := "divide-y divide-white/5")(
                      entity.fields.map { f =>
                        tr(
                          td(cls := "py-2 pr-3 text-white font-mono")(f.name),
                          td(cls := "px-3 py-2 text-gray-400 font-mono")(f.javaType),
                          td(cls := "px-3 py-2 text-gray-400 font-mono")(f.cobolSource),
                          td(cls := "px-3 py-2")(frag(f.annotations.map(annotationBadge))),
                        )
                      }
                    ),
                  )
                else frag(),
                tag("details")(cls := "mt-2")(
                  tag("summary")(cls := "text-xs text-gray-400 cursor-pointer hover:text-gray-300")(
                    "Source Code"
                  ),
                  pre(
                    cls := "text-xs text-gray-400 whitespace-pre-wrap font-mono max-h-60 overflow-y-auto bg-black/20 rounded-md p-3 mt-2"
                  )(entity.sourceCode),
                ),
              ),
            )
          }
        ),
    )

  private def servicesSection(services: List[JavaService]): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")(s"Services (${services.length})"),
      if services.isEmpty then p(cls := "text-sm text-gray-400")("No services generated.")
      else
        div(cls := "space-y-4")(
          services.map { svc =>
            div(cls := "bg-white/5 rounded-md p-4")(
              h3(cls := "text-sm font-semibold text-white mb-3")(svc.name),
              if svc.methods.nonEmpty then
                div(cls := "space-y-2")(
                  svc.methods.map { m =>
                    div(cls := "bg-black/20 rounded-md p-3")(
                      p(cls := "text-xs font-mono text-indigo-400")(
                        s"${m.returnType} ${m.name}(${m.parameters.map(p => s"${p.javaType} ${p.name}").mkString(", ")})"
                      ),
                      pre(
                        cls := "text-xs text-gray-400 whitespace-pre-wrap font-mono mt-2 max-h-40 overflow-y-auto"
                      )(m.body),
                    )
                  }
                )
              else p(cls := "text-xs text-gray-500")("No methods"),
            )
          }
        ),
    )

  private def controllersSection(controllers: List[JavaController]): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")(s"Controllers (${controllers.length})"),
      if controllers.isEmpty then p(cls := "text-sm text-gray-400")("No controllers generated.")
      else
        div(cls := "space-y-4")(
          controllers.map { ctrl =>
            div(cls := "bg-white/5 rounded-md p-4")(
              div(cls := "flex items-center gap-3 mb-3")(
                h3(cls := "text-sm font-semibold text-white")(ctrl.name),
                span(cls := "text-xs text-gray-400 font-mono")(ctrl.basePath),
              ),
              if ctrl.endpoints.nonEmpty then
                table(cls := "min-w-full divide-y divide-white/10 text-sm")(
                  thead(
                    tr(
                      th(cls := "py-2 pr-3 text-left text-xs font-semibold text-gray-400")("Method"),
                      th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Path"),
                      th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Handler"),
                    )
                  ),
                  tbody(cls := "divide-y divide-white/5")(
                    ctrl.endpoints.map { ep =>
                      tr(
                        td(cls := "py-2 pr-3")(httpMethodBadge(ep.method)),
                        td(cls := "px-3 py-2 text-gray-400 font-mono")(ep.path),
                        td(cls := "px-3 py-2 text-white font-mono")(ep.methodName),
                      )
                    }
                  ),
                )
              else p(cls := "text-xs text-gray-500")("No endpoints"),
            )
          }
        ),
    )

  private def repositoriesSection(repositories: List[JavaRepository]): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")(s"Repositories (${repositories.length})"),
      if repositories.isEmpty then p(cls := "text-sm text-gray-400")("No repositories generated.")
      else
        div(cls := "space-y-3")(
          repositories.map { repo =>
            div(cls := "bg-white/5 rounded-md p-4")(
              div(cls := "flex items-center gap-3 mb-2")(
                h3(cls := "text-sm font-semibold text-white")(repo.name),
                frag(repo.annotations.map(annotationBadge)),
              ),
              div(cls := "flex flex-wrap gap-x-6 gap-y-1 text-xs text-gray-400 mb-2")(
                span(s"Entity: ${repo.entityName}"),
                span(s"ID Type: ${repo.idType}"),
                span(s"Package: ${repo.packageName}"),
              ),
              tag("details")(
                tag("summary")(cls := "text-xs text-gray-400 cursor-pointer hover:text-gray-300")(
                  "Source Code"
                ),
                pre(
                  cls := "text-xs text-gray-400 whitespace-pre-wrap font-mono max-h-60 overflow-y-auto bg-black/20 rounded-md p-3 mt-2"
                )(repo.sourceCode),
              ),
            )
          }
        ),
    )

  private def buildConfigSection(config: ProjectConfiguration, build: BuildFile): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")("Build Configuration"),
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-3 mb-4")(
        metricCard("Group ID", config.groupId),
        metricCard("Artifact ID", config.artifactId),
        metricCard("Build Tool", build.tool),
      ),
      div(cls := "mb-4")(
        h3(cls := "text-sm font-semibold text-gray-300 mb-2")(
          s"Dependencies (${config.dependencies.length})"
        ),
        if config.dependencies.nonEmpty then
          div(cls := "flex flex-wrap gap-2")(
            config.dependencies.map { dep =>
              span(
                cls := "inline-flex items-center rounded-md bg-blue-500/10 px-2.5 py-1 text-xs font-medium text-blue-400 ring-1 ring-inset ring-blue-500/20"
              )(dep)
            }
          )
        else p(cls := "text-xs text-gray-500")("No dependencies"),
      ),
      tag("details")(
        tag("summary")(cls := "text-sm text-gray-400 cursor-pointer hover:text-gray-300")(
          s"${build.tool} Build File"
        ),
        pre(
          cls := "text-xs text-gray-400 whitespace-pre-wrap font-mono max-h-96 overflow-y-auto bg-black/20 rounded-md p-4 mt-2"
        )(build.content),
      ),
    )

  private def annotationBadge(annotation: String): Frag =
    span(
      cls := "inline-flex items-center rounded-md bg-yellow-500/10 px-2 py-0.5 text-xs font-medium text-yellow-400 ring-1 ring-inset ring-yellow-500/20"
    )(annotation)

  private def httpMethodBadge(method: HttpMethod): Frag =
    val (bg, text) = method match
      case HttpMethod.GET    => ("bg-green-500/10 ring-green-500/20", "text-green-400")
      case HttpMethod.POST   => ("bg-blue-500/10 ring-blue-500/20", "text-blue-400")
      case HttpMethod.PUT    => ("bg-yellow-500/10 ring-yellow-500/20", "text-yellow-400")
      case HttpMethod.DELETE => ("bg-red-500/10 ring-red-500/20", "text-red-400")
      case HttpMethod.PATCH  => ("bg-purple-500/10 ring-purple-500/20", "text-purple-400")
    span(
      cls := s"inline-flex items-center rounded-md px-2 py-0.5 text-xs font-bold $bg $text ring-1 ring-inset"
    )(method.toString)

  // ---------------------------------------------------------------------------
  // Validation Report sections
  // ---------------------------------------------------------------------------

  private def validationOverviewSection(report: ValidationReport): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-2")("Validation Report"),
      div(cls := "flex items-center gap-4 mb-4")(
        p(cls := "text-sm text-gray-400")(
          s"Project: ${report.projectName}  •  Validated: ${report.validatedAt}"
        ),
        validationStatusBadge(report.overallStatus),
      ),
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-3")(
        metricCard("Total Issues", report.issues.length.toString),
        metricCard("Errors", report.issues.count(_.severity == Severity.ERROR).toString),
        metricCard("Warnings", report.issues.count(_.severity == Severity.WARNING).toString),
      ),
    )

  private def compileResultSection(result: CompileResult): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")("Compilation Result"),
      div(cls := "flex items-center gap-4 mb-4")(
        if result.success then
          span(
            cls := "inline-flex items-center rounded-md bg-green-500/10 px-2.5 py-1 text-xs font-medium text-green-400 ring-1 ring-inset ring-green-500/20"
          )("Passed")
        else
          span(
            cls := "inline-flex items-center rounded-md bg-red-500/10 px-2.5 py-1 text-xs font-medium text-red-400 ring-1 ring-inset ring-red-500/20"
          )("Failed")
        ,
        span(cls := "text-sm text-gray-400")(s"Exit code: ${result.exitCode}"),
      ),
      if result.output.nonEmpty then
        tag("details")(
          tag("summary")(cls := "text-sm text-gray-400 cursor-pointer hover:text-gray-300")(
            "Compiler Output"
          ),
          pre(
            cls := "text-xs text-gray-400 whitespace-pre-wrap font-mono max-h-60 overflow-y-auto bg-black/20 rounded-md p-3 mt-2"
          )(result.output),
        )
      else frag(),
    )

  private def coverageSection(metrics: CoverageMetrics): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")("Coverage Metrics"),
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-3 mb-4")(
        metricCard("Variables Covered", f"${metrics.variablesCovered}%.1f%%"),
        metricCard("Procedures Covered", f"${metrics.proceduresCovered}%.1f%%"),
        metricCard("File Section Covered", f"${metrics.fileSectionCovered}%.1f%%"),
      ),
      if metrics.unmappedItems.nonEmpty then
        div(
          h3(cls := "text-sm font-semibold text-gray-300 mb-2")(
            s"Unmapped Items (${metrics.unmappedItems.length})"
          ),
          div(cls := "flex flex-wrap gap-2")(
            metrics.unmappedItems.map { item =>
              span(
                cls := "inline-flex items-center rounded-md bg-orange-500/10 px-2.5 py-1 text-xs font-medium text-orange-400 ring-1 ring-inset ring-orange-500/20"
              )(item)
            }
          ),
        )
      else frag(),
    )

  private def issuesSection(issues: List[ValidationIssue]): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")(s"Issues (${issues.length})"),
      if issues.isEmpty then p(cls := "text-sm text-gray-400")("No issues found.")
      else
        div(cls := "overflow-x-auto")(
          table(cls := "min-w-full divide-y divide-white/10 text-sm")(
            thead(
              tr(
                th(cls := "py-2 pr-3 text-left text-xs font-semibold text-gray-400")("Severity"),
                th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Category"),
                th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Message"),
                th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Location"),
                th(cls := "px-3 py-2 text-left text-xs font-semibold text-gray-400")("Suggestion"),
              )
            ),
            tbody(cls := "divide-y divide-white/5")(
              issues.map { issue =>
                tr(
                  td(cls := "py-2 pr-3")(severityBadge(issue.severity)),
                  td(cls := "px-3 py-2")(categoryBadge(issue.category)),
                  td(cls := "px-3 py-2 text-white max-w-md")(issue.message),
                  td(cls := "px-3 py-2 text-gray-400 font-mono text-xs")(
                    (issue.file, issue.line) match
                      case (Some(f), Some(l)) => s"$f:$l"
                      case (Some(f), None)    => f
                      case _                  => "\u2014"
                  ),
                  td(cls := "px-3 py-2 text-gray-400 text-xs")(issue.suggestion.getOrElse("\u2014")),
                )
              }
            ),
          )
        ),
    )

  private def semanticValidationSection(semantic: SemanticValidation): Frag =
    tag("section")(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 mb-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")("Semantic Validation"),
      div(cls := "grid grid-cols-1 gap-4 sm:grid-cols-3 mb-4")(
        metricCard(
          "Business Logic",
          if semantic.businessLogicPreserved then "Preserved" else "Not Preserved",
        ),
        metricCard("Confidence", f"${semantic.confidence}%.1f%%"),
        metricCard("Semantic Issues", semantic.issues.length.toString),
      ),
      if semantic.summary.nonEmpty then
        div(cls := "mb-4")(
          h3(cls := "text-sm font-semibold text-gray-300 mb-2")("Summary"),
          p(cls := "text-sm text-gray-400 whitespace-pre-wrap")(semantic.summary),
        )
      else frag(),
      if semantic.issues.nonEmpty then
        div(
          h3(cls := "text-sm font-semibold text-gray-300 mb-2")(
            s"Semantic Issues (${semantic.issues.length})"
          ),
          div(cls := "space-y-2")(
            semantic.issues.map { issue =>
              div(cls := "bg-black/20 rounded-md p-3 flex items-start gap-3")(
                severityBadge(issue.severity),
                div(
                  p(cls := "text-sm text-white")(issue.message),
                  issue.suggestion.map(s => p(cls := "text-xs text-gray-400 mt-1")(s"Suggestion: $s")),
                ),
              )
            }
          ),
        )
      else frag(),
    )

  private def validationStatusBadge(status: ValidationStatus): Frag =
    val (bg, text, label) = status match
      case ValidationStatus.Passed             =>
        ("bg-green-500/10 ring-green-500/20", "text-green-400", "Passed")
      case ValidationStatus.PassedWithWarnings =>
        ("bg-yellow-500/10 ring-yellow-500/20", "text-yellow-400", "Passed with Warnings")
      case ValidationStatus.Failed             =>
        ("bg-red-500/10 ring-red-500/20", "text-red-400", "Failed")
    span(
      cls := s"inline-flex items-center rounded-md px-2.5 py-1 text-xs font-medium $bg $text ring-1 ring-inset"
    )(label)

  private def severityBadge(severity: Severity): Frag =
    val (bg, text) = severity match
      case Severity.ERROR   => ("bg-red-500/10 ring-red-500/20", "text-red-400")
      case Severity.WARNING => ("bg-yellow-500/10 ring-yellow-500/20", "text-yellow-400")
      case Severity.INFO    => ("bg-blue-500/10 ring-blue-500/20", "text-blue-400")
    span(
      cls := s"inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium $bg $text ring-1 ring-inset"
    )(severity.toString)

  private def categoryBadge(category: IssueCategory): Frag =
    val (bg, text) = category match
      case IssueCategory.Compile        => ("bg-red-500/10 ring-red-500/20", "text-red-400")
      case IssueCategory.Coverage       => ("bg-orange-500/10 ring-orange-500/20", "text-orange-400")
      case IssueCategory.StaticAnalysis => ("bg-cyan-500/10 ring-cyan-500/20", "text-cyan-400")
      case IssueCategory.Semantic       => ("bg-purple-500/10 ring-purple-500/20", "text-purple-400")
      case IssueCategory.Convention     => ("bg-gray-500/10 ring-gray-500/20", "text-gray-400")
      case IssueCategory.Undefined      => ("bg-gray-500/10 ring-gray-500/20", "text-gray-500")
    span(
      cls := s"inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium $bg $text ring-1 ring-inset"
    )(category.toString)
