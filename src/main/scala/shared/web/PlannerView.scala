package shared.web

import orchestration.control.{ PlannerIssueDraft, PlannerPreviewState }
import scalatags.Text.all.*

object PlannerView:

  def startPage(workspaces: List[(String, String)]): String =
    Layout.page("Planner", "/planner")(
      div(cls := "mx-auto max-w-4xl space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-950/70 p-6")(
          h1(cls := "text-2xl font-semibold text-white")("Planner Agent"),
          p(cls := "mt-2 text-sm text-slate-300")(
            "Describe a feature, initiative, or refactor and generate an editable issue plan."
          ),
        ),
        div(cls := "rounded-xl border border-white/10 bg-slate-950/70 p-6")(
          form(action := "/planner", method := "post", cls := "space-y-4")(
            label(cls := "block text-[11px] font-semibold uppercase tracking-wide text-gray-400")(
              "Workspace",
              select(
                name := "workspace_id",
                cls  := "mt-1 w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-[12px] text-gray-100",
              )(
                option(value := "chat")("No workspace"),
                workspaces.map((id, name) => option(value := id)(name)),
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
              )(),
            ),
            div(cls := "flex items-center justify-between gap-3")(
              p(cls := "text-[11px] text-slate-500")(
                "The planner uses the built-in task-planner agent and generates structured issue drafts."
              ),
              button(
                `type` := "submit",
                cls    := "rounded bg-cyan-600 px-3 py-1.5 text-[11px] font-semibold text-white hover:bg-cyan-500",
              )("Start planner session"),
            ),
          )
        ),
      )
    )

  def detailPage(
    state: PlannerPreviewState,
    workspaces: List[(String, String)],
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
                )("Create issues")
              ),
            ),
          )
        ),
        div(cls := "grid gap-6 xl:grid-cols-[0.9fr,1.1fr]")(
          div(cls := "space-y-6")(
            div(cls := "rounded-xl border border-white/10 bg-slate-950/70 p-6")(
              h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Refine Plan"),
              p(cls := "mt-1 text-[11px] text-slate-500")(
                "Continue the planner conversation. Each follow-up regenerates the preview."
              ),
              form(action := s"/planner/${state.conversationId}/chat", method := "post", cls := "mt-4 space-y-3")(
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
                    cls    := "rounded bg-cyan-600 px-3 py-1.5 text-[11px] font-semibold text-white hover:bg-cyan-500",
                  )("Send refinement")
                ),
              ),
            ),
            div(cls := "rounded-xl border border-white/10 bg-slate-950/70 p-6")(
              h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Plan Summary"),
              p(cls := "mt-3 whitespace-pre-wrap text-sm text-slate-200")(state.preview.summary),
            ),
          ),
          div(cls := "space-y-6")(
            div(cls := "rounded-xl border border-white/10 bg-slate-950/70 p-6")(
              div(cls := "flex items-center justify-between gap-3")(
                div(
                  h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-200")("Issue Preview"),
                  p(cls := "mt-1 text-[11px] text-slate-500")(
                    "Edit drafts before confirming. Dependencies reference other draft ids."
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
                    "No issue drafts yet. Regenerate or add one manually."
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
        ),
      )
    )

  private def issueCard(conversationId: Long, draft: PlannerIssueDraft): Frag =
    div(cls := "rounded-xl border border-white/10 bg-black/20 p-4 space-y-3")(
      input(`type` := "hidden", name := "draft_id", value := draft.draftId),
      div(cls := "flex items-center justify-between gap-3")(
        div(
          span(
            cls := "inline-flex rounded-full bg-white/5 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-slate-300"
          )(
            draft.draftId
          ),
          p(cls := "mt-1 text-[11px] text-slate-500")("Atomic draft generated by the planner"),
        ),
        form(action := s"/planner/$conversationId/preview/remove", method := "post")(
          input(`type` := "hidden", name := "draft_id", value := draft.draftId),
          button(
            `type` := "submit",
            cls    := "rounded border border-rose-400/30 bg-rose-500/10 px-2 py-1 text-[11px] font-semibold text-rose-200 hover:bg-rose-500/20",
          )("Remove"),
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
