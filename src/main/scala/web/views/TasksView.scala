package web.views

import db.{ RunStatus, TaskRunRow }
import models.{ TaskStep, WorkflowDefinition }
import scalatags.Text.all.*

final case class TaskListItem(
  run: TaskRunRow,
  name: String,
  steps: List[TaskStep],
  workflowName: Option[String],
)

object TasksView:

  def tasksList(
    tasks: List[TaskListItem],
    workflows: List[WorkflowDefinition],
    flash: Option[String] = None,
  ): String =
    Layout.page("Tasks", "/tasks")(
      h1(cls := "text-2xl font-bold text-white mb-6")("Tasks"),
      flash.map(message =>
        div(cls := "mb-4 rounded-md bg-emerald-500/10 ring-1 ring-emerald-400/30 px-4 py-3 text-sm text-emerald-200")(
          message
        )
      ),
      div(cls := "grid grid-cols-1 lg:grid-cols-3 gap-6")(
        div(cls := "lg:col-span-2 bg-white/5 ring-1 ring-white/10 rounded-lg overflow-hidden")(
          div(cls := "px-6 py-4 border-b border-white/10")(
            h2(cls := "text-lg font-semibold text-white")("Recent Tasks")
          ),
          if tasks.isEmpty then Components.emptyState("No tasks yet. Create one from the form.")
          else tasksTable(tasks),
        ),
        createTaskForm(workflows),
      ),
    )

  def taskDetail(task: TaskListItem, flash: Option[String] = None): String =
    val run = task.run
    Layout.page(s"Task #${run.id}", s"/tasks/${run.id}")(
      flash.map(message =>
        div(cls := "mb-4 rounded-md bg-emerald-500/10 ring-1 ring-emerald-400/30 px-4 py-3 text-sm text-emerald-200")(
          message
        )
      ),
      div(cls := "flex items-center justify-between mb-6")(
        div(
          h1(cls := "text-2xl font-bold text-white")(task.name),
          p(cls := "text-sm text-gray-400 mt-1")(s"Task #${run.id}"),
        ),
        div(cls := "flex items-center gap-3")(
          if run.status == RunStatus.Running then
            form(action := s"/tasks/${run.id}/cancel", method := "post")(
              button(
                `type` := "submit",
                cls    := "rounded-md bg-amber-600 hover:bg-amber-500 px-3 py-2 text-xs font-semibold text-white",
              )("Cancel")
            )
          else frag(),
          if run.status == RunStatus.Failed then
            form(action := s"/tasks/${run.id}/retry", method := "post")(
              button(
                `type` := "submit",
                cls    := "rounded-md bg-emerald-600 hover:bg-emerald-500 px-3 py-2 text-xs font-semibold text-white",
              )("Retry")
            )
          else frag(),
          a(href := s"/reports?taskId=${run.id}", cls := "text-indigo-400 hover:text-indigo-300 text-sm font-medium")(
            "View Reports"
          ),
          a(href := "/tasks", cls := "text-indigo-400 hover:text-indigo-300 text-sm font-medium")("Back to Tasks"),
        ),
      ),
      div(
        attr("hx-ext")      := "sse",
        attr("sse-connect") := s"/api/tasks/${run.id}/progress",
      )(
        div(
          id               := "task-progress-content",
          attr("sse-swap") := "step-progress",
          attr("hx-swap")  := "innerHTML",
        )(
          taskProgressContent(task)
        )
      ),
    )

  def taskProgressContent(task: TaskListItem): Frag =
    val run = task.run
    div(cls := "grid grid-cols-1 lg:grid-cols-3 gap-6")(
      div(cls := "lg:col-span-2 bg-white/5 ring-1 ring-white/10 rounded-lg p-6")(
        h2(cls := "text-lg font-semibold text-white mb-4")("Step Progress"),
        if task.steps.isEmpty then Components.emptyState("No workflow steps available for this task.")
        else
          ul(cls := "space-y-3")(
            task.steps.zipWithIndex.map {
              case (step, idx) =>
                val status = stepStatus(run, task.steps, idx)
                li(
                  id  := s"step-row-${slug(step)}",
                  cls := "flex items-center justify-between rounded-md bg-white/5 px-4 py-3",
                )(
                  span(cls := "text-sm font-medium text-white")(step),
                  span(stepBadgeClasses(status))(status),
                )
            }
          ),
      ),
      div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6 space-y-4")(
        h2(cls := "text-lg font-semibold text-white")("Task Metadata"),
        metadataRow("Status", Components.statusBadge(run.status)),
        metadataRow("Workflow", span(cls := "text-sm text-gray-300")(task.workflowName.getOrElse("-"))),
        metadataRow("Current Step", span(cls := "text-sm text-gray-300")(run.currentPhase.getOrElse("-"))),
        metadataRow("Steps", span(cls := "text-sm text-gray-300")(stepProgressLabel(task))),
      ),
    )

  private def tasksTable(tasks: List[TaskListItem]): Frag =
    div(cls := "overflow-x-auto")(
      table(cls := "min-w-full divide-y divide-white/10")(
        thead(cls := "bg-white/5")(
          tr(
            th(cls := "py-3 pl-6 pr-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")("Task"),
            th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")("Status"),
            th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")("Workflow"),
            th(
              cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400"
            )("Current Step"),
            th(cls := "relative py-3 pl-3 pr-6"),
          )
        ),
        tbody(cls := "divide-y divide-white/5")(
          tasks.map { item =>
            tr(cls := "hover:bg-white/5")(
              td(cls := "whitespace-nowrap py-4 pl-6 pr-3 text-sm font-medium text-white")(
                a(href := s"/tasks/${item.run.id}", cls := "text-indigo-400 hover:text-indigo-300")(item.name)
              ),
              td(cls := "whitespace-nowrap px-3 py-4 text-sm")(Components.statusBadge(item.run.status)),
              td(cls := "whitespace-nowrap px-3 py-4 text-sm text-gray-300")(item.workflowName.getOrElse("-")),
              td(cls := "whitespace-nowrap px-3 py-4 text-sm text-gray-400")(item.run.currentPhase.getOrElse("-")),
              td(cls := "relative whitespace-nowrap py-4 pl-3 pr-6 text-right text-sm")(
                a(href := s"/tasks/${item.run.id}", cls := "text-indigo-400 hover:text-indigo-300 font-medium")("View")
              ),
            )
          }
        ),
      )
    )

  private def createTaskForm(workflows: List[WorkflowDefinition]): Frag =
    div(cls := "bg-white/5 ring-1 ring-white/10 rounded-lg p-6")(
      h2(cls := "text-lg font-semibold text-white mb-4")("Create Task"),
      form(action := "/tasks", method := "post", cls := "space-y-4")(
        field("Task Name", "name", "my-task"),
        textAreaField("Description (optional)", "description", "What should this task do?"),
        div(
          label(cls := "block text-sm text-gray-300 mb-1", `for` := "workflowId")("Workflow"),
          select(
            id   := "workflowId",
            name := "workflowId",
            cls  := "w-full rounded-md bg-black/20 ring-1 ring-white/10 px-3 py-2 text-sm text-white",
          )(
            workflows.map { wf =>
              option(value := wf.id.map(_.toString).getOrElse(""), wf.name)
            }
          ),
        ),
        button(
          `type` := "submit",
          cls    := "w-full rounded-md bg-indigo-600 hover:bg-indigo-500 px-3 py-2 text-sm font-semibold text-white",
        )("Create Task"),
      ),
    )

  private def field(labelText: String, nameAttr: String, placeholderText: String): Frag =
    div(
      label(cls := "block text-sm text-gray-300 mb-1", `for` := nameAttr)(labelText),
      input(
        id          := nameAttr,
        name        := nameAttr,
        placeholder := placeholderText,
        cls         := "w-full rounded-md bg-black/20 ring-1 ring-white/10 px-3 py-2 text-sm text-white placeholder:text-gray-500",
      ),
    )

  private def textAreaField(labelText: String, nameAttr: String, placeholderText: String): Frag =
    div(
      label(cls := "block text-sm text-gray-300 mb-1", `for` := nameAttr)(labelText),
      textarea(
        id          := nameAttr,
        name        := nameAttr,
        placeholder := placeholderText,
        rows        := 4,
        cls         := "w-full rounded-md bg-black/20 ring-1 ring-white/10 px-3 py-2 text-sm text-white placeholder:text-gray-500",
      )(),
    )

  private def metadataRow(labelText: String, valueNode: Frag): Frag =
    div(
      span(cls := "block text-xs uppercase tracking-wide text-gray-500 mb-1")(labelText),
      valueNode,
    )

  private def stepStatus(run: TaskRunRow, steps: List[TaskStep], index: Int): String =
    val currentIndex = run.currentPhase.flatMap(phase =>
      steps.indexWhere(_ == phase) match
        case -1 => None
        case n  => Some(n)
    )

    run.status match
      case RunStatus.Completed =>
        "Completed"
      case RunStatus.Failed    =>
        currentIndex match
          case Some(i) if i == index => "Failed"
          case Some(i) if index < i  => "Completed"
          case _                     => "Pending"
      case RunStatus.Running   =>
        currentIndex match
          case Some(i) if i == index => "Running"
          case Some(i) if index < i  => "Completed"
          case _                     => "Pending"
      case RunStatus.Paused    =>
        currentIndex match
          case Some(i) if i == index => "Paused"
          case Some(i) if index < i  => "Completed"
          case _                     => "Pending"
      case RunStatus.Cancelled =>
        currentIndex match
          case Some(i) if index <= i => "Cancelled"
          case _                     => "Pending"
      case RunStatus.Pending   =>
        "Pending"

  private def stepBadgeClasses(status: String): Modifier =
    status match
      case "Completed" =>
        cls := "inline-flex items-center rounded-md bg-emerald-500/10 px-2 py-1 text-xs font-medium text-emerald-300 ring-1 ring-emerald-400/20"
      case "Running"   =>
        cls := "inline-flex items-center rounded-md bg-indigo-500/10 px-2 py-1 text-xs font-medium text-indigo-300 ring-1 ring-indigo-400/20"
      case "Failed"    =>
        cls := "inline-flex items-center rounded-md bg-rose-500/10 px-2 py-1 text-xs font-medium text-rose-300 ring-1 ring-rose-400/20"
      case "Paused"    =>
        cls := "inline-flex items-center rounded-md bg-amber-500/10 px-2 py-1 text-xs font-medium text-amber-300 ring-1 ring-amber-400/20"
      case "Cancelled" =>
        cls := "inline-flex items-center rounded-md bg-amber-500/10 px-2 py-1 text-xs font-medium text-amber-300 ring-1 ring-amber-400/20"
      case _           =>
        cls := "inline-flex items-center rounded-md bg-gray-500/10 px-2 py-1 text-xs font-medium text-gray-300 ring-1 ring-gray-400/20"

  private def stepProgressLabel(task: TaskListItem): String =
    val total     = task.steps.size
    val completed = task.steps.indices.count(idx => stepStatus(task.run, task.steps, idx) == "Completed")
    s"$completed/$total completed"

  private def slug(value: String): String =
    value.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")
