package shared.web

import java.time.Instant

import issues.entity.AgentIssue
import plan.entity.{ Plan, PlanStatus, PlanValidationStatus }
import scalatags.Text.all.*
import specification.entity.Specification

final case class PlanListItem(
  id: String,
  summary: String,
  status: PlanStatus,
  version: Int,
  specificationId: Option[String],
  linkedIssueCount: Int,
  workspaceId: Option[String],
  updatedAt: Instant,
)

object PlansView:

  def page(items: List[PlanListItem]): String =
    Layout.page("Plans", "/plans")(
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          h1(cls := "text-2xl font-bold text-white")("Plans"),
          p(cls := "mt-1 text-sm text-slate-300")(
            "Review persisted task decompositions, linked specifications, and issue batches created from planner sessions."
          ),
        ),
        if items.isEmpty then
          div(cls := "rounded-xl border border-dashed border-white/10 bg-slate-900/60 p-10 text-center")(
            p(cls := "text-slate-300")("No plans available yet."),
            p(cls := "mt-1 text-sm text-slate-500")(
              "Confirm a planner preview to persist its decomposition as a tracked plan."
            ),
          )
        else
          div(cls := "grid gap-4 lg:grid-cols-2")(
            items.sortBy(_.updatedAt).reverse.map(planCard)*
          ),
      )
    )

  def detailPage(plan: Plan, specification: Option[Specification], linkedIssues: List[AgentIssue]): String =
    val graph = PlanPreviewComponents.planGraph(plan.drafts)
    Layout.page(plan.summary, s"/plans/${plan.id.value}")(
      div(cls := "space-y-6")(
        breadcrumb(plan),
        header(plan),
        div(cls := "grid gap-6 xl:grid-cols-[minmax(0,2fr),minmax(22rem,1fr)]")(
          div(cls := "space-y-6")(
            rationalePanel(plan),
            draftsPanel(plan, graph),
            issuesPanel(linkedIssues),
          ),
          div(cls := "space-y-6")(
            traceabilityPanel(plan, specification),
            validationPanel(plan),
            versionHistory(plan),
          ),
        ),
      )
    )

  private def planCard(item: PlanListItem): Frag =
    a(
      href := s"/plans/${item.id}",
      cls  := "rounded-xl border border-white/10 bg-slate-900/60 p-5 transition hover:border-cyan-400/40 hover:bg-slate-900/80",
    )(
      div(cls := "flex items-start justify-between gap-3")(
        div(
          h2(cls := "text-lg font-semibold text-white")(item.summary),
          p(cls := "mt-1 text-xs uppercase tracking-wide text-slate-500")(item.id),
        ),
        statusBadge(item.status),
      ),
      div(cls := "mt-4 flex flex-wrap gap-2 text-xs text-slate-300")(
        metaChip(s"Version ${item.version}"),
        metaChip(s"${item.linkedIssueCount} linked issue(s)"),
        item.specificationId.map(id => metaChip(s"Spec $id")).getOrElse(metaChip("No linked spec")),
        item.workspaceId.map(id => metaChip(s"Workspace $id")).getOrElse(metaChip("No workspace")),
      ),
      p(cls := "mt-3 text-xs text-slate-500")(s"Updated ${timestamp(item.updatedAt)}"),
    )

  private def breadcrumb(plan: Plan): Frag =
    div(cls := "flex items-center gap-3 text-sm")(
      a(href := "/plans", cls := "font-medium text-cyan-300 hover:text-cyan-200")("Plans"),
      span(cls := "text-slate-600")("→"),
      span(cls := "text-slate-400")(plan.summary),
    )

  private def header(plan: Plan): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
      div(cls := "flex flex-wrap items-start justify-between gap-4")(
        div(
          h1(cls := "text-2xl font-bold text-white")(plan.summary),
          p(cls := "mt-2 text-sm text-slate-300")(s"Version ${plan.version} · ${plan.id.value}"),
          p(cls := "mt-2 text-xs text-slate-500")(s"Updated ${timestamp(plan.updatedAt)}"),
        ),
        statusBadge(plan.status),
      )
    )

  private def rationalePanel(plan: Plan): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Decomposition rationale"),
      pre(cls := "mt-4 overflow-x-auto whitespace-pre-wrap text-sm leading-6 text-slate-100")(plan.rationale),
    )

  private def draftsPanel(plan: Plan, graph: PlanPreviewComponents.PlanGraph): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Task drafts"),
      p(
        cls := "mt-2 text-sm text-slate-400"
      )(s"${plan.drafts.count(_.included)} included draft(s) in the current version."),
      if graph.hasIssues then
        div(cls := "mt-4 space-y-4")(
          div(cls := "rounded-lg border border-white/10 bg-black/20 p-3 text-xs text-slate-200 overflow-x-auto")(
            div(cls := "planner-mermaid mermaid")(graph.mermaid)
          ),
          ul(cls := "space-y-3")(
            plan.drafts.map { draft =>
              li(cls := "rounded-lg border border-white/10 bg-black/20 px-3 py-3")(
                div(cls := "flex items-center justify-between gap-3")(
                  div(
                    p(cls := "font-medium text-white")(draft.title),
                    p(cls := "mt-1 text-xs text-slate-500")(draft.draftId),
                  ),
                  if draft.included then metaChip("Included") else metaChip("Excluded"),
                ),
                p(cls := "mt-2 text-sm text-slate-300")(draft.description),
              )
            }*
          ),
        )
      else
        p(cls := "mt-4 text-sm text-slate-400")("No included drafts are available for graph rendering.")
      ,
      PlanPreviewComponents.mermaidInitScript,
    )

  private def issuesPanel(linkedIssues: List[AgentIssue]): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Linked issues"),
      if linkedIssues.isEmpty then
        p(cls := "mt-3 text-sm text-slate-400")("No issues have been created from this plan yet.")
      else
        ul(cls := "mt-3 space-y-3")(
          linkedIssues.sortBy(_.title.toLowerCase).map { issue =>
            li(cls := "rounded-lg border border-white/10 bg-black/20 px-3 py-3")(
              a(href := s"/issues/${issue.id.value}", cls := "font-medium text-white hover:text-cyan-200")(issue.title),
              p(cls := "mt-1 text-xs text-slate-400")(issue.id.value),
              div(cls := "mt-2 flex flex-wrap gap-2 text-xs text-slate-300")(
                issue.workspaceId.map(id => metaChip(s"Workspace $id")).getOrElse(frag()),
                issue.externalRef.map(metaChip).getOrElse(frag()),
              ),
            )
          }*
        ),
    )

  private def traceabilityPanel(plan: Plan, specification: Option[Specification]): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Traceability"),
      dl(cls := "mt-4 space-y-3 text-sm")(
        traceRow("Conversation", s"planner:${plan.conversationId}"),
        traceRow("Workspace", plan.workspaceId.getOrElse("None")),
        traceRow("Specification", plan.specificationId.map(_.value).getOrElse("None")),
        traceRow("Created", timestamp(plan.createdAt)),
      ),
      specification.map { spec =>
        a(
          href := s"/specifications/${spec.id.value}",
          cls  := "mt-4 inline-flex rounded border border-cyan-400/30 bg-cyan-500/10 px-3 py-2 text-xs font-semibold text-cyan-200 hover:bg-cyan-500/20",
        )("Open linked specification")
      }.getOrElse(frag()),
    )

  private def validationPanel(plan: Plan): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Validation"),
      plan.validation match
        case Some(validation) =>
          div(cls := "mt-3 space-y-3")(
            validationBadge(validation.status),
            traceRow("Validated", timestamp(validation.validatedAt)),
            traceRow("Required gates", listOrNone(validation.requiredGates.map(_.toString))),
            traceRow("Missing gates", listOrNone(validation.missingGates.map(_.toString))),
            traceRow("Human approval", if validation.humanApprovalRequired then "Required" else "Not required"),
            validation.reason.map(reason => p(cls := "text-sm text-slate-300")(reason)).getOrElse(frag()),
          )
        case None             =>
          p(cls := "mt-3 text-sm text-slate-400")("This plan has not been validated yet."),
    )

  private def versionHistory(plan: Plan): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Version history"),
      ul(cls := "mt-3 space-y-3")(
        plan.versions.sortBy(_.version).reverse.map { version =>
          li(cls := "rounded-lg border border-white/10 bg-black/20 px-3 py-3 text-sm")(
            div(cls := "flex items-center justify-between gap-3")(
              div(
                span(cls := "font-medium text-white")(s"Version ${version.version}"),
                span(cls := "ml-2 text-slate-400")(version.status.toString),
              ),
              p(cls := "text-xs text-slate-500")(timestamp(version.changedAt)),
            ),
            p(cls := "mt-2 text-sm text-slate-300")(version.summary),
          )
        }*
      ),
    )

  private def traceRow(label: String, value: String): Frag =
    div(
      dt(cls := "text-xs uppercase tracking-wide text-slate-500")(label),
      dd(cls := "mt-1 text-sm text-slate-200")(value),
    )

  private def metaChip(value: String): Frag =
    span(cls := "rounded-full border border-white/15 bg-slate-800/60 px-3 py-1 text-xs text-slate-200")(value)

  private def statusBadge(status: PlanStatus): Frag =
    val toneCls = status match
      case PlanStatus.Draft     => "border-slate-400/30 bg-slate-500/10 text-slate-200"
      case PlanStatus.Validated => "border-cyan-400/30 bg-cyan-500/10 text-cyan-200"
      case PlanStatus.Executing => "border-amber-400/30 bg-amber-500/10 text-amber-200"
      case PlanStatus.Completed => "border-emerald-400/30 bg-emerald-500/10 text-emerald-200"
      case PlanStatus.Abandoned => "border-rose-400/30 bg-rose-500/10 text-rose-200"
    span(cls := s"rounded-full border px-3 py-1 text-xs font-semibold $toneCls")(status.toString)

  private def validationBadge(status: PlanValidationStatus): Frag =
    val toneCls = status match
      case PlanValidationStatus.Passed  => "border-emerald-400/30 bg-emerald-500/10 text-emerald-200"
      case PlanValidationStatus.Blocked => "border-rose-400/30 bg-rose-500/10 text-rose-200"
    span(cls := s"rounded-full border px-3 py-1 text-xs font-semibold $toneCls")(status.toString)

  private def listOrNone(values: List[String]): String =
    if values.isEmpty then "None" else values.mkString(", ")

  private def timestamp(value: Instant): String =
    value.toString
