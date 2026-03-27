package shared.web

import checkpoint.control.*
import scalatags.Text.all.*

object CheckpointsView:

  def page(runs: List[CheckpointRunSummary]): String =
    Layout.page("Checkpoint Review", "/checkpoints")(
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          h1(cls := "text-2xl font-bold text-white")("Checkpoint Review"),
          p(cls := "mt-1 text-sm text-slate-300")(
            "Inspect active agent checkpoints, compare run state, and intervene without leaving the monitor surface."
          ),
        ),
        if runs.isEmpty then
          emptyState
        else
          div(cls := "grid gap-4 lg:grid-cols-2")(
            runs.map(runCard)*
          ),
      )
    )

  def detailPage(
    review: CheckpointRunReview,
    selectedStep: Option[String],
    compareLeft: Option[String],
    compareRight: Option[String],
    flash: Option[String],
  ): String =
    Layout.page(s"Checkpoint ${review.summary.runId}", "/checkpoints")(
      div(cls := "space-y-6")(
        flash.map(message => flashBanner(message)).getOrElse(frag()),
        detailHeader(review),
        div(cls := "grid gap-6 xl:grid-cols-[18rem,minmax(0,1fr)]")(
          timelinePanel(review, selectedStep),
          div(cls := "space-y-6")(
            actionsPanel(review.summary.runId),
            snapshotHost(review, selectedStep),
            comparisonPanel(review, compareLeft, compareRight),
          ),
        ),
      )
    )

  def snapshotFragment(runId: String, review: CheckpointSnapshotReview): String =
    div(cls := "space-y-5")(
      sectionCard(
        "Checkpoint",
        div(cls := "grid gap-3 md:grid-cols-2")(
          summaryLine("Step", review.snapshot.checkpoint.step),
          summaryLine("Created", review.snapshot.checkpoint.createdAt.toString),
          summaryLine(
            "Current",
            review.snapshot.state.currentStepName.getOrElse(review.snapshot.state.currentStep.toString),
          ),
          summaryLine("Status", review.snapshot.state.status.toString),
          summaryLine("Completed", review.snapshot.state.completedSteps.toList.sorted.mkString(", ")),
          summaryLine("Errors", review.snapshot.state.errors.size.toString),
        ),
      ),
      sectionCard(
        "Artifacts",
        if review.snapshot.state.artifacts.isEmpty then emptyInline("No checkpoint artifacts were captured.")
        else
          div(cls := "space-y-3")(
            review.snapshot.state.artifacts.toList.sortBy(_._1).map {
              case (key, value) =>
                div(cls := "rounded-lg border border-white/10 bg-black/20")(
                  div(
                    cls := "border-b border-white/10 px-3 py-2 text-xs font-semibold uppercase tracking-wide text-slate-400"
                  )(key),
                  pre(cls := "overflow-x-auto whitespace-pre-wrap px-3 py-3 text-xs text-slate-200")(truncate(
                    value,
                    6000,
                  )),
                )
            }
          ),
      ),
      sectionCard(
        "Git Diff",
        review.gitDiff match
          case Some(diff) =>
            pre(cls := "overflow-x-auto whitespace-pre-wrap text-xs text-slate-200")(truncate(diff, 12000))
          case None       => emptyInline("No git diff available for this checkpoint."),
      ),
      sectionCard(
        "Tests",
        if review.testSignals.isEmpty then emptyInline("No test or CI signals available for this checkpoint.")
        else
          div(cls := "space-y-3")(
            review.testSignals.map { signal =>
              div(cls := "rounded-lg border border-white/10 bg-black/20 px-3 py-3")(
                div(cls := "text-xs font-semibold uppercase tracking-wide text-slate-400")(signal.label),
                pre(cls := "mt-2 overflow-x-auto whitespace-pre-wrap text-xs text-slate-200")(truncate(
                  signal.content,
                  5000,
                )),
              )
            }
          ),
      ),
      sectionCard(
        "Conversation Excerpt",
        if review.conversationExcerpt.isEmpty then
          emptyInline("No conversation messages recorded before this checkpoint.")
        else
          div(cls := "space-y-3")(
            review.conversationExcerpt.map { message =>
              div(cls := "rounded-lg border border-white/10 bg-black/20 px-3 py-3")(
                div(cls := "flex items-center justify-between gap-3 text-[11px] text-slate-400")(
                  span(s"${message.sender} · ${message.senderType}"),
                  span(message.createdAt.toString),
                ),
                pre(cls := "mt-2 whitespace-pre-wrap text-xs text-slate-200")(truncate(message.content, 5000)),
              )
            }
          ),
      ),
      review.proofOfWork match
        case Some(report) =>
          sectionCard("Proof of Work", raw(ProofOfWorkView.panel(report, collapsed = false)))
        case None         => frag(),
    ).render

  def comparisonFragment(comparison: Option[CheckpointComparison]): String =
    comparison match
      case None        =>
        div(cls := "rounded-xl border border-dashed border-white/10 bg-slate-900/40 p-6 text-sm text-slate-400")(
          "Select two checkpoints from the same run to compare them."
        ).render
      case Some(value) =>
        div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
          div(cls := "flex flex-wrap items-center justify-between gap-3")(
            h2(cls := "text-lg font-semibold text-white")(s"${value.leftStep} -> ${value.rightStep}"),
            if value.currentStepChanged then badge("Current step changed", "amber")
            else badge("Current step stable", "emerald"),
          ),
          div(cls := "mt-4 grid gap-4 lg:grid-cols-2")(
            comparisonList("Completed Added", value.completedStepsAdded),
            comparisonList("Completed Removed", value.completedStepsRemoved),
            comparisonList("Errors Added", value.errorsAdded),
            comparisonList("Errors Resolved", value.errorsResolved),
          ),
          div(cls := "mt-4")(
            h3(cls := "text-sm font-semibold text-white")("Artifact Changes"),
            if value.artifactDeltas.isEmpty then
              p(cls := "mt-2 text-sm text-slate-400")("No artifact changes between the selected checkpoints.")
            else
              div(cls := "mt-3 space-y-3")(
                value.artifactDeltas.map { delta =>
                  div(cls := "rounded-lg border border-white/10 bg-black/20 p-3")(
                    div(cls := "text-xs font-semibold uppercase tracking-wide text-slate-400")(delta.key),
                    div(cls := "mt-3 grid gap-3 lg:grid-cols-2")(
                      deltaSide("Before", delta.before),
                      deltaSide("After", delta.after),
                    ),
                  )
                }
              ),
          ),
        ).render

  private def runCard(item: CheckpointRunSummary): Frag =
    a(
      href := s"/checkpoints/${item.runId}",
      cls  := "block rounded-xl border border-white/10 bg-slate-900/60 p-5 transition hover:border-cyan-400/40 hover:bg-slate-900/80",
    )(
      div(cls := "flex items-start justify-between gap-4")(
        div(
          h2(cls := "text-lg font-semibold text-white")(item.runId),
          p(cls := "mt-1 text-sm text-slate-300")(s"${item.agentName} · ${item.currentStepLabel}"),
        ),
        badge(item.stage, stageTone(item.stage)),
      ),
      div(cls := "mt-4 flex flex-wrap gap-2 text-xs text-slate-300")(
        chip(s"${item.checkpointCount} checkpoints"),
        item.issueId.map(id => chip(s"Issue $id")).getOrElse(frag()),
        item.workspaceId.map(id => chip(s"Workspace $id")).getOrElse(frag()),
        item.lastCheckpointAt.map(ts => chip(s"Updated ${ts.toString}")).getOrElse(chip("No checkpoints yet")),
      ),
      item.statusMessage.map { message =>
        p(cls := "mt-4 line-clamp-2 text-sm text-slate-400")(message)
      }.getOrElse(frag()),
    )

  private def detailHeader(review: CheckpointRunReview): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-5")(
      div(cls := "flex flex-wrap items-start justify-between gap-4")(
        div(
          h1(cls := "text-2xl font-bold text-white")(review.summary.runId),
          p(cls := "mt-1 text-sm text-slate-300")(s"${review.summary.agentName} · ${review.summary.currentStepLabel}"),
        ),
        div(cls := "flex flex-wrap gap-2")(
          badge(review.summary.stage, stageTone(review.summary.stage)),
          chip(s"${review.summary.checkpointCount} checkpoints"),
        ),
      ),
      div(cls := "mt-4 flex flex-wrap gap-2 text-xs text-slate-300")(
        review.summary.issueId.map(id => chip(s"Issue $id")).getOrElse(frag()),
        review.summary.workspaceId.map(id => chip(s"Workspace $id")).getOrElse(frag()),
        review.summary.conversationId.map(id => chip(s"Conversation $id")).getOrElse(frag()),
        review.summary.lastCheckpointAt.map(ts => chip(s"Last checkpoint ${ts.toString}")).getOrElse(chip(
          "No checkpoint timestamp"
        )),
      ),
      review.summary.statusMessage.map { message =>
        p(cls := "mt-4 text-sm text-slate-400")(message)
      }.getOrElse(frag()),
    )

  private def timelinePanel(review: CheckpointRunReview, selectedStep: Option[String]): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-4")(
      h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Run Checkpoints"),
      if review.checkpoints.isEmpty then
        p(cls := "mt-4 text-sm text-slate-400")("No checkpoints persisted for this run yet.")
      else
        div(cls := "mt-4 space-y-2")(
          review.checkpoints.reverse.map { snapshot =>
            val active = selectedStep.contains(snapshot.checkpoint.step) ||
              (selectedStep.isEmpty && review.checkpoints.lastOption.exists(
                _.checkpoint.step == snapshot.checkpoint.step
              ))
            a(
              href              := s"/checkpoints/${review.summary.runId}?step=${urlEncode(snapshot.checkpoint.step)}",
              cls               := s"block rounded-lg border px-3 py-3 text-sm ${
                  if active then "border-cyan-400/40 bg-cyan-500/10 text-white"
                  else "border-white/10 bg-black/20 text-slate-200"
                }",
              attr(
                "hx-get"
              )                 := s"/checkpoints/${review.summary.runId}/snapshots/${urlEncode(snapshot.checkpoint.step)}",
              attr("hx-target") := "#checkpoint-detail",
              attr("hx-swap")   := "innerHTML",
            )(
              div(cls := "flex items-center justify-between gap-3")(
                span(cls := "font-medium")(snapshot.checkpoint.step),
                span(cls := "text-[11px] text-slate-400")(snapshot.checkpoint.createdAt.toString),
              ),
              p(
                cls := "mt-1 text-xs text-slate-400"
              )(snapshot.state.currentStepName.getOrElse(snapshot.state.currentStep.toString)),
            )
          }
        ),
    )

  private def snapshotHost(review: CheckpointRunReview, selectedStep: Option[String]): Frag =
    div(
      id  := "checkpoint-detail",
      cls := "min-h-24",
    )(
      review.selected match
        case Some(value) => raw(snapshotFragment(review.summary.runId, value))
        case None        =>
          div(cls := "rounded-xl border border-dashed border-white/10 bg-slate-900/40 p-6 text-sm text-slate-400")(
            selectedStep match
              case Some(step) => s"Checkpoint $step is not available for this run."
              case None       => "Select a checkpoint to inspect its captured state."
          )
    )

  private def actionsPanel(runId: String): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Intervene"),
      form(action := s"/checkpoints/$runId/actions/approve-continue", method := "post", cls := "mt-4 space-y-3")(
        textarea(
          name        := "note",
          rows        := "3",
          placeholder := "Optional operator note for continue or redirect actions",
          cls         := "w-full rounded-lg border border-white/10 bg-black/20 px-3 py-2 text-sm text-slate-100",
        )(),
        div(cls := "flex flex-wrap gap-2")(
          actionButton("Approve / Continue", s"/checkpoints/$runId/actions/approve-continue", "emerald"),
          actionButton("Redirect", s"/checkpoints/$runId/actions/redirect", "amber"),
          actionButton("Pause", s"/checkpoints/$runId/actions/pause", "sky"),
          actionButton("Abort", s"/checkpoints/$runId/actions/abort", "rose"),
          actionButton("Flag Full Review", s"/checkpoints/$runId/actions/flag-full-review", "slate"),
        ),
      ),
    )

  private def actionButton(labelText: String, target: String, tone: String): Frag =
    button(
      `type`     := "submit",
      formaction := target,
      cls        := s"rounded border border-$tone-400/30 bg-$tone-500/10 px-3 py-2 text-xs font-semibold text-$tone-200 hover:bg-$tone-500/20",
    )(labelText)

  private def comparisonPanel(
    review: CheckpointRunReview,
    compareLeft: Option[String],
    compareRight: Option[String],
  ): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      div(cls := "flex items-center justify-between gap-3")(
        h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")("Compare Checkpoints"),
        span(cls := "text-xs text-slate-500")("Same run only"),
      ),
      form(
        action := s"/checkpoints/${review.summary.runId}",
        method := "get",
        cls    := "mt-4 grid gap-3 lg:grid-cols-[1fr,1fr,auto]",
      )(
        input(
          `type` := "hidden",
          name   := "step",
          value  := review.selected.map(_.snapshot.checkpoint.step).getOrElse(""),
        ),
        checkpointSelect("compare_left", compareLeft, review.checkpoints),
        checkpointSelect("compare_right", compareRight, review.checkpoints),
        button(
          `type`             := "submit",
          cls                := "rounded bg-cyan-600 px-3 py-2 text-xs font-semibold text-white hover:bg-cyan-500",
          attr("hx-get")     := s"/checkpoints/${review.summary.runId}/compare",
          attr("hx-target")  := "#checkpoint-compare",
          attr("hx-include") := "closest form",
          attr("hx-swap")    := "innerHTML",
        )("Compare"),
      ),
      div(id := "checkpoint-compare", cls := "mt-4")(
        raw(comparisonFragment(review.comparison))
      ),
    )

  private def checkpointSelect(
    name: String,
    selected: Option[String],
    checkpoints: List[taskrun.entity.CheckpointSnapshot],
  ): Frag =
    select(
      attr("name") := name,
      cls          := "w-full rounded border border-white/10 bg-black/20 px-3 py-2 text-sm text-slate-100",
    )(
      option(value := "")("Choose checkpoint"),
      checkpoints.map { snapshot =>
        option(
          value := snapshot.checkpoint.step,
          if selected.contains(snapshot.checkpoint.step) then attr("selected") := "selected" else (),
        )(snapshot.checkpoint.step)
      },
    )

  private def comparisonList(titleText: String, values: List[String]): Frag =
    div(cls := "rounded-lg border border-white/10 bg-black/20 p-3")(
      h3(cls := "text-sm font-semibold text-white")(titleText),
      if values.isEmpty then p(cls := "mt-2 text-sm text-slate-400")("None")
      else ul(cls := "mt-2 space-y-1 text-sm text-slate-200")(values.map(v => li(v))),
    )

  private def deltaSide(labelText: String, value: Option[String]): Frag =
    div(
      div(cls := "text-[11px] font-semibold uppercase tracking-wide text-slate-400")(labelText),
      pre(cls := "mt-2 overflow-x-auto whitespace-pre-wrap text-xs text-slate-200")(truncate(
        value.getOrElse("(none)"),
        3000,
      )),
    )

  private def sectionCard(titleText: String, content: Frag*): Frag =
    div(cls := "rounded-xl border border-white/10 bg-slate-900/60 p-5")(
      h2(cls := "text-sm font-semibold uppercase tracking-wide text-slate-300")(titleText),
      div(cls := "mt-4")(content),
    )

  private def summaryLine(labelText: String, value: String): Frag =
    div(cls := "rounded-lg border border-white/10 bg-black/20 px-3 py-3")(
      div(cls := "text-[11px] font-semibold uppercase tracking-wide text-slate-400")(labelText),
      p(cls := "mt-1 text-sm text-slate-200")(if value.trim.nonEmpty then value else "—"),
    )

  private def flashBanner(message: String): Frag =
    div(cls := "rounded-xl border border-emerald-400/20 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-100")(message)

  private def badge(labelText: String, tone: String): Frag =
    span(cls := s"rounded-full border px-3 py-1 text-xs font-semibold $tone")(labelText)

  private def chip(labelText: String): Frag =
    span(cls := "rounded-full border border-white/10 bg-black/20 px-3 py-1 text-xs text-slate-200")(labelText)

  private def emptyState: Frag =
    div(cls := "rounded-xl border border-dashed border-white/10 bg-slate-900/40 p-10 text-center")(
      p(cls := "text-slate-300")("No active runs expose checkpoints right now.")
    )

  private def emptyInline(message: String): Frag =
    p(cls := "text-sm text-slate-400")(message)

  private def stageTone(stage: String): String =
    stage match
      case "EXEC"   => "border-emerald-400/30 bg-emerald-500/10 text-emerald-200"
      case "TOOL"   => "border-amber-400/30 bg-amber-500/10 text-amber-200"
      case "PAUSED" => "border-sky-400/30 bg-sky-500/10 text-sky-200"
      case "ABORT"  => "border-rose-400/30 bg-rose-500/10 text-rose-200"
      case "FAIL"   => "border-rose-400/30 bg-rose-500/10 text-rose-200"
      case _        => "border-slate-400/30 bg-slate-500/10 text-slate-200"

  private def truncate(value: String, max: Int): String =
    if value.length <= max then value else value.take(max - 1) + "…"

  private def urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
