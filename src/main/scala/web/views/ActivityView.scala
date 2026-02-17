package web.views

import models.{ ActivityEvent, ActivityEventType }
import scalatags.Text.all.*

object ActivityView:

  def timeline(events: List[ActivityEvent]): String =
    Layout.page("Activity", "/activity")(
      div(cls := "flex items-center justify-between mb-6")(
        h1(cls := "text-2xl font-bold text-white")("Activity Timeline"),
        div(cls := "flex items-center gap-2")(
          span(
            cls := "inline-flex items-center rounded-md bg-green-500/10 px-2 py-1 text-xs font-medium text-green-400 ring-1 ring-inset ring-green-500/20"
          )("Live")
        ),
      ),
      // Filter bar
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-4 mb-6")(
        div(cls := "flex flex-wrap items-center gap-3")(
          span(cls := "text-sm font-medium text-gray-400")("Filter:"),
          filterButton("all", "All Events", selected = true),
          filterButton("run_started", "Run Started"),
          filterButton("run_completed", "Run Completed"),
          filterButton("run_failed", "Run Failed"),
          filterButton("agent_assigned", "Agent Assigned"),
          filterButton("message_sent", "Message Sent"),
          filterButton("config_changed", "Config Changed"),
        )
      ),
      // Timeline container — Lit component handles live updates and filtering
      tag("activity-timeline")(id := "activity-timeline")(
        eventsFragmentFrag(events)
      ),
      JsResources.inlineModuleScript("/static/client/components/activity-timeline.js"),
    )

  def eventsFragment(events: List[ActivityEvent]): String =
    eventsFragmentFrag(events).render

  private def eventsFragmentFrag(events: List[ActivityEvent]): Frag =
    div(id := "activity-events", cls := "space-y-3")(
      if events.isEmpty then
        Seq(
          div(cls := "text-center py-12 text-gray-500")(
            p(cls := "text-sm")("No activity events yet. Events will appear here as they happen.")
          )
        )
      else events.map(eventCard)
    )

  def singleEventFragment(event: ActivityEvent): String =
    eventCard(event).render

  private def filterButton(value: String, label: String, selected: Boolean = false): Frag =
    val baseCls     =
      "activity-filter cursor-pointer rounded-md px-2.5 py-1 text-xs font-medium ring-1 ring-inset transition-colors"
    val activeCls   = s"$baseCls bg-indigo-500/10 text-indigo-400 ring-indigo-500/20"
    val inactiveCls = s"$baseCls bg-white/5 text-gray-400 ring-white/10 hover:bg-white/10"
    span(
      cls                 := (if selected then activeCls else inactiveCls),
      attr("data-filter") := value,
      attr("onclick")     := "window.__activityFilterClick && window.__activityFilterClick(this)",
    )(label)

  private def eventCard(event: ActivityEvent): Frag =
    val (iconColor, iconPath) = eventIcon(event.eventType)
    div(
      cls                     := "activity-event flex items-start gap-3 rounded-lg bg-white/5 ring-1 ring-white/10 p-4",
      attr("data-event-type") := toDbEventType(event.eventType),
    )(
      // Icon
      div(cls := s"flex-shrink-0 rounded-full p-1.5 $iconColor")(
        tag("svg")(
          attr("xmlns")        := "http://www.w3.org/2000/svg",
          attr("fill")         := "none",
          attr("viewBox")      := "0 0 24 24",
          attr("stroke-width") := "1.5",
          attr("stroke")       := "currentColor",
          cls                  := "size-5",
        )(
          tag("path")(
            attr("stroke-linecap")  := "round",
            attr("stroke-linejoin") := "round",
            attr("d")               := iconPath,
          )
        )
      ),
      // Content
      div(cls := "flex-1 min-w-0")(
        div(cls := "flex items-center justify-between")(
          span(cls := "text-sm font-medium text-white")(event.summary),
          span(cls := "text-xs text-gray-500")(formatTime(event.createdAt)),
        ),
        div(cls := "mt-1 flex items-center gap-2")(
          span(
            cls := "inline-flex items-center rounded-md bg-white/5 px-1.5 py-0.5 text-xs text-gray-400 ring-1 ring-inset ring-white/10"
          )(eventTypeLabel(event.eventType)),
          event.agentName.map { name =>
            span(
              cls := "inline-flex items-center rounded-md bg-purple-500/10 px-1.5 py-0.5 text-xs text-purple-400 ring-1 ring-inset ring-purple-500/20"
            )(name)
          },
          event.runId.map { rid =>
            a(
              attr("href") := s"/runs/$rid",
              cls          := "inline-flex items-center rounded-md bg-blue-500/10 px-1.5 py-0.5 text-xs text-blue-400 ring-1 ring-inset ring-blue-500/20 hover:bg-blue-500/20",
            )(s"Run #$rid")
          },
          span(cls := "text-xs text-gray-500")(event.source),
        ),
      ),
    )

  private def eventIcon(eventType: ActivityEventType): (String, String) = eventType match
    case ActivityEventType.RunStarted    =>
      (
        "bg-green-500/10 text-green-400",
        "M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.347a1.125 1.125 0 0 1 0 1.972l-11.54 6.347a1.125 1.125 0 0 1-1.667-.986V5.653Z",
      )
    case ActivityEventType.RunCompleted  =>
      ("bg-emerald-500/10 text-emerald-400", "M9 12.75 11.25 15 15 9.75M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z")
    case ActivityEventType.RunFailed     =>
      (
        "bg-red-500/10 text-red-400",
        "M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z",
      )
    case ActivityEventType.AgentAssigned =>
      (
        "bg-purple-500/10 text-purple-400",
        "M15.75 6a3.75 3.75 0 1 1-7.5 0 3.75 3.75 0 0 1 7.5 0ZM4.501 20.118a7.5 7.5 0 0 1 14.998 0A17.933 17.933 0 0 1 12 21.75c-2.676 0-5.216-.584-7.499-1.632Z",
      )
    case ActivityEventType.MessageSent   =>
      (
        "bg-blue-500/10 text-blue-400",
        "M8.625 12a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Zm0 0H8.25m4.125 0a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Zm0 0H12m4.125 0a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Zm0 0h-.375M21 12c0 4.556-4.03 8.25-9 8.25a9.764 9.764 0 0 1-2.555-.337A5.972 5.972 0 0 1 5.41 20.97a5.969 5.969 0 0 1-.474-.065 4.48 4.48 0 0 0 .978-2.025c.09-.457-.133-.901-.467-1.226C3.93 16.178 3 14.189 3 12c0-4.556 4.03-8.25 9-8.25s9 3.694 9 8.25Z",
      )
    case ActivityEventType.ConfigChanged =>
      (
        "bg-amber-500/10 text-amber-400",
        "M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.325.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 0 1 1.37.49l1.296 2.247a1.125 1.125 0 0 1-.26 1.431l-1.003.827c-.293.241-.438.613-.43.992a7.723 7.723 0 0 1 0 .255c-.008.378.137.75.43.991l1.004.827c.424.35.534.955.26 1.43l-1.298 2.247a1.125 1.125 0 0 1-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.47 6.47 0 0 1-.22.128c-.331.183-.581.495-.644.869l-.213 1.281c-.09.543-.56.94-1.11.94h-2.594c-.55 0-1.019-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 0 1-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 0 1-1.369-.49l-1.297-2.247a1.125 1.125 0 0 1 .26-1.431l1.004-.827c.292-.24.437-.613.43-.991a6.932 6.932 0 0 1 0-.255c.007-.38-.138-.751-.43-.992l-1.004-.827a1.125 1.125 0 0 1-.26-1.43l1.297-2.247a1.125 1.125 0 0 1 1.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.086.22-.128.332-.183.582-.495.644-.869l.214-1.28Z M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z",
      )

  private def eventTypeLabel(eventType: ActivityEventType): String = eventType match
    case ActivityEventType.RunStarted    => "Run Started"
    case ActivityEventType.RunCompleted  => "Run Completed"
    case ActivityEventType.RunFailed     => "Run Failed"
    case ActivityEventType.AgentAssigned => "Agent Assigned"
    case ActivityEventType.MessageSent   => "Message Sent"
    case ActivityEventType.ConfigChanged => "Config Changed"

  private def toDbEventType(eventType: ActivityEventType): String = eventType match
    case ActivityEventType.RunStarted    => "run_started"
    case ActivityEventType.RunCompleted  => "run_completed"
    case ActivityEventType.RunFailed     => "run_failed"
    case ActivityEventType.AgentAssigned => "agent_assigned"
    case ActivityEventType.MessageSent   => "message_sent"
    case ActivityEventType.ConfigChanged => "config_changed"

  private def formatTime(instant: java.time.Instant): String =
    val formatter = java.time.format.DateTimeFormatter
      .ofPattern("MMM d, HH:mm:ss")
      .withZone(java.time.ZoneId.systemDefault())
    formatter.format(instant)
