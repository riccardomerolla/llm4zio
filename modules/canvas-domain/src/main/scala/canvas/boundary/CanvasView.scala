package canvas.boundary

import scalatags.Text.all.*

import _root_.canvas.entity.*
import shared.web.Layout

object CanvasView:

  def listPage(
    canvases: List[ReasonsCanvas],
    selectedProject: Option[String],
    projects: List[String],
  ): String =
    Layout.page("Canvases", "/canvases")(
      div(cls := "space-y-6")(
        listHeader(selectedProject, projects),
        listStats(canvases),
        canvasGrid(canvases),
      )
    )

  def detailPage(canvas: ReasonsCanvas): String =
    Layout.page(s"Canvas — ${canvas.title}", "/canvases")(
      div(cls := "space-y-6")(
        detailHeader(canvas),
        sectionsGrid(canvas.sections),
        revisionTimeline(canvas.revisions),
        traceabilityFooter(canvas),
      )
    )

  // ── List page ─────────────────────────────────────────────────────────

  private def listHeader(selectedProject: Option[String], projects: List[String]): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-3")(
      form(
        action := "/canvases",
        method := "get",
        cls    := "flex flex-wrap items-center gap-3",
      )(
        h1(cls := "shrink-0 text-lg font-bold text-white mr-2")("REASONS Canvases"),
        select(
          id           := "projectId",
          attr("name") := "projectId",
          cls          := "shrink-0 rounded border border-white/10 bg-black/20 px-3 py-1.5 text-sm text-slate-100",
        )(
          option(value := "", if selectedProject.isEmpty then attr("selected") := "selected" else ())("All projects"),
          frag(
            projects.map { pid =>
              option(
                attr("value") := pid,
                if selectedProject.contains(pid) then attr("selected") := "selected" else (),
              )(pid)
            }
          ),
        ),
        button(
          `type` := "submit",
          cls    := "shrink-0 rounded bg-cyan-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-cyan-500",
        )("Filter"),
      )
    )

  private def listStats(canvases: List[ReasonsCanvas]): Frag =
    val byStatus = canvases.groupMapReduce(_.status)(_ => 1)(_ + _)
    div(cls := "grid gap-3 md:grid-cols-5")(
      statCard("Total", canvases.size.toString),
      statCard("Approved", byStatus.getOrElse(CanvasStatus.Approved, 0).toString),
      statCard("InReview", byStatus.getOrElse(CanvasStatus.InReview, 0).toString),
      statCard("Draft", byStatus.getOrElse(CanvasStatus.Draft, 0).toString),
      statCard("Stale", byStatus.getOrElse(CanvasStatus.Stale, 0).toString),
    )

  private def canvasGrid(canvases: List[ReasonsCanvas]): Frag =
    if canvases.isEmpty then
      emptyState("No canvases match the current filter. Run /spdd-reasons-canvas to create one.")
    else
      div(cls := "grid gap-4 md:grid-cols-2 lg:grid-cols-3")(
        canvases.map(canvasCard)
      )

  private def canvasCard(canvas: ReasonsCanvas): Frag =
    a(
      href := s"/canvases/${canvas.id.value}",
      cls  := "block rounded-xl border border-white/10 bg-black/20 p-4 hover:border-cyan-500/40 hover:bg-black/30",
    )(
      div(cls := "flex items-start justify-between gap-3 mb-2")(
        h3(cls := "text-sm font-semibold text-white truncate")(canvas.title),
        statusBadge(canvas.status),
      ),
      p(cls := "text-xs text-slate-400 mb-2")(s"v${canvas.version} · ${canvas.projectId.value}"),
      p(cls := "text-xs text-slate-300 line-clamp-3")(
        truncate(canvas.sections.requirements.content, 160)
      ),
      div(cls := "mt-3 flex items-center gap-2 flex-wrap")(
        chip(s"${canvas.linkedTaskRunIds.size} runs"),
        canvas.storyIssueId.map(id => chip(s"story:${id.value}")).getOrElse(frag()),
      ),
    )

  // ── Detail page ───────────────────────────────────────────────────────

  private def detailHeader(canvas: ReasonsCanvas): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
      div(cls := "flex items-start justify-between gap-4")(
        div(
          h1(cls := "text-2xl font-bold text-white")(canvas.title),
          p(cls := "mt-1 text-xs text-slate-400")(
            s"${canvas.id.value} · v${canvas.version} · project ${canvas.projectId.value}"
          ),
        ),
        statusBadge(canvas.status),
      ),
      div(cls := "mt-3 flex flex-wrap gap-2 text-xs text-slate-300")(
        canvas.storyIssueId.map(id => chip(s"story: ${id.value}")).getOrElse(frag()),
        canvas.analysisId.map(id => chip(s"analysis: ${id.value}")).getOrElse(frag()),
        canvas.specificationId.map(id => chip(s"spec: ${id.value}")).getOrElse(frag()),
        canvas.normProfileId.map(id => chip(s"norms: ${id.value}")).getOrElse(frag()),
        canvas.safeguardProfileId.map(id => chip(s"safeguards: ${id.value}")).getOrElse(frag()),
      ),
    )

  private def sectionsGrid(sections: ReasonsSections): Frag =
    div(cls := "grid gap-4 md:grid-cols-2")(
      sectionCard("R — Requirements", sections.requirements),
      sectionCard("E — Entities", sections.entities),
      sectionCard("A — Approach", sections.approach),
      sectionCard("S — Structure", sections.structure),
      div(cls := "md:col-span-2")(sectionCard("O — Operations", sections.operations)),
      sectionCard("N — Norms", sections.norms),
      sectionCard("S — Safeguards", sections.safeguards),
    )

  private def sectionCard(title: String, section: CanvasSection): Frag =
    div(cls := "rounded-xl border border-white/10 bg-black/20 p-4")(
      div(cls := "flex items-baseline justify-between gap-3 mb-2")(
        h3(cls := "text-sm font-semibold text-cyan-400 uppercase tracking-wide")(title),
        span(cls := "text-[10px] text-slate-500")(
          s"by ${section.lastUpdatedBy.displayName} at ${section.lastUpdatedAt.toString}"
        ),
      ),
      pre(cls := "whitespace-pre-wrap text-xs text-slate-200 font-mono break-words")(section.content),
    )

  private def revisionTimeline(revisions: List[CanvasRevision]): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
      h2(cls := "text-sm font-semibold text-white mb-3")("Revision history"),
      if revisions.isEmpty then emptyState("No revisions yet.")
      else
        ul(cls := "space-y-2")(
          revisions.map { rev =>
            li(cls := "flex items-start gap-3 text-xs text-slate-300")(
              span(cls := "rounded bg-slate-800 px-2 py-0.5 text-[10px] text-slate-400 font-mono")(s"v${rev.version}"),
              span(cls := "text-slate-500")(rev.changedAt.toString),
              span(cls := "text-slate-300")(s"${rev.author.displayName}"),
              span(cls := "text-slate-200")(
                if rev.changedSections.isEmpty then "(no sections changed)"
                else s"changed: ${rev.changedSections.map(_.toString).mkString(", ")}"
              ),
              rev.staleReason.map(reason => span(cls := "text-amber-400")(s"stale: $reason")).getOrElse(frag()),
            )
          }
        ),
    )

  private def traceabilityFooter(canvas: ReasonsCanvas): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
      h2(cls := "text-sm font-semibold text-white mb-2")("Linked TaskRuns"),
      if canvas.linkedTaskRunIds.isEmpty then emptyState("No TaskRuns linked. Run /spdd-generate to produce one.")
      else
        ul(cls := "flex flex-wrap gap-2")(
          canvas.linkedTaskRunIds.map(id =>
            li(cls := "rounded bg-slate-800 px-2 py-1 text-xs font-mono text-slate-200")(id.value)
          )
        ),
    )

  // ── Helpers ──────────────────────────────────────────────────────────

  private def statCard(label: String, value: String): Frag =
    div(cls := "rounded-xl border border-white/10 bg-black/20 px-4 py-3")(
      div(cls := "text-[10px] uppercase tracking-wide text-slate-400")(label),
      div(cls := "mt-1 text-2xl font-bold text-white")(value),
    )

  private def statusBadge(status: CanvasStatus): Frag =
    val (label, classes) = status match
      case CanvasStatus.Draft      => ("Draft", "bg-slate-700 text-slate-200")
      case CanvasStatus.InReview   => ("InReview", "bg-amber-700 text-amber-100")
      case CanvasStatus.Approved   => ("Approved", "bg-emerald-700 text-emerald-100")
      case CanvasStatus.Stale      => ("Stale", "bg-rose-700 text-rose-100")
      case CanvasStatus.Superseded => ("Superseded", "bg-slate-800 text-slate-400")
    span(cls := s"shrink-0 rounded px-2 py-0.5 text-[10px] uppercase tracking-wide font-semibold $classes")(label)

  private def chip(text: String): Frag =
    span(cls := "rounded bg-slate-800 px-2 py-0.5 text-[10px] text-slate-300 font-mono")(text)

  private def emptyState(text: String): Frag =
    div(cls := "rounded border border-dashed border-white/10 bg-black/10 px-4 py-6 text-center text-xs text-slate-400")(text)

  private def truncate(text: String, max: Int): String =
    if text.length <= max then text else text.take(max).reverse.dropWhile(_ != ' ').reverse.trim + "…"
