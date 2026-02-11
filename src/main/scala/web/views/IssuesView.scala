package web.views

import models.*
import scalatags.Text.all.*

object IssuesView:

  def list(
    runId: Option[Long],
    issues: List[AgentIssue],
    statusFilter: Option[String],
    query: Option[String],
    tagFilter: Option[String],
  ): String =
    val pageTitle = runId match
      case Some(id) => s"Issues for Run #$id"
      case None     => "Issues"

    val openCount = issues.count(_.status == IssueStatus.Open)

    Layout.page("Issues", "/issues")(
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          div(cls := "flex flex-wrap items-center justify-between gap-3")(
            div(
              h1(cls := "text-2xl font-bold text-white")(pageTitle),
              p(cls := "mt-1 text-sm text-slate-300")("Track and assign AI execution tasks"),
            ),
            div(cls := "flex items-center gap-2")(
              a(
                href := "/issues/new",
                cls  := "rounded-md border border-emerald-400/30 bg-emerald-500/20 px-3 py-2 text-sm font-semibold text-emerald-200 hover:bg-emerald-500/30",
              )("New issue"),
              form(method := "post", action := "/issues/import")(
                button(
                  `type` := "submit",
                  cls    := "rounded-md border border-indigo-400/30 bg-indigo-500/20 px-3 py-2 text-sm font-semibold text-indigo-200 hover:bg-indigo-500/30",
                )("Import markdown")
              ),
            ),
          ),
          div(cls := "mt-3 flex items-center gap-2 text-sm")(
            span(cls := "rounded-full border border-emerald-400/30 bg-emerald-500/10 px-3 py-1 text-emerald-200")(
              s"$openCount open"
            ),
            span(cls := "text-slate-400")(s"${issues.size} total"),
          ),
        ),
        filterBar(runId, statusFilter, query, tagFilter),
        if issues.isEmpty then
          div(cls := "rounded-xl border border-white/10 bg-slate-900/60 px-6 py-16 text-center")(
            p(cls := "text-base text-slate-300")("No matching issues found")
          )
        else
          div(cls := "overflow-hidden rounded-xl border border-white/10 bg-slate-900/60")(
            issues.sortBy(_.updatedAt).reverse.map(issueRow)
          ),
      )
    )

  def newForm(defaultRunId: Option[Long]): String =
    Layout.page("New Issue", "/issues")(
      div(cls := "-mt-6 mx-auto max-w-4xl")(
        div(cls := "mb-5")(
          h1(cls := "text-2xl font-bold text-white")("Create issue"),
          p(cls := "mt-1 text-sm text-slate-300")("Write a markdown task and optional execution metadata"),
        ),
        form(method := "post", action := "/issues", cls := "space-y-5")(
          div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5")(
            div(cls := "grid grid-cols-1 gap-4 md:grid-cols-2")(
              textField("title", "Title", "Describe the task", required = true),
              textField("issueType", "Type", "task", required = true),
              textField("runId", "Run ID (optional)", defaultRunId.map(_.toString).getOrElse("")),
              textField("priority", "Priority", "medium"),
              textField("tags", "Tags (comma separated)", "bug,build,analysis"),
              textField("preferredAgent", "Preferred AI Agent", "gemini-cli"),
              textField("contextPath", "Context Path", "/path/to/context"),
              textField("sourceFolder", "Source Folder", "/path/to/source"),
            ),
            div(cls := "mt-4")(
              label(cls := "mb-2 block text-sm font-semibold text-slate-200", `for` := "description")("Markdown task"),
              textarea(
                id          := "description",
                name        := "description",
                rows        := 14,
                cls         := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
                placeholder := "# Task\nExplain what the agent should do, acceptance criteria, and constraints.",
                required,
              ),
            ),
          ),
          div(cls := "flex items-center gap-3")(
            button(
              `type` := "submit",
              cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
            )("Create issue"),
            a(href := "/issues", cls := "text-sm font-medium text-slate-300 hover:text-white")("Cancel"),
          ),
        ),
      )
    )

  def detail(issue: AgentIssue, assignments: List[AgentAssignment], availableAgents: List[AgentInfo]): String =
    val selectedAgent = issue.preferredAgent.orElse(issue.assignedAgent).getOrElse("")
    Layout.page(s"Issue #${issue.id.getOrElse(0L)}", "/issues")(
      div(cls := "-mt-6 mx-auto max-w-5xl space-y-4")(
        a(href := "/issues", cls := "text-sm font-medium text-indigo-300 hover:text-indigo-200")("← Back to issues"),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-6")(
          div(cls := "flex items-start justify-between gap-4")(
            div(
              h1(cls := "text-2xl font-bold text-white")(issue.title),
              div(cls := "mt-2 flex flex-wrap items-center gap-2")(
                statusBadge(issue.status.toString),
                priorityBadge(issue.priority.toString),
                issue.tags.toList.flatMap(splitTags).map(tagBadge),
              ),
            ),
            div(cls := "flex flex-col items-end gap-2")(
              if issue.conversationId.isDefined then
                a(
                  href := s"/chat/${issue.conversationId.get}",
                  cls  := "rounded-md border border-indigo-400/30 bg-indigo-500/20 px-3 py-2 text-sm font-semibold text-indigo-200 hover:bg-indigo-500/30",
                )("Open linked chat")
              else (),
              form(
                method   := "post",
                action   := s"/issues/${issue.id.getOrElse(0L)}/assign",
                cls      := "flex items-center gap-2",
                onsubmit := "const b=this.querySelector('button[type=submit]'); if(b){b.disabled=true;b.classList.add('opacity-60','cursor-not-allowed'); b.dataset.originalText=b.textContent; b.textContent='Assigning...';}",
              )(
                agentSelect("agentName", selectedAgent, availableAgents),
                button(
                  `type` := "submit",
                  cls    := "rounded-md border border-emerald-400/30 bg-emerald-500/20 px-3 py-2 text-sm font-semibold text-emerald-200 hover:bg-emerald-500/30",
                )(
                  if issue.conversationId.isDefined then "Re-assign & Run" else "Assign & Start Chat"
                ),
              ),
            ),
          ),
          div(cls := "mt-5 grid grid-cols-1 gap-4 md:grid-cols-3")(
            metaItem("Run", issue.runId.map(_.toString).getOrElse("Not linked")),
            metaItem("Preferred Agent", issue.preferredAgent.getOrElse("Not specified")),
            metaItem("Assigned Agent", issue.assignedAgent.getOrElse("Unassigned")),
            metaItem("Context Path", issue.contextPath.getOrElse("Not specified")),
            metaItem("Source Folder", issue.sourceFolder.getOrElse("Not specified")),
            metaItem("Updated", issue.updatedAt.toString.take(19).replace('T', ' ')),
          ),
          div(cls := "mt-6 rounded-lg border border-white/10 bg-slate-950/70 p-4")(
            p(cls := "mb-2 text-sm font-semibold text-slate-300")("Task markdown"),
            div(cls := "prose prose-invert prose-sm max-w-none text-slate-100")(
              markdownFragment(issue.description)
            ),
          ),
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-6")(
          h2(cls := "mb-3 text-lg font-semibold text-white")("Execution history"),
          if assignments.isEmpty then p(cls := "text-sm text-slate-400")("No assignments yet")
          else
            div(cls := "space-y-3")(
              assignments.sortBy(_.assignedAt).reverse.map { assignment =>
                div(cls := "rounded-lg border border-white/10 bg-slate-800/70 p-4")(
                  div(cls := "flex flex-wrap items-center justify-between gap-2")(
                    span(cls := "text-sm font-semibold text-slate-100")(assignment.agentName),
                    span(cls := s"rounded-full px-2 py-0.5 text-xs ${assignmentStatusBadge(assignment.status)}")(
                      assignment.status
                    ),
                  ),
                  assignment.executionLog.filter(_.nonEmpty).map { value =>
                    pre(cls := "mt-3 max-h-48 overflow-auto rounded bg-black/30 p-3 text-xs text-slate-200")(value)
                  },
                  assignment.result.filter(_.nonEmpty).map { value =>
                    pre(cls := "mt-3 max-h-48 overflow-auto rounded bg-black/30 p-3 text-xs text-slate-200")(value)
                  },
                )
              }
            ),
        ),
      )
    )

  private def filterBar(
    runId: Option[Long],
    statusFilter: Option[String],
    query: Option[String],
    tagFilter: Option[String],
  ): Frag =
    form(method := "get", action := "/issues", cls := "rounded-xl border border-white/10 bg-slate-900/60 p-4")(
      runId.map(id => input(`type` := "hidden", name := "run_id", value := id.toString)),
      div(cls := "grid grid-cols-1 gap-3 md:grid-cols-4")(
        input(
          `type`      := "text",
          name        := "q",
          value       := query.getOrElse(""),
          placeholder := "Search title or markdown",
          cls         := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500",
        ),
        input(
          `type`      := "text",
          name        := "tag",
          value       := tagFilter.getOrElse(""),
          placeholder := "Filter by tag",
          cls         := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500",
        ),
        select(
          name := "status",
          cls  := "rounded-md border border-white/15 bg-slate-800/70 px-3 py-2 text-sm text-slate-100",
        )(
          statusOption("", "Any status", statusFilter),
          statusOption("open", "Open", statusFilter),
          statusOption("assigned", "Assigned", statusFilter),
          statusOption("in_progress", "In progress", statusFilter),
          statusOption("completed", "Completed", statusFilter),
          statusOption("failed", "Failed", statusFilter),
          statusOption("skipped", "Skipped", statusFilter),
        ),
        div(cls := "flex gap-2")(
          button(
            `type` := "submit",
            cls    := "rounded-md bg-indigo-500 px-3 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
          )(
            "Filter"
          ),
          a(
            href := "/issues",
            cls  := "rounded-md border border-white/20 px-3 py-2 text-sm text-slate-200 hover:bg-white/5",
          )(
            "Reset"
          ),
        ),
      ),
    )

  private def issueRow(issue: AgentIssue): Frag =
    div(cls := "border-b border-white/10 px-4 py-4 last:border-b-0")(
      div(cls := "flex items-start gap-3")(
        span(cls := "mt-1 inline-block h-3 w-3 rounded-full bg-emerald-400"),
        div(cls := "min-w-0 flex-1")(
          div(cls := "flex flex-wrap items-center gap-2")(
            a(
              href := s"/issues/${issue.id.getOrElse(0L)}",
              cls  := "text-base font-semibold text-slate-100 hover:text-indigo-300",
            )(issue.title),
            statusBadge(issue.status.toString),
            priorityBadge(issue.priority.toString),
            issue.tags.toList.flatMap(splitTags).map(tagBadge),
          ),
          p(cls := "mt-1 line-clamp-2 text-sm text-slate-300")(issue.description),
          div(cls := "mt-2 flex flex-wrap items-center gap-3 text-xs text-slate-400")(
            span(s"#${issue.id.getOrElse(0L)}"),
            span(s"updated ${issue.updatedAt.toString.take(19).replace('T', ' ')}"),
            issue.runId.map(id => span(s"run:$id")),
            issue.preferredAgent.map(agent => span(s"agent:$agent")),
            issue.sourceFolder.map(folder => span(s"source:$folder")),
          ),
        ),
      )
    )

  private def splitTags(tags: String): List[String] =
    tags.split(",").toList.map(_.trim).filter(_.nonEmpty)

  private def textField(fieldName: String, labelText: String, fieldValue: String, required: Boolean = false): Frag =
    div(
      label(cls := "mb-2 block text-sm font-semibold text-slate-200", `for` := fieldName)(labelText),
      input(
        `type` := "text",
        id     := fieldName,
        name   := fieldName,
        value  := fieldValue,
        cls    := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
        if required then scalatags.Text.all.required else (),
      ),
    )

  private def agentSelect(fieldName: String, selectedAgent: String, agents: List[AgentInfo]): Frag =
    val options      = agents
      .filter(_.usesAI)
      .sortBy(_.displayName.toLowerCase)
    val knownNames   = options.map(_.name.toLowerCase).toSet
    val hasSelection = selectedAgent.trim.nonEmpty
    val unknown      = selectedAgent.trim
    select(
      name := fieldName,
      cls  := "w-64 rounded-md border border-white/15 bg-slate-800/80 px-2 py-1.5 text-sm text-slate-100 focus:border-indigo-400/40 focus:outline-none",
    )(
      if !hasSelection then option(value := "", selected := "selected")("Select agent") else (),
      options.map { agent =>
        option(
          value := agent.name,
          if agent.name.equalsIgnoreCase(selectedAgent) then selected := "selected" else (),
        )(s"${agent.displayName} (${agent.name})")
      },
      if hasSelection && !knownNames.contains(unknown.toLowerCase) then
        option(
          value    := unknown,
          selected := "selected",
        )(s"$unknown (unknown)")
      else (),
    )

  private def metaItem(labelText: String, value: String): Frag =
    div(cls := "rounded border border-white/10 bg-slate-800/60 p-3")(
      p(cls := "text-xs uppercase tracking-wide text-slate-400")(labelText),
      p(cls := "mt-1 text-sm text-slate-100")(value),
    )

  private def statusOption(value: String, labelText: String, current: Option[String]): Frag =
    option(
      attr("value") := value,
      if current.contains(value) then attr("selected") := "selected" else (),
    )(labelText)

  private def statusBadge(status: String): Frag =
    span(cls := s"rounded-full px-2 py-0.5 text-xs font-semibold ${statusBadgeClass(status)}")(status)

  private def priorityBadge(priority: String): Frag =
    span(cls := s"rounded-full px-2 py-0.5 text-xs font-semibold ${priorityBadgeClass(priority)}")(priority)

  private def tagBadge(tag: String): Frag =
    span(cls := s"rounded-full border px-2 py-0.5 text-xs font-semibold ${tagBadgeClass(tag)}")(tag)

  private def priorityBadgeClass(priority: String): String =
    priority.toLowerCase match
      case "critical" => "bg-red-500/20 text-red-200"
      case "high"     => "bg-orange-500/20 text-orange-200"
      case "medium"   => "bg-yellow-500/20 text-yellow-200"
      case _          => "bg-blue-500/20 text-blue-200"

  private def statusBadgeClass(status: String): String =
    status.toLowerCase match
      case "open"        => "bg-emerald-500/20 text-emerald-200"
      case "assigned"    => "bg-indigo-500/20 text-indigo-200"
      case "in_progress" => "bg-violet-500/20 text-violet-200"
      case "completed"   => "bg-sky-500/20 text-sky-200"
      case "failed"      => "bg-red-500/20 text-red-200"
      case _             => "bg-slate-500/20 text-slate-200"

  private def assignmentStatusBadge(status: String): String =
    status.toLowerCase match
      case "pending"    => "bg-yellow-500/20 text-yellow-200"
      case "processing" => "bg-indigo-500/20 text-indigo-200"
      case "completed"  => "bg-emerald-500/20 text-emerald-200"
      case "failed"     => "bg-red-500/20 text-red-200"
      case _            => "bg-slate-500/20 text-slate-200"

  private def tagBadgeClass(tag: String): String =
    val palette = Vector(
      "border-rose-400/30 bg-rose-500/20 text-rose-200",
      "border-amber-400/30 bg-amber-500/20 text-amber-200",
      "border-emerald-400/30 bg-emerald-500/20 text-emerald-200",
      "border-cyan-400/30 bg-cyan-500/20 text-cyan-200",
      "border-indigo-400/30 bg-indigo-500/20 text-indigo-200",
      "border-fuchsia-400/30 bg-fuchsia-500/20 text-fuchsia-200",
    )
    val idx     = math.abs(tag.toLowerCase.hashCode) % palette.size
    palette(idx)

  def markdownFragment(markdown: String): Frag =
    val normalized = markdown.replace("\r\n", "\n")
    val lines      = normalized.split("\n", -1).toList
    val lineCount  = lines.length

    def collectWhile[A](start: Int)(f: String => Option[A]): (List[A], Int) =
      if start >= lineCount then (Nil, start)
      else
        f(lines(start)) match
          case Some(value) =>
            val (tail, next) = collectWhile(start + 1)(f)
            (value :: tail, next)
          case None        =>
            (Nil, start)

    def collectUntil(start: Int, stop: String => Boolean): (List[String], Int) =
      if start >= lineCount || stop(lines(start)) then (Nil, start)
      else
        val (tail, next) = collectUntil(start + 1, stop)
        (lines(start) :: tail, next)

    def headingFrag(level: Int, textValue: String): Frag =
      val headingCls = "mt-5 mb-2 font-semibold text-slate-50"
      level match
        case 1 => h1(cls := s"$headingCls text-2xl")(renderInline(textValue))
        case 2 => h2(cls := s"$headingCls text-xl")(renderInline(textValue))
        case 3 => h3(cls := s"$headingCls text-lg")(renderInline(textValue))
        case 4 => h4(cls := s"$headingCls text-base")(renderInline(textValue))
        case _ => h5(cls := s"$headingCls text-sm")(renderInline(textValue))

    def parseAt(idx: Int): List[Frag] =
      if idx >= lineCount then Nil
      else
        val line = lines(idx)
        if line.trim.isEmpty then parseAt(idx + 1)
        else if line.trim.startsWith("```") then
          val lang                  = line.trim.stripPrefix("```").trim
          val (codeLines, fenceIdx) = collectUntil(idx + 1, l => l.trim.startsWith("```"))
          val next                  = if fenceIdx < lineCount then fenceIdx + 1 else fenceIdx
          val block                 = div(cls := "my-4 rounded-lg border border-white/10 bg-black/30 p-0")(
            if lang.nonEmpty then
              div(cls := "border-b border-white/10 px-3 py-1 text-xs uppercase tracking-wide text-slate-400")(lang)
            else (),
            pre(cls := "overflow-auto px-3 py-3 text-sm leading-6 text-slate-100")(codeLines.mkString("\n")),
          )
          block :: parseAt(next)
        else
          headingLevel(line) match
            case Some((level, textValue)) =>
              headingFrag(level, textValue) :: parseAt(idx + 1)
            case None                     =>
              if line.trim.startsWith(">") then
                val (quoteLines, next) = collectWhile(idx)(current =>
                  Option.when(current.trim.startsWith(">"))(current.trim.stripPrefix(">").trim)
                )
                blockquote(cls := "my-3 border-l-4 border-indigo-400/40 pl-3 text-slate-300")(
                  paragraphWithBreaks(quoteLines)
                ) :: parseAt(next)
              else
                unorderedItem(line) match
                  case Some(_) =>
                    val (items, next) =
                      collectWhile(idx)(current => unorderedItem(current))
                    ul(cls := "my-3 list-disc space-y-1 pl-6 text-slate-100")(
                      items.map(item => li(renderInline(item)))
                    ) :: parseAt(next)
                  case None    =>
                    orderedItem(line) match
                      case Some(_) =>
                        val (items, next) =
                          collectWhile(idx)(current => orderedItem(current))
                        ol(cls := "my-3 list-decimal space-y-1 pl-6 text-slate-100")(
                          items.map(item => li(renderInline(item)))
                        ) :: parseAt(next)
                      case None    =>
                        val (paragraphLines, next) = collectWhile(idx)(current =>
                          Option.when(current.trim.nonEmpty && !startsBlock(current))(current)
                        )
                        p(cls := "my-3 whitespace-normal text-sm leading-7 text-slate-100")(
                          paragraphWithBreaks(paragraphLines)
                        ) :: parseAt(next)

    div(parseAt(0))

  private def paragraphWithBreaks(lines: List[String]): Seq[Frag] =
    lines.zipWithIndex.flatMap {
      case (line, idx) =>
        val parts = renderInline(line)
        if idx < lines.length - 1 then parts :+ br()
        else parts
    }

  private def renderInline(text: String): Seq[Frag] =
    def flush(buffer: String, acc: List[Frag]): List[Frag] =
      if buffer.nonEmpty then buffer :: acc else acc

    def loop(i: Int, buffer: String, acc: List[Frag]): List[Frag] =
      if i >= text.length then flush(buffer, acc).reverse
      else if text.startsWith("**", i) then
        val end = text.indexOf("**", i + 2)
        if end > i + 1 then
          val updated = strong(renderInline(text.substring(i + 2, end))) :: flush(buffer, acc)
          loop(end + 2, "", updated)
        else loop(i + 1, buffer + text.charAt(i), acc)
      else if text.charAt(i) == '*' then
        val end = text.indexOf('*', i + 1)
        if end > i then
          val updated = em(renderInline(text.substring(i + 1, end))) :: flush(buffer, acc)
          loop(end + 1, "", updated)
        else loop(i + 1, buffer + text.charAt(i), acc)
      else if text.charAt(i) == '`' then
        val end = text.indexOf('`', i + 1)
        if end > i then
          val updated =
            code(cls := "rounded bg-black/30 px-1 py-0.5 text-slate-100")(text.substring(i + 1, end)) :: flush(
              buffer,
              acc,
            )
          loop(end + 1, "", updated)
        else loop(i + 1, buffer + text.charAt(i), acc)
      else if text.charAt(i) == '[' then
        val closeBracket = text.indexOf(']', i + 1)
        val openParen    = if closeBracket >= 0 then text.indexOf('(', closeBracket + 1) else -1
        val closeParen   = if openParen >= 0 then text.indexOf(')', openParen + 1) else -1
        if closeBracket > i && openParen == closeBracket + 1 && closeParen > openParen then
          val label          = text.substring(i + 1, closeBracket)
          val urlCandidate   = text.substring(openParen + 1, closeParen)
          val linkFrag: Frag = safeHref(urlCandidate) match
            case Some(urlHref) =>
              a(
                href := urlHref,
                cls  := "text-indigo-300 underline decoration-indigo-400/60 underline-offset-2 hover:text-indigo-200",
              )(label)
            case None          =>
              span(s"[$label]($urlCandidate)")
          loop(closeParen + 1, "", linkFrag :: flush(buffer, acc))
        else loop(i + 1, buffer + text.charAt(i), acc)
      else loop(i + 1, buffer + text.charAt(i), acc)

    loop(0, "", Nil)

  private def safeHref(href: String): Option[String] =
    val normalized = href.trim
    val lower      = normalized.toLowerCase
    if lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("/") || lower.startsWith("#")
    then
      Some(normalized)
    else None

  private def headingLevel(line: String): Option[(Int, String)] =
    val trimmed = line.trim
    val hashes  = trimmed.takeWhile(_ == '#').length
    if hashes >= 1 && hashes <= 6 && trimmed.drop(hashes).startsWith(" ") then
      Some((hashes, trimmed.drop(hashes).trim))
    else None

  private def unorderedItem(line: String): Option[String] =
    val trimmed = line.trim
    if trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") then Some(trimmed.drop(2).trim)
    else None

  private def orderedItem(line: String): Option[String] =
    val trimmed = line.trim
    val dotIdx  = trimmed.indexOf('.')
    if dotIdx > 0 && trimmed.take(dotIdx).forall(_.isDigit) && trimmed.lift(dotIdx + 1).contains(' ') then
      Some(trimmed.drop(dotIdx + 2).trim)
    else None

  private def startsBlock(line: String): Boolean =
    line.trim.startsWith("```") ||
    headingLevel(line).isDefined ||
    line.trim.startsWith(">") ||
    unorderedItem(line).isDefined ||
    orderedItem(line).isDefined
