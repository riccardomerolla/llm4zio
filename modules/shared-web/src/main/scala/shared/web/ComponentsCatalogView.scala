package shared.web

import scalatags.Text.all.*
import scalatags.Text.tags2.section

/** Dev catalog for design-system components. Accessible at GET /components in development.
  */
object ComponentsCatalogView:

  def page(): String =
    Layout.page("Design System Catalog", "/components")(
      frag(
        Components.dsScripts*
      ),
      h1(cls := "text-2xl font-bold text-white mb-2")("Design System Catalog"),
      p(cls := "text-sm text-gray-400 mb-8")(
        "Visual reference for all ",
        code(cls := "text-indigo-300")("ab-*"),
        " web components. Use this page to spot regressions.",
      ),

      // ── Indicators ─────────────────────────────────────────────────────────
      section(
        h2(cls := "text-lg font-semibold text-white mb-4 border-b border-white/10 pb-2")("Indicators"),

        // ab-badge
        div(cls := "mb-8")(
          h3(cls := "text-sm font-semibold text-gray-300 mb-3")("ab-badge"),
          div(cls := "flex flex-wrap gap-2")(
            tag("ab-badge")(attr("text") := "Default", attr("variant") := "default"),
            tag("ab-badge")(attr("text") := "Success", attr("variant") := "success"),
            tag("ab-badge")(attr("text") := "Warning", attr("variant") := "warning"),
            tag("ab-badge")(attr("text") := "Error", attr("variant")   := "error"),
            tag("ab-badge")(attr("text") := "Info", attr("variant")    := "info"),
            tag("ab-badge")(attr("text") := "Indigo", attr("variant")  := "indigo"),
            tag("ab-badge")(attr("text") := "Purple", attr("variant")  := "purple"),
            tag("ab-badge")(attr("text") := "Pink", attr("variant")    := "pink"),
            tag("ab-badge")(attr("text") := "Gray", attr("variant")    := "gray"),
            tag("ab-badge")(attr("text") := "Amber", attr("variant")   := "amber"),
          ),
          p(cls := "mt-2 text-xs text-gray-500")(raw("""Props: <code>text</code>, <code>variant</code>""")),
        ),

        // ab-spinner
        div(cls := "mb-8")(
          h3(cls := "text-sm font-semibold text-gray-300 mb-3")("ab-spinner"),
          div(cls := "flex items-center gap-6")(
            div(cls := "flex flex-col items-center gap-1")(
              tag("ab-spinner")(attr("size") := "sm"),
              span(cls := "text-xs text-gray-500")("sm"),
            ),
            div(cls := "flex flex-col items-center gap-1")(
              tag("ab-spinner")(attr("size") := "md"),
              span(cls := "text-xs text-gray-500")("md"),
            ),
            div(cls := "flex flex-col items-center gap-1")(
              tag("ab-spinner")(attr("size") := "lg"),
              span(cls := "text-xs text-gray-500")("lg"),
            ),
          ),
          p(cls := "mt-2 text-xs text-gray-500")(raw("""Props: <code>size</code> (sm/md/lg), <code>label</code>""")),
        ),

        // ab-status
        div(cls := "mb-8")(
          h3(cls := "text-sm font-semibold text-gray-300 mb-3")("ab-status"),
          div(cls := "flex flex-wrap gap-4")(
            tag("ab-status")(attr("status") := "healthy"),
            tag("ab-status")(attr("status") := "degraded"),
            tag("ab-status")(attr("status") := "down"),
            tag("ab-status")(attr("status") := "unknown"),
            tag("ab-status")(attr("status") := "healthy", attr("label")  := "API Gateway"),
            tag("ab-status")(attr("status") := "degraded", attr("label") := "Memory Pressure"),
          ),
          p(
            cls := "mt-2 text-xs text-gray-500"
          )(raw("""Props: <code>status</code> (healthy/degraded/down/unknown), <code>label</code>""")),
        ),
      ),

      // ── Containers ─────────────────────────────────────────────────────────
      section(
        h2(cls := "text-lg font-semibold text-white mb-4 border-b border-white/10 pb-2")("Containers"),

        // ab-card
        div(cls := "mb-8")(
          h3(cls := "text-sm font-semibold text-gray-300 mb-3")("ab-card"),
          div(cls := "grid grid-cols-1 sm:grid-cols-2 gap-4")(
            tag("ab-card")(attr("title") := "Card with title")(
              p(cls := "text-sm text-gray-300")("Slot content goes here. Render anything inside the card body.")
            ),
            tag("ab-card")(
              p(cls := "text-sm text-gray-300")("Card without title — only slot content.")
            ),
          ),
          p(cls := "mt-2 text-xs text-gray-500")(raw("""Props: <code>title</code> (optional). Slot: default.""")),
        ),

        // ab-modal
        div(cls := "mb-8")(
          h3(cls := "text-sm font-semibold text-gray-300 mb-3")("ab-modal"),
          div(cls := "flex gap-3")(
            button(
              `type`          := "button",
              cls             := "rounded bg-indigo-600 px-3 py-1.5 text-sm text-white hover:bg-indigo-500",
              attr("onclick") := "document.getElementById('demo-modal').open=true",
            )("Open modal")
          ),
          tag("ab-modal")(
            attr("id")    := "demo-modal",
            attr("title") := "Example Modal",
          )(
            p(cls := "text-sm text-gray-300 mb-4")("Modal body content via slot. Click backdrop or ✕ to close."),
            button(
              `type`          := "button",
              cls             := "rounded bg-white/10 px-3 py-1.5 text-sm text-white hover:bg-white/20",
              attr("onclick") := "document.getElementById('demo-modal').open=false",
            )("Close"),
          ),
          p(cls := "mt-2 text-xs text-gray-500")(raw(
            """Props: <code>open</code> (Boolean, reflected), <code>title</code>. Event: <code>ab-close</code>. Slot: default."""
          )),
        ),
      ),

      // ── Data Display ───────────────────────────────────────────────────────
      section(
        h2(cls := "text-lg font-semibold text-white mb-4 border-b border-white/10 pb-2")("Data Display"),

        // ab-progress-bar
        div(cls := "mb-8")(
          h3(cls := "text-sm font-semibold text-gray-300 mb-3")("ab-progress-bar"),
          div(cls := "flex flex-col gap-3 max-w-sm")(
            tag("ab-progress-bar")(attr("value") := "0", attr("max")   := "100"),
            tag("ab-progress-bar")(attr("value") := "34", attr("max")  := "100"),
            tag("ab-progress-bar")(attr("value") := "72", attr("max")  := "100"),
            tag("ab-progress-bar")(attr("value") := "100", attr("max") := "100"),
            tag("ab-progress-bar")(attr("value") := "7", attr("max")   := "20", attr("label") := "Files"),
          ),
          p(
            cls := "mt-2 text-xs text-gray-500"
          )(raw("""Props: <code>value</code>, <code>max</code>, <code>label</code>""")),
        ),

        // ab-data-table
        div(cls := "mb-8")(
          h3(cls := "text-sm font-semibold text-gray-300 mb-3")("ab-data-table"),
          tag("ab-data-table")(
            attr("id") := "demo-table"
          ),
          script(attr("type") := "module")(
            raw("""
              const t = document.getElementById('demo-table');
              t.headers = ['ID', 'Agent', 'Status', 'Duration'];
              t.rows    = [
                ['#1', 'planner-agent', 'Completed', '1.2 s'],
                ['#2', 'code-agent',    'Running',   '0.4 s'],
                ['#3', 'review-agent',  'Failed',    '0.8 s'],
                ['#4', 'test-agent',    'Pending',   '—'],
              ];
            """)
          ),
          p(cls := "mt-2 text-xs text-gray-500")(raw(
            """Props (JS only): <code>headers</code> (Array&lt;String&gt;), <code>rows</code> (Array&lt;Array&lt;String&gt;&gt;). Click header to sort. Event: <code>ab-sort</code>."""
          )),
        ),
      ),

      // ── Feedback ───────────────────────────────────────────────────────────
      section(
        h2(cls := "text-lg font-semibold text-white mb-4 border-b border-white/10 pb-2")("Feedback"),

        // ab-toast
        div(cls := "mb-8")(
          h3(cls := "text-sm font-semibold text-gray-300 mb-3")("ab-toast"),
          div(cls := "flex flex-wrap gap-2")(
            button(
              `type`          := "button",
              cls             := "rounded bg-green-600/20 px-3 py-1.5 text-sm text-green-300 ring-1 ring-green-500/30 hover:bg-green-600/30",
              attr("onclick") := "AbToast.show({ type: 'success', message: 'Action completed successfully!' })",
            )("Success toast"),
            button(
              `type`          := "button",
              cls             := "rounded bg-red-600/20 px-3 py-1.5 text-sm text-red-300 ring-1 ring-red-500/30 hover:bg-red-600/30",
              attr("onclick") := "AbToast.show({ type: 'error', message: 'Something went wrong.' })",
            )("Error toast"),
            button(
              `type`          := "button",
              cls             := "rounded bg-yellow-600/20 px-3 py-1.5 text-sm text-yellow-300 ring-1 ring-yellow-500/30 hover:bg-yellow-600/30",
              attr("onclick") := "AbToast.show({ type: 'warning', message: 'This action has side effects.' })",
            )("Warning toast"),
            button(
              `type`          := "button",
              cls             := "rounded bg-blue-600/20 px-3 py-1.5 text-sm text-blue-300 ring-1 ring-blue-500/30 hover:bg-blue-600/30",
              attr("onclick") := "AbToast.show({ type: 'info', message: 'New deployment available.', duration: 8000 })",
            )("Info toast (8 s)"),
            button(
              `type`          := "button",
              cls             := "rounded bg-white/5 px-3 py-1.5 text-sm text-gray-300 ring-1 ring-white/10 hover:bg-white/10",
              attr("onclick") := "AbToast.show({ type: 'info', message: 'Sticky — dismiss manually.', duration: 0 })",
            )("Sticky toast"),
          ),
          p(cls := "mt-2 text-xs text-gray-500")(raw(
            """<code>AbToast.show({ type, message, duration })</code> — global singleton. Registers as <code>&lt;ab-toast&gt;</code>."""
          )),
          tag("ab-toast"),
        ),
      ),
    )
