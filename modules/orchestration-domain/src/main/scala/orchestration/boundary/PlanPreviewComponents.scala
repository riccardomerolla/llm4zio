package orchestration.boundary

import orchestration.entity.PlannerPreviewState
import plan.entity.PlanTaskDraft
import scalatags.Text.all.*

/** Renders the planner-preview panels (plan summary, plan graph, Mermaid
  * diagram init script).
  *
  * Moved from `shared.web` to `orchestration.boundary` in phase 5A.8.
  * The view's primary data source is `orchestration.entity.PlannerPreviewState`
  * (produced by `PlannerAgentService`). `plan.entity.PlanTaskDraft` is
  * reachable because `orchestrationDomain` already `dependsOn(planDomain)`.
  * Target was briefly considered to be `plan-domain/boundary/`, but
  * `orchestrationDomain → planDomain` already exists, so reverse-depping
  * would cycle. Orchestration is the natural home since the view renders
  * orchestration-produced state.
  */
object PlanPreviewComponents:

  def planPanelsContent(state: PlannerPreviewState, basePath: String, fragmentPath: String): Frag =
    val graph = planGraph(state.preview.issues)
    div(
      id  := s"planner-plan-panels-${state.conversationId}",
      cls := "grid gap-6 xl:grid-cols-[0.9fr,1.1fr]",
      if state.isGenerating then attr("hx-get")     := fragmentPath else (),
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
            form(action := s"$basePath/preview/add", method := "post")(
              button(
                `type` := "submit",
                cls    := "rounded border border-cyan-400/30 bg-cyan-500/15 px-3 py-1.5 text-[11px] font-semibold text-cyan-200 hover:bg-cyan-500/25",
              )("Add issue")
            ),
          ),
          form(action := s"$basePath/preview", method := "post", cls := "mt-5 space-y-6")(
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
              div(cls := "space-y-4")(state.preview.issues.map(issueCard(basePath, _))*)
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

  def planGraph(drafts: List[PlanTaskDraft]): PlanGraph =
    val included = drafts.filter(_.included)
    val byId     = included.map(d => d.draftId -> d).toMap
    val deps     = included.map(d => d.draftId -> d.dependencyDraftIds.filter(byId.contains).distinct).toMap
    val batches  = topoBatches(deps)
    val labels   = included.map(d => d.draftId -> d.title).toMap
    val nodeIds  = included.zipWithIndex.map {
      case (draft, index) =>
        draft.draftId -> sanitizeMermaidNodeId(draft.draftId, index)
    }.toMap
    val edges    =
      included.flatMap(d =>
        d.dependencyDraftIds.filter(byId.contains).map(dep => s"  ${nodeIds(dep)} --> ${nodeIds(d.draftId)}")
      )
    val nodes    = included.map(d => s"  ${nodeIds(d.draftId)}[\"${escapeMermaid(labels(d.draftId))}\"]")
    val mermaid  =
      if included.isEmpty then "graph TD\n  empty[\"No included issues\"]"
      else (List("graph TD") ++ nodes ++ edges).mkString("\n")
    PlanGraph(
      mermaid = mermaid,
      batches = batches.map(_.map(id => s"$id (${labels.getOrElse(id, id)})")),
      recommendedOrder = batches.flatten.map(id => s"$id (${labels.getOrElse(id, id)})"),
    )

  def issueCard(basePath: String, draft: PlanTaskDraft): Frag =
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
          button(
            `type`             := "submit",
            attr("formaction") := s"$basePath/preview/remove",
            attr("formmethod") := "post",
            name               := "remove_draft_id",
            value              := draft.draftId,
            cls                := "rounded border border-rose-400/30 bg-rose-500/10 px-2 py-1 text-[11px] font-semibold text-rose-200 hover:bg-rose-500/20",
          )("Remove"),
        ),
      ),
      div(cls := "grid gap-3 md:grid-cols-2")(
        labeledInput("Title", "title", draft.title),
        labeledInput("Issue Type", "issue_type", draft.issueType),
      ),
      div(cls := "grid gap-3 md:grid-cols-4")(
        labeledInput("Priority", "priority", draft.priority),
        labeledInput("Estimate", "estimate", draft.estimate.getOrElse("")),
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

  def labeledInput(labelText: String, nameValue: String, valueText: String): Frag =
    label(cls := "block text-[11px] font-semibold uppercase tracking-wide text-gray-400")(
      labelText,
      input(
        name  := nameValue,
        value := valueText,
        cls   := "mt-1 w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-[12px] text-gray-100",
      ),
    )

  def labeledArea(labelText: String, nameValue: String, valueText: String, rowsCount: Int): Frag =
    label(cls := "block text-[11px] font-semibold uppercase tracking-wide text-gray-400")(
      labelText,
      textarea(
        name := nameValue,
        rows := rowsCount,
        cls  := "mt-1 w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-[12px] text-gray-100",
      )(valueText),
    )

  def plannerAlert(message: String): Frag =
    div(cls := "rounded-xl border border-amber-400/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-100")(
      div(cls := "text-[11px] font-semibold uppercase tracking-wide text-amber-200")("Planner warning"),
      p(cls := "mt-1 whitespace-pre-wrap")(message),
    )

  def plannerStreamScript(conversationId: Long, isGenerating: Boolean): Frag =
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

  val mermaidInitScript: Frag =
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

  final case class PlanGraph(
    mermaid: String,
    batches: List[List[String]],
    recommendedOrder: List[String],
  ):
    def hasIssues: Boolean = recommendedOrder.nonEmpty

  def topoBatches(deps: Map[String, List[String]]): List[List[String]] =
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

  def escapeMermaid(value: String): String =
    value
      .replaceAll("[\\r\\n\\t]+", " ")
      .replaceAll("[\\[\\]{}|<>]", " ")
      .replace("\"", "'")
      .replace("`", "'")
      .replace("\\", "/")
      .replaceAll("\\s+", " ")
      .trim
      .take(120)

  private def sanitizeMermaidNodeId(draftId: String, index: Int): String =
    val base       = draftId.trim.toLowerCase.replaceAll("[^a-z0-9_\\-]", "_").replaceAll("_+", "_")
    val normalized = if base.nonEmpty then base else s"issue_${index + 1}"
    s"node_${index + 1}_$normalized"
