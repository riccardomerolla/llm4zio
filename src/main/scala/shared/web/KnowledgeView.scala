package shared.web

import knowledge.control.ArchitecturalContext
import knowledge.entity.DecisionLog
import memory.entity.MemoryEntry
import scalatags.Text.all.*

object KnowledgeView:

  def page(
    timeline: List[DecisionLog],
    context: ArchitecturalContext,
    browserEntries: List[MemoryEntry],
    query: Option[String],
    workspaceId: Option[String],
  ): String =
    Layout.page("Knowledge", "/knowledge")(
      div(cls := "space-y-6")(
        hero(query, workspaceId),
        filters(query, workspaceId),
        stats(context, timeline, browserEntries),
        decisionTimeline(timeline),
        rationaleBrowser(browserEntries),
        architecturalContextSection(context),
      )
    )

  private def hero(query: Option[String], workspaceId: Option[String]): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
      h1(cls := "text-2xl font-bold text-white")("Knowledge Base"),
      p(cls := "mt-1 text-sm text-slate-300")(
        "Browse decision logs, architectural rationale, constraints, and extracted system understanding."
      ),
      div(cls := "mt-3 flex flex-wrap gap-2 text-xs text-slate-300")(
        chip(s"Query ${query.getOrElse("recent")}"),
        workspaceId.map(id => chip(s"Workspace $id")).getOrElse(chip("All workspaces")),
      ),
    )

  private def filters(query: Option[String], workspaceId: Option[String]): Frag =
    form(
      action := "/knowledge",
      method := "get",
      cls    := "grid gap-3 rounded-xl border border-white/10 bg-slate-900/60 p-4 lg:grid-cols-[2fr,1fr,auto]",
    )(
      textField("q", "Search", query.getOrElse(""), "decision, rationale, constraint"),
      textField("workspaceId", "Workspace", workspaceId.getOrElse(""), "workspace id"),
      div(cls := "flex items-end justify-end")(
        button(
          `type` := "submit",
          cls    := "rounded bg-cyan-600 px-3 py-2 text-xs font-semibold text-white hover:bg-cyan-500",
        )("Search")
      ),
    )

  private def stats(
    context: ArchitecturalContext,
    timeline: List[DecisionLog],
    browserEntries: List[MemoryEntry],
  ): Frag =
    div(cls := "grid gap-3 md:grid-cols-4")(
      statCard("Decisions", timeline.size.toString),
      statCard("Linked knowledge", browserEntries.size.toString),
      statCard("Architecture docs", context.analysisDocs.size.toString),
      statCard("Graph edges", context.edges.size.toString),
    )

  private def decisionTimeline(timeline: List[DecisionLog]): Frag =
    sectionCard(
      "Decision Timeline",
      if timeline.isEmpty then
        emptyState("No decision logs match the current filters.")
      else
        div(cls := "space-y-4")(
          timeline.map(log =>
            div(cls := "rounded-xl border border-white/10 bg-black/20 p-4")(
              div(cls := "flex flex-wrap items-start justify-between gap-4")(
                div(
                  h3(cls := "text-lg font-semibold text-white")(log.title),
                  p(cls := "mt-1 text-sm text-slate-300")(log.context),
                ),
                chip(log.decisionDate.toString),
              ),
              p(cls := "mt-3 text-sm text-slate-100")(log.decisionTaken),
              p(cls := "mt-2 text-sm text-slate-300")(log.rationale),
              div(cls := "mt-3 flex flex-wrap gap-2 text-xs text-slate-300")(
                chip(s"Decision maker ${log.decisionMaker.name}"),
                log.workspaceId.map(id => chip(s"Workspace $id")).getOrElse(frag()),
                log.issueIds.map(id => chip(s"Issue ${id.value}")),
                log.specificationIds.map(id => chip(s"Spec ${id.value}")),
                log.planIds.map(id => chip(s"Plan ${id.value}")),
              ),
            )
          )*
        ),
    )

  private def rationaleBrowser(entries: List[MemoryEntry]): Frag =
    sectionCard(
      "Rationale Browser",
      if entries.isEmpty then
        emptyState("No rationale or knowledge entries match the current filters.")
      else
        div(cls := "grid gap-4 lg:grid-cols-2")(
          entries.map(entry =>
            div(cls := "rounded-xl border border-white/10 bg-black/20 p-4")(
              div(cls := "flex items-center justify-between gap-3")(
                span(cls := "rounded-full border border-cyan-400/30 bg-cyan-500/10 px-3 py-1 text-xs text-cyan-200")(
                  entry.kind.value
                ),
                span(cls := "text-xs text-slate-500")(entry.createdAt.toString),
              ),
              p(cls := "mt-3 text-sm text-slate-200 whitespace-pre-wrap")(entry.text),
              div(cls := "mt-3 flex flex-wrap gap-2 text-xs text-slate-400")(
                entry.tags.take(6).map(chip)
              ),
            )
          )*
        ),
    )

  private def architecturalContextSection(context: ArchitecturalContext): Frag =
    sectionCard(
      "Architectural Context",
      if context.decisions.isEmpty && context.analysisDocs.isEmpty then
        emptyState("No architectural context found.")
      else
        div(cls := "space-y-4")(
          if context.decisions.nonEmpty then
            div(cls := "rounded-xl border border-white/10 bg-black/20 p-4")(
              h3(cls := "text-sm font-semibold uppercase tracking-wide text-slate-400")("Matched decisions"),
              ul(cls := "mt-3 space-y-2 text-sm text-slate-200")(
                context.decisions.map(matchResult =>
                  li(
                    span(cls := "font-medium text-white")(matchResult.decision.title),
                    span(cls := "ml-2 text-xs text-slate-500")(f"score ${matchResult.score}%.2f"),
                  )
                )*
              ),
            )
          else frag(),
          if context.analysisDocs.nonEmpty then
            div(cls := "rounded-xl border border-white/10 bg-black/20 p-4")(
              h3(cls := "text-sm font-semibold uppercase tracking-wide text-slate-400")("Architecture docs"),
              div(cls := "mt-3 space-y-3")(
                context.analysisDocs.map(doc =>
                  div(
                    h4(cls := "font-medium text-white")(doc.filePath),
                    p(cls := "mt-1 text-sm text-slate-300 whitespace-pre-wrap")(doc.content.take(500)),
                  )
                )*
              ),
            )
          else frag(),
          if context.edges.nonEmpty then
            div(cls := "rounded-xl border border-white/10 bg-black/20 p-4")(
              h3(cls := "text-sm font-semibold uppercase tracking-wide text-slate-400")("Graph edges"),
              ul(cls := "mt-3 space-y-2 text-sm text-slate-200")(
                context.edges.take(10).map(edge =>
                  li(s"${edge.fromId} -> ${edge.toId} (${edge.relation}, ${"%.2f".format(edge.score)})")
                )*
              ),
            )
          else frag(),
        ),
    )

  private def sectionCard(titleText: String, content: Frag): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      h2(cls := "text-lg font-semibold text-white")(titleText),
      div(cls := "mt-4")(content),
    )

  private def textField(fieldName: String, labelText: String, valueText: String, placeholderText: String): Frag =
    div(cls := "space-y-2")(
      label(cls := "text-xs font-semibold uppercase tracking-wide text-slate-400", `for` := fieldName)(labelText),
      input(
        id           := fieldName,
        attr("name") := fieldName,
        value        := valueText,
        placeholder  := placeholderText,
        cls          := "w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-sm text-slate-100",
      ),
    )

  private def statCard(labelText: String, valueText: String): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-4")(
      p(cls := "text-xs font-semibold uppercase tracking-wide text-slate-400")(labelText),
      p(cls := "mt-2 text-2xl font-semibold text-white")(valueText),
    )

  private def emptyState(message: String): Frag =
    div(
      cls := "rounded-xl border border-dashed border-white/10 bg-black/20 p-8 text-center text-sm text-slate-300"
    )(message)

  private def chip(value: String): Frag =
    span(cls := "rounded-full border border-white/15 bg-slate-800/60 px-3 py-1 text-xs text-slate-200")(value)
