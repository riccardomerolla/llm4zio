package web.views

import memory.{ MemoryEntry, ScoredMemory, UserId }
import scalatags.Text.all.*

object MemoryView:

  def page(
    userId: Option[UserId],
    entries: List[MemoryEntry],
    totalEntries: Int,
    oldest: Option[String],
    newest: Option[String],
    retentionDays: Int,
  ): String =
    Layout.page("Memory", "/memory")(
      div(cls := "space-y-6")(
        div(
          h1(cls := "text-2xl font-bold text-white")("Memory"),
          p(cls := "mt-2 text-sm text-gray-400")("Browse, search, and delete long-term memory entries."),
        ),
        searchPanel(userId),
        statsBar(totalEntries, oldest, newest, retentionDays),
        div(id := "memory-results", cls := "space-y-3")(raw(entriesFragment(entries, userId))),
      )
    )

  def entriesFragment(entries: List[MemoryEntry], userId: Option[UserId]): String =
    if entries.isEmpty then
      div(cls := "rounded-lg border border-white/10 bg-white/5 p-6 text-sm text-gray-300")("No memories found.").render
    else
      div(cls := "space-y-3")(
        entries.map(entry =>
          memoryCard(
            entry.id.value,
            entry.text,
            entry.kind.value,
            entry.createdAt.toString,
            None,
            entry.userId,
            showUserId = userId.isEmpty,
          )
        ).toSeq*
      ).render

  def searchFragment(results: List[ScoredMemory], userId: Option[UserId]): String =
    if results.isEmpty then
      div(
        cls := "rounded-lg border border-white/10 bg-white/5 p-6 text-sm text-gray-300"
      )("No matching memories.").render
    else
      div(cls := "space-y-3")(
        results.map { result =>
          memoryCard(
            result.entry.id.value,
            result.entry.text,
            result.entry.kind.value,
            result.entry.createdAt.toString,
            Some(f"${result.score}%.3f"),
            result.entry.userId,
            showUserId = userId.isEmpty,
          )
        }.toSeq*
      ).render

  private def searchPanel(userId: Option[UserId]): Frag =
    div(cls := "rounded-lg border border-white/10 bg-white/5 p-4")(
      div(cls := "grid gap-4 md:grid-cols-4", id := "memory-filters")(
        div(cls := "md:col-span-2")(
          label(cls := "mb-2 block text-sm font-medium text-gray-200", `for` := "memory-q")("Search"),
          input(
            id                   := "memory-q",
            name                 := "q",
            cls                  := "block w-full rounded-md border-0 bg-white/5 px-3 py-2 text-sm text-white ring-1 ring-inset ring-white/10 placeholder:text-gray-500 focus:ring-2 focus:ring-inset focus:ring-indigo-500",
            placeholder          := "Search memory...",
            attr("hx-get")       := "/api/memory/search",
            attr("hx-trigger")   := "input changed delay:300ms, search",
            attr("hx-target")    := "#memory-results",
            attr("hx-include")   := "#memory-filters",
            attr("hx-swap")      := "innerHTML",
            attr("hx-vals")      := "{\"format\":\"html\"}",
            attr("autocomplete") := "off",
          ),
        ),
        div(
          label(cls := "mb-2 block text-sm font-medium text-gray-200", `for` := "memory-userId")("User"),
          input(
            id                 := "memory-userId",
            name               := "userId",
            cls                := "block w-full rounded-md border-0 bg-white/5 px-3 py-2 text-sm text-white ring-1 ring-inset ring-white/10 placeholder:text-gray-500 focus:ring-2 focus:ring-inset focus:ring-indigo-500",
            placeholder        := "All users",
            value              := userId.map(_.value).getOrElse(""),
            attr("hx-get")     := "/api/memory/search",
            attr("hx-trigger") := "input changed delay:300ms, search",
            attr("hx-target")  := "#memory-results",
            attr("hx-include") := "#memory-filters",
            attr("hx-swap")    := "innerHTML",
            attr("hx-vals")    := "{\"format\":\"html\"}",
          ),
        ),
        div(
          label(cls := "mb-2 block text-sm font-medium text-gray-200", `for` := "memory-kind")("Kind"),
          select(
            id                 := "memory-kind",
            name               := "kind",
            cls                := "block w-full rounded-md border-0 bg-white/5 px-3 py-2 text-sm text-white ring-1 ring-inset ring-white/10 focus:ring-2 focus:ring-inset focus:ring-indigo-500",
            attr("hx-get")     := "/api/memory/search",
            attr("hx-trigger") := "change",
            attr("hx-target")  := "#memory-results",
            attr("hx-include") := "#memory-filters",
            attr("hx-swap")    := "innerHTML",
            attr("hx-vals")    := "{\"format\":\"html\"}",
          )(
            option(value := "")("All"),
            option(value := "Preference")("Preference"),
            option(value := "Fact")("Fact"),
            option(value := "Context")("Context"),
            option(value := "Summary")("Summary"),
          ),
        ),
        div(cls := "grid grid-cols-2 gap-2")(
          div(
            label(cls := "mb-2 block text-sm font-medium text-gray-200", `for` := "memory-from")("From"),
            input(
              id     := "memory-from",
              name   := "from",
              `type` := "date",
              cls    := "block w-full rounded-md border-0 bg-white/5 px-3 py-2 text-sm text-white ring-1 ring-inset ring-white/10",
            ),
          ),
          div(
            label(cls := "mb-2 block text-sm font-medium text-gray-200", `for` := "memory-to")("To"),
            input(
              id     := "memory-to",
              name   := "to",
              `type` := "date",
              cls    := "block w-full rounded-md border-0 bg-white/5 px-3 py-2 text-sm text-white ring-1 ring-inset ring-white/10",
            ),
          ),
        ),
      )
    )

  private def statsBar(
    totalEntries: Int,
    oldest: Option[String],
    newest: Option[String],
    retentionDays: Int,
  ): Frag =
    div(cls := "rounded-lg border border-white/10 bg-white/5 p-4")(
      div(cls := "grid gap-3 text-sm md:grid-cols-4")(
        statCell("Total", totalEntries.toString),
        statCell("Oldest", oldest.getOrElse("n/a")),
        statCell("Newest", newest.getOrElse("n/a")),
        statCell("Retention", s"${retentionDays}d"),
      )
    )

  private def statCell(labelText: String, valueText: String): Frag =
    div(
      p(cls := "text-xs uppercase tracking-wide text-gray-400")(labelText),
      p(cls := "mt-1 font-medium text-white")(valueText),
    )

  private def memoryCard(
    memoryId: String,
    text: String,
    kind: String,
    createdAt: String,
    score: Option[String],
    userId: UserId,
    showUserId: Boolean,
  ): Frag =
    div(scalatags.Text.all.id := s"memory-card-$memoryId", cls := "rounded-lg border border-white/10 bg-black/20 p-4")(
      div(cls := "mb-2 flex items-center justify-between gap-3")(
        div(cls := "flex items-center gap-2")(
          span(cls := "rounded-full bg-indigo-500/20 px-2 py-1 text-xs font-semibold text-indigo-300")(kind),
          score.map(value => span(cls := "text-xs text-gray-400")(s"score $value")),
          Option.when(showUserId)(span(cls := "rounded-full bg-white/10 px-2 py-1 text-xs text-gray-300")(userId.value)),
        ),
        button(
          cls               := "rounded-md bg-red-600/80 px-2 py-1 text-xs font-medium text-white hover:bg-red-500",
          attr("hx-delete") := s"/api/memory/$memoryId?userId=${userId.value}",
          attr("hx-target") := s"#memory-card-$memoryId",
          attr("hx-swap")   := "outerHTML",
        )("Delete"),
      ),
      p(cls := "text-sm text-gray-200 whitespace-pre-wrap")(text),
      p(cls := "mt-2 text-xs text-gray-500")(createdAt),
    )
