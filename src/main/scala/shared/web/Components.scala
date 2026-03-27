package shared.web

import db.RunStatus
import scalatags.Text.all.*
import scalatags.Text.svgAttrs.{ d, viewBox }
import scalatags.Text.svgTags.{ path, svg }

/** Design-system component wrappers.
  *
  * Primitive display components delegate to `ab-*` Lit web components (see
  * `resources/static/client/components/design-system/`), which use light-DOM rendering so Tailwind classes are applied
  * without Shadow DOM restrictions.
  *
  * Views that import Components.scala methods automatically use the web components without needing individual changes.
  */
object Components:

  // ── Badge ────────────────────────────────────────────────────────────────

  /** Generic badge — emits `<ab-badge text="..." variant="...">`. */
  def badge(text: String, variant: String = "default"): Frag =
    tag("ab-badge")(attr("text") := text, attr("variant") := variant)

  /** Status-mapped badge for `RunStatus`. */
  def statusBadge(status: RunStatus): Frag =
    val (variant, label) = status match
      case RunStatus.Pending   => ("warning", "Pending")
      case RunStatus.Running   => ("info", "Running")
      case RunStatus.Paused    => ("amber", "Paused")
      case RunStatus.Completed => ("success", "Completed")
      case RunStatus.Failed    => ("error", "Failed")
      case RunStatus.Cancelled => ("gray", "Cancelled")
    badge(label, variant)

  /** File-type badge for `FileType`. */
  def fileTypeBadge(ft: FileType): Frag =
    val (variant, label) = ft match
      case FileType.Program  => ("indigo", "Program")
      case FileType.Copybook => ("purple", "Copybook")
      case FileType.JCL      => ("pink", "JCL")
      case FileType.Unknown  => ("gray", "Unknown")
    badge(label, variant)

  // ── Spinner ──────────────────────────────────────────────────────────────

  /** Loading spinner — emits `<ab-spinner size="..." label="...">`. */
  def spinner(size: String = "md", label: String = "Loading"): Frag =
    tag("ab-spinner")(attr("size") := size, attr("label") := label)

  /** Legacy alias used by views (wraps in htmx-indicator div for compatibility). */
  def loadingSpinner: Frag =
    div(cls := "htmx-indicator flex justify-center items-center p-4")(
      spinner()
    )

  // ── Progress bar ─────────────────────────────────────────────────────────

  /** Animated progress bar — emits `<ab-progress-bar value="current" max="total">`. */
  def progressBar(current: Int, total: Int): Frag =
    tag("ab-progress-bar")(
      attr("value") := current.toString,
      attr("max")   := total.toString,
    )

  // ── Summary card ─────────────────────────────────────────────────────────

  /** Icon summary card — kept as ScalaTags because it embeds an SVG icon inline. */
  def summaryCard(titleText: String, value: String, svgPath: String): Frag =
    div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6")(
      div(cls := "flex items-center justify-between")(
        div(
          p(cls := "text-sm font-medium text-gray-400")(titleText),
          p(cls := "mt-2 text-3xl font-semibold text-white")(value),
        ),
        svgIcon(svgPath, "size-10 text-indigo-400"),
      )
    )

  // ── Page header ──────────────────────────────────────────────────────────

  /** Standard page header with title, optional subtitle, and right-side action slot. */
  def pageHeader(title: String, subtitle: String = "", actions: Frag*): Frag =
    div(cls := "flex items-start justify-between gap-4 mb-6")(
      div(cls := "min-w-0")(
        h1(cls := "text-lg font-semibold text-white")(title),
        if subtitle.nonEmpty then p(cls := "mt-1 text-sm text-gray-400")(subtitle) else (),
      ),
      if actions.nonEmpty then
        div(cls := "flex items-center gap-2 flex-shrink-0")(actions*)
      else (),
    )

  // ── Empty state ──────────────────────────────────────────────────────────

  /** Legacy single-message empty state (plain Scalatags, no web component). */
  def emptyState(message: String): Frag =
    tag("ab-empty-state")(
      attr("headline") := message
    )

  /** Full empty state with headline, description, and optional action slot. */
  def emptyStateFull(headline: String, description: String = "", icon: String = "", action: Frag*): Frag =
    tag("ab-empty-state")(
      attr("headline")    := headline,
      attr("description") := description,
      if icon.nonEmpty then attr("icon") := icon else (),
      if action.nonEmpty then frag(action*) else (),
    )

  // ── Stat card ─────────────────────────────────────────────────────────────

  /** Compact metric card. `trend` is optional (e.g. "↑3 vs last week"). */
  def statCard(label: String, value: String, trend: String = "", trendPositive: Boolean = false): Frag =
    tag("ab-stat-card")(
      attr("label") := label,
      attr("value") := value,
      if trend.nonEmpty then attr("trend")         := trend else (),
      if trendPositive then attr("trend-positive") := "" else (),
    )

  // ── SVG icon utility ─────────────────────────────────────────────────────

  def svgIcon(pathD: String, classes: String): Frag =
    svg(
      cls                  := classes,
      viewBox              := "0 0 24 24",
      attr("fill")         := "none",
      attr("stroke")       := "currentColor",
      attr("stroke-width") := "1.5",
    )(
      path(
        d                       := pathD,
        attr("stroke-linecap")  := "round",
        attr("stroke-linejoin") := "round",
      )
    )

  // ── Design-system JS imports ─────────────────────────────────────────────

  /** Module scripts for all design-system `ab-*` components. Include once per page (or let the layout include them
    * globally). Views use `dsScripts` to include them.
    */
  val dsScripts: Seq[Frag] = Seq(
    JsResources.inlineModuleScript("/static/client/components/design-system/ab-badge.js"),
    JsResources.inlineModuleScript("/static/client/components/design-system/ab-spinner.js"),
    JsResources.inlineModuleScript("/static/client/components/design-system/ab-status.js"),
    JsResources.inlineModuleScript("/static/client/components/design-system/ab-card.js"),
    JsResources.inlineModuleScript("/static/client/components/design-system/ab-modal.js"),
    JsResources.inlineModuleScript("/static/client/components/design-system/ab-progress-bar.js"),
    JsResources.inlineModuleScript("/static/client/components/design-system/ab-data-table.js"),
    JsResources.inlineModuleScript("/static/client/components/design-system/ab-toast.js"),
    JsResources.inlineModuleScript("/static/client/components/design-system/ab-empty-state.js"),
    JsResources.inlineModuleScript("/static/client/components/design-system/ab-stat-card.js"),
    JsResources.inlineModuleScript("/static/client/components/design-system/ab-filter-bar.js"),
  )
