package shared.web

import scalatags.Text.all.*
import scalatags.Text.svgAttrs.{ d, viewBox }
import scalatags.Text.svgTags.{ path, svg }
import taskrun.entity.RunStatus

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

  // ── Interactive card ─────────────────────────────────────────────────────

  /** Navigable card with hover lift effect, focus ring, and transition. Emits an `<a>` so the entire card is
    * keyboard-accessible.
    */
  def interactiveCard(href: String)(content: Frag*): Frag =
    a(
      cls          := "group block rounded-xl border border-white/10 bg-slate-800/70 p-5 " +
        "transition-all duration-150 hover:-translate-y-0.5 hover:shadow-lg " +
        "hover:shadow-black/30 hover:border-white/20 " +
        "focus:outline-none focus:ring-2 focus:ring-cyan-500 focus:ring-offset-2 focus:ring-offset-slate-900",
      attr("href") := href,
    )(content*)

  // ── Button helpers ────────────────────────────────────────────────────────

  /** Primary action button — indigo bg, white text, consistent hover/active states. */
  def primaryButton(text: String, tpe: String = "button"): Frag =
    button(
      `type` := tpe,
      cls    := "rounded-md bg-indigo-600 px-4 py-2 text-sm font-semibold text-white " +
        "transition-all duration-150 hover:bg-indigo-500 active:scale-95 " +
        "focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2 focus:ring-offset-slate-900 " +
        "disabled:cursor-not-allowed disabled:opacity-50",
    )(text)

  /** Secondary button — border outline, transparent bg, hover fill. */
  def secondaryButton(text: String, tpe: String = "button"): Frag =
    button(
      `type` := tpe,
      cls    := "rounded-md border border-white/20 bg-transparent px-4 py-2 text-sm font-semibold text-slate-200 " +
        "transition-all duration-150 hover:bg-white/10 active:scale-95 " +
        "focus:outline-none focus:ring-2 focus:ring-cyan-500 focus:ring-offset-2 focus:ring-offset-slate-900",
    )(text)

  /** Ghost button — no border, subtle hover bg. */
  def ghostButton(text: String, tpe: String = "button"): Frag =
    button(
      `type` := tpe,
      cls    := "rounded-md bg-transparent px-4 py-2 text-sm font-semibold text-slate-300 " +
        "transition-all duration-150 hover:bg-white/10 hover:text-white active:scale-95 " +
        "focus:outline-none focus:ring-2 focus:ring-cyan-500 focus:ring-offset-2",
    )(text)

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
    JsResources.inlineModuleScript("/static/client/components/ab-nav-dropdown.js"),
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
