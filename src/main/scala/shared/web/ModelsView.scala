package shared.web

import config.control.{ ModelRegistryResponse, ProviderAvailability, ProviderProbeStatus }
import scalatags.Text.all.*

object ModelsView:

  def page(
    registry: ModelRegistryResponse,
    statuses: List[ProviderProbeStatus],
  ): String =
    val statusMap = statuses.map(s => s.provider -> s).toMap

    Layout.page("Models", "/models")(
      div(cls := "space-y-6")(
        h1(cls := "text-2xl font-bold text-white")("Models"),
        p(cls := "text-sm text-slate-300")(
          "Available models grouped by provider. Configure primary model and fallback chain in Settings."
        ),
        div(cls := "space-y-4")(
          registry.providers.map { group =>
            val status = statusMap.get(group.provider)
            div(cls := "rounded-lg border border-white/10 bg-slate-900/70 p-5")(
              div(cls := "mb-3 flex items-center justify-between")(
                h2(cls := "text-lg font-semibold text-white")(group.provider.toString),
                statusBadge(status),
              ),
              p(
                cls := "mb-3 text-xs text-slate-400"
              )(status.map(_.statusMessage).getOrElse("No health probe available")),
              table(cls := "min-w-full text-left text-sm text-slate-200")(
                thead(
                  tr(
                    th(cls := "py-2 pr-4 text-xs font-semibold uppercase text-slate-400")("Model"),
                    th(cls := "py-2 pr-4 text-xs font-semibold uppercase text-slate-400")("Context"),
                    th(cls := "py-2 pr-4 text-xs font-semibold uppercase text-slate-400")("Capabilities"),
                  )
                ),
                tbody(
                  group.models.map { model =>
                    tr(cls := "border-t border-white/5")(
                      td(cls := "py-2 pr-4 font-mono text-xs")(model.modelId),
                      td(cls := "py-2 pr-4")(model.contextWindow.toString),
                      td(cls := "py-2 pr-4")(model.capabilities.toList.map(_.toString).sorted.mkString(", ")),
                    )
                  }
                ),
              ),
            )
          }
        ),
      )
    )

  def statusBadge(status: Option[ProviderProbeStatus]): Frag =
    val (label, classes) = status match
      case Some(s) =>
        s.availability match
          case ProviderAvailability.Healthy   => ("Healthy", "bg-emerald-500/20 text-emerald-300 border-emerald-500/40")
          case ProviderAvailability.Degraded  => ("Degraded", "bg-amber-500/20 text-amber-300 border-amber-500/40")
          case ProviderAvailability.Unhealthy => ("Unhealthy", "bg-rose-500/20 text-rose-300 border-rose-500/40")
          case ProviderAvailability.Unknown   => ("Unknown", "bg-slate-500/20 text-slate-300 border-slate-500/40")
      case None    =>
        ("Unknown", "bg-slate-500/20 text-slate-300 border-slate-500/40")

    span(cls := s"rounded-full border px-2 py-1 text-xs font-semibold $classes")(label)
