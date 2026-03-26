package shared.web

import java.time.Instant

import issues.entity.AgentIssue
import scalatags.Text.all.*
import specification.entity.{ Specification, SpecificationDiff, SpecificationStatus }

final case class SpecificationListItem(
  id: String,
  title: String,
  status: SpecificationStatus,
  version: Int,
  linkedPlanRef: Option[String],
  linkedIssueCount: Int,
  updatedAt: Instant,
)

object SpecificationsView:

  def page(items: List[SpecificationListItem]): String =
    Layout.page("Specifications", "/specifications")(
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          h1(cls := "text-2xl font-bold text-white")("Specifications"),
          p(cls := "mt-1 text-sm text-slate-300")(
            "Review planner-generated specifications, trace linked issues, and compare revisions before approval."
          ),
        ),
        if items.isEmpty then
          div(cls := "rounded-xl border border-dashed border-white/10 bg-slate-900/60 p-10 text-center")(
            p(cls := "text-slate-300")("No specifications available yet."),
            p(cls := "mt-1 text-sm text-slate-500")(
              "Planner sessions will create specifications automatically when previews are generated."
            ),
          )
        else
          div(cls := "grid gap-4 lg:grid-cols-2")(
            items.sortBy(_.updatedAt).reverse.map(specCard)*
          ),
      )
    )

  def detailPage(
    specification: Specification,
    linkedIssues: List[AgentIssue],
    diff: Option[SpecificationDiff],
  ): String =
    Layout.page(specification.title, s"/specifications/${specification.id.value}")(
      div(cls := "space-y-6")(
        breadcrumb(specification),
        header(specification),
        detailGrid(specification, linkedIssues),
        revisionEditor(specification),
        diffSection(specification, diff),
      )
    )

  def diffPage(
    specification: Specification,
    diff: SpecificationDiff,
  ): String =
    Layout.page(s"${specification.title} Diff", s"/specifications/${specification.id.value}/diff")(
      div(cls := "space-y-6")(
        breadcrumb(specification),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          h1(cls := "text-2xl font-bold text-white")(specification.title),
          p(cls := "mt-1 text-sm text-slate-300")(s"Version ${diff.fromVersion} → ${diff.toVersion}"),
        ),
        diffPanels(diff),
      )
    )

  private def specCard(item: SpecificationListItem): Frag =
    a(
      href := s"/specifications/${item.id}",
      cls  := "rounded-xl border border-white/10 bg-slate-900/60 p-5 transition hover:border-cyan-400/40 hover:bg-slate-900/80",
    )(
      div(cls := "flex items-start justify-between gap-3")(
        div(
          h2(cls := "text-lg font-semibold text-white")(item.title),
          p(cls := "mt-1 text-xs uppercase tracking-wide text-slate-500")(item.id),
        ),
        statusBadge(item.status),
      ),
      div(cls := "mt-4 flex flex-wrap gap-2 text-xs text-slate-300")(
        metaChip(s"Version ${item.version}"),
        metaChip(s"${item.linkedIssueCount} linked issue(s)"),
        item.linkedPlanRef.map(metaChip).getOrElse(metaChip("No linked plan")),
      ),
      p(cls := "mt-3 text-xs text-slate-500")(s"Updated ${timestamp(item.updatedAt)}"),
    )

  private def breadcrumb(specification: Specification): Frag =
    div(cls := "flex items-center gap-3 text-sm")(
      a(href := "/specifications", cls := "font-medium text-cyan-300 hover:text-cyan-200")("Specifications"),
      span(cls := "text-slate-600")("→"),
      span(cls := "text-slate-400")(specification.title),
    )

  private def header(specification: Specification): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
      div(cls := "flex flex-wrap items-start justify-between gap-4")(
        div(
          h1(cls := "text-2xl font-bold text-white")(specification.title),
          p(cls := "mt-2 text-sm text-slate-300")(s"Version ${specification.version} · ${specification.id.value}"),
          p(cls := "mt-2 text-xs text-slate-500")(s"Updated ${timestamp(specification.updatedAt)}"),
        ),
        div(cls := "flex flex-wrap items-center gap-2")(
          statusBadge(specification.status),
          approvalForm(specification),
        ),
      )
    )

  private def detailGrid(specification: Specification, linkedIssues: List[AgentIssue]): Frag =
    div(cls := "grid gap-6 xl:grid-cols-[minmax(0,2fr),minmax(22rem,1fr)]")(
      div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
        h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Current specification"),
        pre(cls := "mt-4 overflow-x-auto whitespace-pre-wrap text-sm leading-6 text-slate-100")(specification.content),
      ),
      div(cls := "space-y-6")(
        summaryPanel(specification),
        linkedIssuesPanel(linkedIssues),
        revisionHistory(specification),
      ),
    )

  private def summaryPanel(specification: Specification): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Traceability"),
      dl(cls := "mt-4 space-y-3 text-sm")(
        traceRow("Plan", specification.linkedPlanRef.fold("None")(identity)),
        traceRow("Author", specification.author.displayName),
        traceRow("Comments", specification.reviewComments.size.toString),
        traceRow("Created", timestamp(specification.createdAt)),
      ),
      specification.linkedPlanRef.flatMap(planHref).map { planUrl =>
        a(
          href := planUrl,
          cls  := "mt-4 inline-flex rounded border border-cyan-400/30 bg-cyan-500/10 px-3 py-2 text-xs font-semibold text-cyan-200 hover:bg-cyan-500/20",
        )("Open planner conversation")
      }.getOrElse(frag()),
    )

  private def linkedIssuesPanel(linkedIssues: List[AgentIssue]): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Linked issues"),
      if linkedIssues.isEmpty then
        p(cls := "mt-3 text-sm text-slate-400")("No issues linked yet.")
      else
        ul(cls := "mt-3 space-y-3")(
          linkedIssues.sortBy(_.title.toLowerCase).map { issue =>
            li(cls := "rounded-lg border border-white/10 bg-black/20 px-3 py-3")(
              a(href := s"/issues/${issue.id.value}", cls := "font-medium text-white hover:text-cyan-200")(issue.title),
              p(cls := "mt-1 text-xs text-slate-400")(issue.id.value),
              div(cls := "mt-2 flex flex-wrap gap-2 text-xs text-slate-300")(
                issue.workspaceId.map(metaChip).getOrElse(frag()),
                issue.externalRef.map(metaChip).getOrElse(frag()),
              ),
            )
          }*
        ),
    )

  private def revisionHistory(specification: Specification): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Version history"),
      ul(cls := "mt-3 space-y-3")(
        specification.revisions.sortBy(_.version).reverse.map { revision =>
          val compareHref =
            if revision.version < specification.version then
              s"/specifications/${specification.id.value}/diff?from=${revision.version}&to=${specification.version}"
            else s"/specifications/${specification.id.value}"
          li(cls := "rounded-lg border border-white/10 bg-black/20 px-3 py-3 text-sm")(
            div(cls := "flex items-center justify-between gap-3")(
              div(
                span(cls := "font-medium text-white")(s"Version ${revision.version}"),
                span(cls := "ml-2 text-slate-400")(revision.status.toString),
              ),
              a(href := compareHref, cls := "text-xs font-semibold text-cyan-300 hover:text-cyan-200")("Compare"),
            ),
            p(
              cls := "mt-1 text-xs text-slate-500"
            )(s"${revision.author.displayName} · ${timestamp(revision.changedAt)}"),
          )
        }*
      ),
    )

  private def revisionEditor(specification: Specification): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Revise specification"),
      form(action := s"/specifications/${specification.id.value}/revise", method := "post", cls := "mt-4 space-y-4")(
        input(
          name     := "title",
          value    := specification.title,
          required := "required",
          cls      := "w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-sm text-slate-100",
        ),
        textarea(
          name     := "content",
          rows     := 18,
          required := "required",
          cls      := "w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-sm text-slate-100",
        )(specification.content),
        div(cls := "flex justify-end")(
          button(
            `type` := "submit",
            cls    := "rounded bg-cyan-600 px-3 py-2 text-sm font-semibold text-white hover:bg-cyan-500",
          )("Save revision")
        ),
      ),
    )

  private def diffSection(specification: Specification, diff: Option[SpecificationDiff]): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Version diff"),
      diff match
        case Some(value) =>
          div(
            div(cls := "mt-2 text-sm text-slate-400")(s"Comparing version ${value.fromVersion} to ${value.toVersion}."),
            div(cls := "mt-3")(
              a(
                href := s"/specifications/${specification.id.value}/diff?from=${value.fromVersion}&to=${value.toVersion}",
                cls  := "text-xs font-semibold text-cyan-300 hover:text-cyan-200",
              )("Open full diff view")
            ),
            div(cls := "mt-4")(diffPanels(value)),
          )
        case None        =>
          p(cls := "mt-3 text-sm text-slate-400")(
            "Create at least two revisions to compare changes across versions."
          ),
    )

  private def diffPanels(diff: SpecificationDiff): Frag =
    div(cls := "grid gap-4 xl:grid-cols-2")(
      div(cls := "rounded-xl border border-rose-400/20 bg-rose-500/5 p-4")(
        h2(cls := "text-sm font-semibold text-rose-200")(s"Version ${diff.fromVersion}"),
        pre(cls := "mt-3 overflow-x-auto whitespace-pre-wrap text-sm leading-6 text-slate-100")(diff.beforeContent),
      ),
      div(cls := "rounded-xl border border-emerald-400/20 bg-emerald-500/5 p-4")(
        h2(cls := "text-sm font-semibold text-emerald-200")(s"Version ${diff.toVersion}"),
        pre(cls := "mt-3 overflow-x-auto whitespace-pre-wrap text-sm leading-6 text-slate-100")(diff.afterContent),
      ),
    )

  private def approvalForm(specification: Specification): Frag =
    if specification.status == SpecificationStatus.Approved then frag()
    else
      form(action := s"/specifications/${specification.id.value}/approve", method := "post")(
        button(
          `type` := "submit",
          cls    := "rounded border border-emerald-400/30 bg-emerald-500/10 px-3 py-2 text-xs font-semibold text-emerald-200 hover:bg-emerald-500/20",
        )("Approve")
      )

  private def traceRow(label: String, value: String): Frag =
    div(
      dt(cls := "text-xs uppercase tracking-wide text-slate-500")(label),
      dd(cls := "mt-1 text-sm text-slate-200")(value),
    )

  private def metaChip(value: String): Frag =
    span(cls := "rounded-full border border-white/15 bg-slate-800/60 px-3 py-1 text-xs text-slate-200")(value)

  private def statusBadge(status: SpecificationStatus): Frag =
    val toneCls = status match
      case SpecificationStatus.Draft        => "border-slate-400/30 bg-slate-500/10 text-slate-200"
      case SpecificationStatus.InRefinement => "border-amber-400/30 bg-amber-500/10 text-amber-200"
      case SpecificationStatus.Approved     => "border-emerald-400/30 bg-emerald-500/10 text-emerald-200"
      case SpecificationStatus.Superseded   => "border-rose-400/30 bg-rose-500/10 text-rose-200"
    span(cls := s"rounded-full border px-3 py-1 text-xs font-semibold $toneCls")(status.toString)

  private def planHref(planRef: String): Option[String] =
    Option(planRef)
      .map(_.trim)
      .filter(_.startsWith("planner:"))
      .flatMap(_.stripPrefix("planner:").toLongOption)
      .map(id => s"/chat/$id")

  private def timestamp(value: Instant): String =
    value.toString
