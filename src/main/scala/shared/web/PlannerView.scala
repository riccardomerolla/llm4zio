package shared.web

import conversation.entity.api.ConversationEntry
import orchestration.control.{ PlannerIssueDraft, PlannerPreviewState }
import scalatags.Text.all.*

object PlannerView:

  def startPage(
    workspaces: List[(String, String)],
    initialRequest: String = "",
    selectedWorkspaceId: Option[String] = None,
    errorMessage: Option[String] = None,
  ): String =
    Layout.page("Planner", "/planner")(
      div(cls := "mx-auto max-w-4xl space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-950/70 p-6")(
          h1(cls := "text-2xl font-semibold text-white")("Planner Agent"),
          p(cls := "mt-2 text-sm text-slate-300")(
            "Start a planner conversation inside a workspace context, refine it in chat, then create issues from the plan."
          ),
        ),
        errorMessage.fold[Frag](frag())(plannerAlert),
        div(cls := "rounded-xl border border-white/10 bg-slate-950/70 p-6")(
          form(
            action   := "/planner",
            method   := "post",
            cls      := "space-y-4",
            onsubmit := """const button=this.querySelector('button[type=submit]');if(button){button.disabled=true;button.classList.add('opacity-60','cursor-not-allowed');button.textContent='Starting planner...';button.setAttribute('aria-busy','true');}""",
          )(
            label(cls := "block text-[11px] font-semibold uppercase tracking-wide text-gray-400")(
              "Workspace",
              select(
                name := "workspace_id",
                cls  := "mt-1 w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-[12px] text-gray-100",
              )(
                option(
                  value := "chat",
                  if selectedWorkspaceId.isEmpty then selected := "selected" else (),
                )("No workspace"),
                workspaces.map { (id, name) =>
                  option(
                    value := id,
                    if selectedWorkspaceId.contains(id) then selected := "selected" else (),
                  )(name)
                },
              ),
            ),
            label(cls := "block text-[11px] font-semibold uppercase tracking-wide text-gray-400")(
              "Request",
              textarea(
                name              := "request",
                required          := "required",
                rows              := 9,
                cls               := "mt-1 w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-[12px] text-gray-100 placeholder:text-gray-500 focus:border-cyan-400 focus:outline-none",
                attr("autofocus") := "autofocus",
                placeholder       := "Describe the feature, constraints, expected outcomes, and any known dependencies...",
              )(initialRequest),
            ),
            div(cls := "flex items-center justify-between gap-3")(
              p(cls := "text-[11px] text-slate-500")(
                "The planner keeps a real conversation thread and an editable issue preview side by side."
              ),
              button(
                `type` := "submit",
                cls    := "rounded bg-cyan-600 px-3 py-1.5 text-[11px] font-semibold text-white hover:bg-cyan-500 disabled:opacity-60 disabled:cursor-not-allowed",
              )("Start planner session"),
            ),
          )
        ),
      ),
      JsResources.markedScript,
      ChatView.markdownRenderScript,
      JsResources.mermaidScript,
      script(
        raw(
          """document.addEventListener('DOMContentLoaded', function () {
            |  if (!window.mermaid) return;
            |  window.mermaid.initialize({ startOnLoad: false, securityLevel: 'loose' });
            |  var nodes = Array.from(document.querySelectorAll('.planner-mermaid'));
            |  if (nodes.length > 0) {
            |    window.mermaid.run({ nodes: nodes }).catch(function () {});
            |  }
            |});""".stripMargin
        )
      ),
    )

  def detailPage(
    state: PlannerPreviewState,
    messages: List[ConversationEntry] = Nil,
    workspaces: List[(String, String)] = Nil,
    errorMessage: Option[String] = None,
    canonicalPath: Option[String] = None,
  ): String =
    Layout.page("Planner", "/planner")(
      div(cls := "mx-auto max-w-6xl space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-950/70 p-6")(
          div(cls := "flex flex-wrap items-center justify-between gap-3")(
            div(
              h1(cls := "text-2xl font-semibold text-white")("Planner Session"),
              p(cls := "mt-1 text-sm text-slate-300")(
                s"Conversation #${state.conversationId}" + state.workspaceId.fold("")(id =>
                  workspaces.find(
                    _._1 == id
                  ).map { case (_, name) => s" · Workspace: $name" }.getOrElse(s" · Workspace: $id")
                )
              ),
            ),
            div(cls := "flex items-center gap-2")(
              a(
                href := s"/chat/${state.conversationId}",
                cls  := "rounded border border-white/10 px-3 py-1.5 text-[11px] font-semibold text-slate-200 hover:bg-white/5",
              )("Open chat"),
              form(action := s"/planner/${state.conversationId}/refresh", method := "post")(
                button(
                  `type` := "submit",
                  cls    := "rounded border border-white/10 px-3 py-1.5 text-[11px] font-semibold text-slate-200 hover:bg-white/5",
                )("Regenerate")
              ),
              form(action := s"/planner/${state.conversationId}/confirm", method := "post")(
                button(
                  `type` := "submit",
                  cls    := "rounded bg-emerald-600 px-3 py-1.5 text-[11px] font-semibold text-white hover:bg-emerald-500",
                )("Create all")
              ),
            ),
          )
        ),
        errorMessage.orElse(state.lastError).fold[Frag](frag())(plannerAlert),
        div(cls := "space-y-6")(
          div(cls := "rounded-xl border border-white/10 bg-slate-950/70 p-6")(
            h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Planner Conversation"),
            p(cls := "mt-1 text-[11px] text-slate-500")(
              "You are in the planner session immediately. The preview keeps updating as the planner responds."
            ),
            tag("chat-message-stream")(
              id                      := s"planner-messages-${state.conversationId}",
              cls                     := "mt-4 block max-h-[30rem] overflow-y-auto rounded-2xl bg-slate-950/55 p-4 ring-1 ring-white/5",
              attr("conversation-id") := state.conversationId.toString,
              attr("ws-url")          := "/ws/console",
              attr("data-generating") := state.isGenerating.toString,
            )(
              raw(ChatView.messagesFragment(messages, Some(state.conversationId.toString)))
            ),
            form(
              action   := s"/planner/${state.conversationId}/chat",
              method   := "post",
              cls      := "mt-4 space-y-3",
              onsubmit := """const button=this.querySelector('button[type=submit]');if(button){button.disabled=true;button.classList.add('opacity-60','cursor-not-allowed');button.textContent='Sending refinement...';button.setAttribute('aria-busy','true');}""",
            )(
              textarea(
                name        := "message",
                rows        := 7,
                required    := "required",
                cls         := "w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-[12px] text-gray-100 placeholder:text-gray-500 focus:border-cyan-400 focus:outline-none",
                placeholder := "Ask the planner to split tasks further, rebalance dependencies, or tighten acceptance criteria...",
              )(),
              div(cls := "flex justify-end")(
                button(
                  `type` := "submit",
                  cls    := "rounded bg-cyan-600 px-3 py-1.5 text-[11px] font-semibold text-white hover:bg-cyan-500 disabled:opacity-60 disabled:cursor-not-allowed",
                )("Send refinement")
              ),
            ),
          ),
          raw(planPanels(state)),
        ),
      ),
      JsResources.markedScript,
      ChatView.markdownRenderScript,
      JsResources.mermaidScript,
      JsResources.inlineModuleScript("/static/client/components/chat-message-stream.js"),
      canonicalPath.fold[Frag](frag())(canonicalPathScript),
      plannerStreamScript(state.conversationId, state.isGenerating),
      mermaidInitScript,
    )

  def planPanels(state: PlannerPreviewState): String =
    planPanelsContent(state).render

  private def planPanelsContent(state: PlannerPreviewState): Frag =
    val graph = planGraph(state.preview.issues)
    div(
      id  := s"planner-plan-panels-${state.conversationId}",
      cls := "grid gap-6 xl:grid-cols-[0.9fr,1.1fr]",
      if state.isGenerating then attr("hx-get")     := s"/planner/${state.conversationId}/plan-fragment" else (),
      if state.isGenerating then attr("hx-trigger") := "every 2s" else (),
      if state.isGenerating then attr("hx-swap")    := "outerHTML" else (),
    )(
      div(cls := "space-y-6")(
        state.lastError.fold[Frag](frag())(plannerAlert),
        div(cls := "rounded-xl border border-white/10 bg-slate-950/70 p-6")(
          div(cls := "flex items-center justify-between gap-3")(
            h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Plan Summary"),
            if state.isGenerating then
              span(cls := "rounded-full bg-cyan-500/15 px-2.5 py-1 text-[11px] font-semibold text-cyan-200")(
                "Generating preview..."
              )
            else frag(),
          ),
          p(cls := "mt-3 whitespace-pre-wrap text-sm text-slate-200")(state.preview.summary),
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-950/70 p-6 space-y-4")(
          h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Execution Graph"),
          if graph.hasIssues then
            frag(
              div(cls := "rounded-lg border border-white/10 bg-black/20 p-3 text-xs text-slate-200 overflow-x-auto")(
                div(cls := "planner-mermaid mermaid")(graph.mermaid)
              ),
              div(cls := "grid gap-3 md:grid-cols-2")(
                div(
                  h3(cls := "text-[11px] font-semibold uppercase tracking-wide text-slate-400")("Parallel batches"),
                  div(cls := "mt-2 space-y-2")(
                    graph.batches.zipWithIndex.map {
                      case (batch, index) =>
                        div(cls := "rounded border border-white/10 bg-black/20 px-3 py-2 text-xs text-slate-200")(
                          span(cls := "font-semibold text-cyan-200")(s"Batch ${index + 1}"),
                          span(cls := "ml-2")(batch.mkString(", ")),
                        )
                    }
                  ),
                ),
                div(
                  h3(cls := "text-[11px] font-semibold uppercase tracking-wide text-slate-400")("Recommended order"),
                  p(cls := "mt-2 text-sm text-slate-200")(graph.recommendedOrder.mkString(" -> ")),
                ),
              ),
            )
          else if state.isGenerating then
            p(cls := "text-sm text-slate-400")("Waiting for the planner to produce a graph...")
          else
            p(cls := "text-sm text-slate-400")("Add at least one included issue to generate the execution graph."),
        ),
      ),
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-950/70 p-6")(
          div(cls := "flex items-center justify-between gap-3")(
            div(
              h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Issue Preview"),
              p(cls := "mt-1 text-[11px] text-slate-500")(
                if state.isGenerating then "The planner is updating the issue drafts live."
                else "Edit drafts before confirming. Dependencies reference other draft ids."
              ),
            ),
            form(action := s"/planner/${state.conversationId}/preview/add", method := "post")(
              button(
                `type` := "submit",
                cls    := "rounded border border-cyan-400/30 bg-cyan-500/15 px-3 py-1.5 text-[11px] font-semibold text-cyan-200 hover:bg-cyan-500/25",
              )("Add issue")
            ),
          ),
          form(action := s"/planner/${state.conversationId}/preview", method := "post", cls := "mt-5 space-y-6")(
            label(cls := "block text-[11px] font-semibold uppercase tracking-wide text-gray-400")(
              "Summary",
              textarea(
                name := "summary",
                rows := 4,
                cls  := "mt-1 w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-[12px] text-gray-100",
              )(state.preview.summary),
            ),
            if state.preview.issues.isEmpty then
              div(cls := "rounded-lg border border-dashed border-white/10 p-6 text-center text-sm text-slate-400")(
                if state.isGenerating then "Waiting for issue drafts..."
                else "No issue drafts yet. Regenerate or add one manually."
              )
            else
              div(cls := "space-y-4")(state.preview.issues.map(issueCard(state.conversationId, _))*)
            ,
            div(cls := "flex justify-end")(
              button(
                `type` := "submit",
                cls    := "rounded bg-indigo-600 px-3 py-1.5 text-[11px] font-semibold text-white hover:bg-indigo-500",
              )("Save preview changes")
            ),
          ),
        )
      ),
    )

  private def issueCard(conversationId: Long, draft: PlannerIssueDraft): Frag =
    div(cls := "rounded-xl border border-white/10 bg-black/20 p-4 space-y-3")(
      input(`type` := "hidden", name := "draft_id", value := draft.draftId),
      input(`type` := "hidden", name := "included", value := draft.included.toString),
      div(cls := "flex items-center justify-between gap-3")(
        div(
          span(
            cls := "inline-flex rounded-full bg-white/5 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-slate-300"
          )(
            draft.draftId
          ),
          p(cls := "mt-1 text-[11px] text-slate-500")("Atomic draft generated by the planner"),
        ),
        div(cls := "flex items-center gap-3")(
          label(cls := "flex items-center gap-2 text-[11px] font-semibold uppercase tracking-wide text-slate-300")(
            input(
              `type`   := "checkbox",
              value    := "true",
              if draft.included then checked := "checked" else (),
              onchange := "this.closest('div.rounded-xl').querySelector('input[name=included]').value=this.checked?'true':'false';",
            ),
            span(if draft.included then "Included" else "Excluded"),
          ),
          form(action := s"/planner/$conversationId/preview/remove", method := "post")(
            input(`type` := "hidden", name := "draft_id", value := draft.draftId),
            button(
              `type` := "submit",
              cls    := "rounded border border-rose-400/30 bg-rose-500/10 px-2 py-1 text-[11px] font-semibold text-rose-200 hover:bg-rose-500/20",
            )("Remove"),
          ),
        ),
      ),
      div(cls := "grid gap-3 md:grid-cols-2")(
        labeledInput("Title", "title", draft.title),
        labeledInput("Issue Type", "issue_type", draft.issueType),
      ),
      div(cls := "grid gap-3 md:grid-cols-3")(
        labeledInput("Priority", "priority", draft.priority),
        labeledInput("Capabilities", "required_capabilities", draft.requiredCapabilities.mkString(",")),
        labeledInput("Dependencies", "dependency_draft_ids", draft.dependencyDraftIds.mkString(",")),
      ),
      labeledArea("Description", "description", draft.description, 4),
      labeledArea("Acceptance Criteria", "acceptance_criteria", draft.acceptanceCriteria, 3),
      labeledArea("Prompt Template", "prompt_template", draft.promptTemplate, 5),
      labeledInput("Kaizen Skills", "kaizen_skills", draft.kaizenSkills.mkString(",")),
      labeledInput(
        "Proof Of Work",
        "proof_of_work_requirements",
        draft.proofOfWorkRequirements.mkString(","),
      ),
    )

  private def labeledInput(labelText: String, nameValue: String, valueText: String): Frag =
    label(cls := "block text-[11px] font-semibold uppercase tracking-wide text-gray-400")(
      labelText,
      input(
        name  := nameValue,
        value := valueText,
        cls   := "mt-1 w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-[12px] text-gray-100",
      ),
    )

  private def labeledArea(labelText: String, nameValue: String, valueText: String, rowsCount: Int): Frag =
    label(cls := "block text-[11px] font-semibold uppercase tracking-wide text-gray-400")(
      labelText,
      textarea(
        name := nameValue,
        rows := rowsCount,
        cls  := "mt-1 w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-[12px] text-gray-100",
      )(valueText),
    )

  private def plannerAlert(message: String): Frag =
    div(cls := "rounded-xl border border-amber-400/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-100")(
      div(cls := "text-[11px] font-semibold uppercase tracking-wide text-amber-200")("Planner warning"),
      p(cls := "mt-1 whitespace-pre-wrap")(message),
    )

  private def canonicalPathScript(path: String): Frag =
    script(
      raw(
        s"""document.addEventListener('DOMContentLoaded', function () {
           |  if (window.location.pathname !== ${'"'}$path${'"'}) {
           |    window.history.replaceState(window.history.state, '', ${'"'}$path${'"'});
           |  }
           |});""".stripMargin
      )
    )

  private def plannerStreamScript(conversationId: Long, isGenerating: Boolean): Frag =
    script(
      raw(
        s"""document.addEventListener('DOMContentLoaded', function () {
           |  var stream = document.getElementById('planner-messages-$conversationId');
           |  if (!stream) return;
           |  stream.dataset.generating = '${isGenerating.toString}';
           |  if (${if isGenerating then "true" else "false"}) {
           |    var markPending = function () {
           |      if (typeof stream.markPending === 'function') stream.markPending();
           |      else window.setTimeout(markPending, 50);
           |    };
           |    markPending();
           |  }
           |});""".stripMargin
      )
    )

  private val mermaidInitScript: Frag =
    script(
      raw(
        """document.addEventListener('DOMContentLoaded', function () {
          |  if (!window.mermaid) return;
          |  window.mermaid.initialize({ startOnLoad: false, securityLevel: 'loose' });
          |  var render = function () {
          |    var nodes = Array.from(document.querySelectorAll('.planner-mermaid'));
          |    if (nodes.length > 0) {
          |      window.mermaid.run({ nodes: nodes }).catch(function () {});
          |    }
          |  };
          |  render();
          |  document.body.addEventListener('htmx:afterSwap', function (event) {
          |    if (event.target && event.target.id && event.target.id.indexOf('planner-plan-panels-') === 0) render();
          |  });
          |});""".stripMargin
      )
    )

  final private case class PlanGraph(
    mermaid: String,
    batches: List[List[String]],
    recommendedOrder: List[String],
  ):
    def hasIssues: Boolean = recommendedOrder.nonEmpty

  private def planGraph(drafts: List[PlannerIssueDraft]): PlanGraph =
    val included = drafts.filter(_.included)
    val byId     = included.map(d => d.draftId -> d).toMap
    val deps     = included.map(d => d.draftId -> d.dependencyDraftIds.filter(byId.contains).distinct).toMap
    val batches  = topoBatches(deps)
    val labels   = included.map(d => d.draftId -> d.title).toMap
    val edges    =
      included.flatMap(d => d.dependencyDraftIds.filter(byId.contains).map(dep => s"""  "$dep" --> "${d.draftId}""""))
    val nodes    = included.map(d => s"""  "${d.draftId}"["${escapeMermaid(labels(d.draftId))}"]""")
    val mermaid  =
      if included.isEmpty then "graph TD\n  empty[\"No included issues\"]"
      else (List("graph TD") ++ nodes ++ edges).mkString("\n")
    PlanGraph(
      mermaid = mermaid,
      batches = batches.map(_.map(id => s"$id (${labels.getOrElse(id, id)})")),
      recommendedOrder = batches.flatten.map(id => s"$id (${labels.getOrElse(id, id)})"),
    )

  private def topoBatches(deps: Map[String, List[String]]): List[List[String]] =
    def loop(remaining: Map[String, List[String]], acc: List[List[String]]): List[List[String]] =
      if remaining.isEmpty then acc.reverse
      else
        val ready = remaining.collect { case (id, blockedBy) if blockedBy.isEmpty => id }.toList.sorted
        if ready.isEmpty then (remaining.keys.toList.sorted :: acc).reverse
        else
          val next = remaining.view
            .filterKeys(id => !ready.contains(id))
            .mapValues(_.filterNot(ready.contains))
            .toMap
          loop(next, ready :: acc)
    loop(deps, Nil)

  private def escapeMermaid(value: String): String =
    value.replace("\"", "'")
