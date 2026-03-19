package shared.web

import db.{ FileType, RunStatus }
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

  // ── Empty state ──────────────────────────────────────────────────────────

  def emptyState(message: String): Frag =
    div(cls := "text-center py-12")(
      svgIcon(
        "M2.25 12.75V12A2.25 2.25 0 0 1 4.5 9.75h15A2.25 2.25 0 0 1 21.75 12v.75m-8.69-6.44-2.12-2.12a1.5 1.5 0 0 0-1.061-.44H4.5A2.25 2.25 0 0 0 2.25 6v12a2.25 2.25 0 0 0 2.25 2.25h15A2.25 2.25 0 0 0 21.75 18V9a2.25 2.25 0 0 0-2.25-2.25h-5.379a1.5 1.5 0 0 1-1.06-.44Z",
        "mx-auto size-12 text-gray-500 mb-4",
      ),
      p(cls := "text-sm text-gray-400")(message),
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
  )
