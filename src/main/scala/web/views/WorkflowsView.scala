package web.views

import models.{ AgentInfo, MigrationStep, WorkflowDefinition, WorkflowStepAgent }
import scalatags.Text.all.*

object WorkflowsView:

  def list(
    workflows: List[WorkflowDefinition],
    availableAgents: List[AgentInfo],
    flash: Option[String] = None,
  ): String =
    val agentMap = availableAgents.map(agent => agent.name -> agent.displayName).toMap
    Layout.page("Workflows", "/workflows")(
      div(cls := "space-y-6")(
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          div(cls := "flex flex-wrap items-center justify-between gap-3")(
            div(
              h1(cls := "text-2xl font-bold text-white")("Workflows"),
              p(cls := "mt-1 text-sm text-slate-300")("Define ordered migration steps, with optional per-step agents"),
            ),
            a(
              href := "/workflows/new",
              cls  := "rounded-md border border-emerald-400/30 bg-emerald-500/20 px-3 py-2 text-sm font-semibold text-emerald-200 hover:bg-emerald-500/30",
            )("Create Workflow"),
          )
        ),
        flash.map { message =>
          div(cls := "rounded-md border border-emerald-500/30 bg-emerald-500/10 p-4")(
            p(cls := "text-sm text-emerald-300")(message)
          )
        },
        div(
          id  := "workflow-feedback",
          cls := "hidden rounded-md border border-emerald-500/30 bg-emerald-500/10 p-4",
        )(
          p(cls := "text-sm text-emerald-300", id := "workflow-feedback-text")()
        ),
        div(cls := "overflow-hidden rounded-xl border border-white/10 bg-slate-900/60")(
          table(cls := "min-w-full divide-y divide-white/10")(
            thead(cls := "bg-white/5")(
              tr(
                th(
                  cls := "py-3 pl-6 pr-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400"
                )("Name"),
                th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")("Steps"),
                th(cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400")(
                  "Description"
                ),
                th(
                  cls := "px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide text-gray-400"
                )("Step Agents"),
                th(
                  cls := "relative py-3 pl-3 pr-6 text-right text-xs font-semibold uppercase tracking-wide text-gray-400"
                )(
                  "Actions"
                ),
              )
            ),
            tbody(cls := "divide-y divide-white/5")(
              workflows.map(workflow => workflowRow(workflow, agentMap))
            ),
          )
        ),
      )
    )

  def form(
    title: String,
    formAction: String,
    workflow: WorkflowDefinition,
    availableAgents: List[AgentInfo],
    flash: Option[String] = None,
  ): String =
    val orderedCsv     = workflow.steps.map(_.toString).mkString(",")
    val stepAgentsJson = stepAgentsToJson(workflow.stepAgents)
    val agentOptions   = availableAgents.filter(_.usesAI).sortBy(_.displayName.toLowerCase)
    val stepAgentMap   = workflow.stepAgents.map(assign => assign.step -> assign.agentName).toMap

    Layout.page(title, "/workflows")(
      div(cls := "mx-auto max-w-6xl space-y-5")(
        a(
          href := "/workflows",
          cls  := "text-sm font-medium text-indigo-300 hover:text-indigo-200",
        )("← Back to Workflows"),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/80 px-5 py-4")(
          h1(cls := "text-2xl font-bold text-white")(title),
          p(cls := "mt-1 text-sm text-slate-300")(
            "Select steps, order them, assign optional agents per step, and preview the workflow graph."
          ),
        ),
        flash.map { message =>
          div(cls := "rounded-md border border-amber-500/30 bg-amber-500/10 p-4")(
            p(cls := "text-sm text-amber-200")(message)
          )
        },
        div(cls := "grid grid-cols-1 gap-5 lg:grid-cols-2")(
          tag("form")(method := "post", action := formAction, cls := "space-y-5")(
            div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5")(
              textField("name", "Name", workflow.name, required = true),
              textField("description", "Description", workflow.description.getOrElse(""), required = false),
              input(`type`               := "hidden", id := "orderedSteps", name := "orderedSteps", value := orderedCsv),
              input(
                `type`                   := "hidden",
                id                       := "stepAgentsJson",
                name                     := "stepAgentsJson",
                scalatags.Text.all.value := stepAgentsJson,
              ),
              div(cls := "mt-4")(
                p(cls := "mb-2 text-sm font-semibold text-slate-200")("Steps"),
                div(cls := "grid grid-cols-1 gap-2 sm:grid-cols-2")(
                  MigrationStep.values.toList.map { step =>
                    val selected = workflow.steps.contains(step)
                    label(
                      cls := "flex items-center gap-2 rounded-md border border-white/10 bg-slate-800/60 px-3 py-2 text-sm text-slate-100"
                    )(
                      input(
                        `type`                   := "checkbox",
                        attr("data-step-toggle") := step.toString,
                        name                     := s"step.${step.toString}",
                        scalatags.Text.all.value := "on",
                        if selected then checked := "checked" else (),
                      ),
                      span(step.toString),
                    )
                  }
                ),
              ),
              div(id := "static-step-agents", cls := "mt-4")(
                p(cls := "mb-2 text-sm font-semibold text-slate-200")("Step Agents"),
                p(
                  cls := "mb-2 text-xs text-slate-400"
                )("Optional fallback fields used when JavaScript is unavailable."),
                div(cls := "grid grid-cols-1 gap-2 sm:grid-cols-2")(
                  MigrationStep.values.toList.map { step =>
                    val selectedAgent = stepAgentMap.getOrElse(step, "")
                    div(cls := "rounded-md border border-white/10 bg-slate-800/50 px-3 py-2")(
                      label(
                        cls   := "mb-1 block text-xs font-semibold text-slate-200",
                        `for` := s"agent-${step.toString}",
                      )(
                        step.toString
                      ),
                      select(
                        id   := s"agent-${step.toString}",
                        name := s"agent.${step.toString}",
                        cls  := "w-full rounded-md border border-white/15 bg-slate-900/70 px-2 py-1.5 text-xs text-slate-100",
                      )(
                        option(
                          value := "",
                          if selectedAgent.isEmpty then selected := "selected" else (),
                        )("No specific agent"),
                        agentOptions.map { agent =>
                          option(
                            value := agent.name,
                            if selectedAgent == agent.name then selected := "selected" else (),
                          )(s"${agent.displayName} (${agent.name})")
                        },
                      ),
                    )
                  }
                ),
              ),
              div(cls := "mt-4")(
                p(cls := "mb-2 text-sm font-semibold text-slate-200")("Ordered Steps (with optional agent)"),
                ul(id := "ordered-step-list", cls := "space-y-2"),
              ),
            ),
            div(cls := "flex items-center gap-3")(
              button(
                `type` := "submit",
                cls    := "rounded-md bg-indigo-500 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-400",
              )("Save Workflow"),
              a(href := "/workflows", cls := "text-sm font-medium text-slate-300 hover:text-white")("Cancel"),
            ),
          ),
          div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-5")(
            h2(cls := "text-lg font-semibold text-white")("Live Mermaid Preview"),
            p(cls := "mt-1 text-xs text-slate-400")("Rendered from the current ordered steps."),
            div(
              id  := "workflow-mermaid-preview",
              cls := "mt-4 overflow-auto rounded-lg border border-white/10 bg-slate-950/70 p-4",
            )(
              div(cls := "mermaid text-slate-200")(workflowToMermaid(workflow.steps))
            ),
          ),
        ),
        script(src := "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"),
        script(raw(formScript())),
      )
    )

  def detail(workflow: WorkflowDefinition): String =
    Layout.page(s"Workflow: ${workflow.name}", "/workflows")(
      div(cls := "mx-auto max-w-5xl space-y-5")(
        a(
          href := "/workflows",
          cls  := "text-sm font-medium text-indigo-300 hover:text-indigo-200",
        )("← Back to Workflows"),
        div(cls := "rounded-xl border border-white/10 bg-slate-900/70 p-6")(
          div(cls := "flex items-start justify-between gap-4")(
            div(
              h1(cls := "text-2xl font-bold text-white")(workflow.name),
              p(cls := "mt-1 text-sm text-slate-300")(workflow.description.getOrElse("No description provided")),
            ),
            span(
              cls := (if workflow.isBuiltin then
                        "rounded-full border border-sky-400/30 bg-sky-500/20 px-2 py-0.5 text-xs font-semibold text-sky-200"
                      else
                        "rounded-full border border-violet-400/30 bg-violet-500/20 px-2 py-0.5 text-xs font-semibold text-violet-200")
            )(
              if workflow.isBuiltin then "Built-in" else "Custom"
            ),
          ),
          div(cls := "mt-4 rounded-lg border border-white/10 bg-slate-950/70 p-4")(
            div(cls := "mermaid text-slate-200")(workflowToMermaid(workflow.steps))
          ),
          ol(cls := "mt-5 list-decimal space-y-2 pl-5 text-sm text-slate-200")(
            workflow.steps.map { step =>
              val assignedAgent = workflow.stepAgents.find(_.step == step).map(_.agentName)
              li(
                span(step.toString),
                assignedAgent.filter(_.nonEmpty).map(agent =>
                  span(cls := "ml-2 text-xs text-cyan-300")(s"(agent: $agent)")
                ),
              )
            }
          ),
        ),
        script(src := "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"),
        script(raw(
          "document.addEventListener('DOMContentLoaded', function () { if (window.mermaid) { mermaid.initialize({ startOnLoad: true, securityLevel: 'loose' }); } });"
        )),
      )
    )

  def workflowToMermaid(steps: List[MigrationStep]): String =
    val nodes = steps.zipWithIndex.map((step, index) => s"""  step$index["${step.toString}"]""")
    val edges = steps.indices.sliding(2).map(pair => s"  step${pair(0)} --> step${pair(1)}").toList
    ("graph LR" :: nodes ::: edges).mkString("\n")

  private def workflowRow(workflow: WorkflowDefinition, agentDisplayByName: Map[String, String]): Frag =
    val actions: Frag =
      if workflow.isBuiltin then span(cls := "text-xs text-slate-400")("Built-in")
      else
        workflow.id match
          case Some(id) =>
            div(cls := "flex items-center justify-end gap-2")(
              a(
                href := s"/workflows/$id",
                cls  := "rounded-md border border-slate-400/30 bg-slate-500/10 px-2 py-1 text-xs font-semibold text-slate-200 hover:bg-slate-500/20",
              )("View"),
              a(
                href := s"/workflows/$id/edit",
                cls  := "rounded-md border border-cyan-400/30 bg-cyan-500/20 px-2 py-1 text-xs font-semibold text-cyan-200 hover:bg-cyan-500/30",
              )("Edit"),
              button(
                `type`                       := "button",
                attr("hx-delete")            := s"/workflows/$id",
                attr("hx-confirm")           := s"Delete workflow '${workflow.name}'?",
                attr("hx-target")            := "closest tr",
                attr("hx-swap")              := "delete",
                attr("hx-on::after-request") :=
                  "if(event.detail.successful){window.location.reload();}",
                cls                          := "rounded-md border border-rose-400/30 bg-rose-500/10 px-2 py-1 text-xs font-semibold text-rose-200 hover:bg-rose-500/20",
              )("Delete"),
            )
          case None     => span(cls := "text-xs text-slate-400")("Built-in")

    val assignmentMap = workflow.stepAgents.map(a => a.step -> a.agentName).toMap
    val agentSummary  =
      if workflow.stepAgents.isEmpty then "—"
      else
        assignmentMap.toList
          .sortBy((step, _) => workflow.steps.indexOf(step))
          .map {
            case (step, agentName) =>
              val display = agentDisplayByName.getOrElse(agentName, agentName)
              s"${step.toString}: $display"
          }
          .mkString(" · ")

    tr(cls := "hover:bg-white/5")(
      td(cls := "py-4 pl-6 pr-3 text-sm font-medium text-white")(workflow.name),
      td(cls := "px-3 py-4 text-sm text-slate-300")(workflow.steps.length.toString),
      td(cls := "px-3 py-4 text-sm text-slate-300")(workflow.description.getOrElse("—")),
      td(cls := "px-3 py-4 text-xs text-slate-300")(agentSummary),
      td(cls := "py-4 pl-3 pr-6 text-right text-sm")(actions),
    )

  private def textField(fieldName: String, labelText: String, fieldValue: String, required: Boolean): Frag =
    div(cls := "mt-3")(
      label(cls := "mb-2 block text-sm font-semibold text-slate-200", `for` := fieldName)(labelText),
      input(
        `type` := "text",
        id     := fieldName,
        name   := fieldName,
        value  := fieldValue,
        cls    := "w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none",
        if required then scalatags.Text.all.required else (),
      ),
    )

  private def stepAgentsToJson(stepAgents: List[WorkflowStepAgent]): String =
    val payload = stepAgents
      .collect {
        case WorkflowStepAgent(step, agentName) if agentName.trim.nonEmpty =>
          step.toString -> agentName.trim
      }
      .toMap
    val entries = payload.toList.sortBy(_._1).map {
      case (key, value) =>
        val safeKey   = key.replace("\\", "\\\\").replace("\"", "\\\"")
        val safeValue = value.replace("\\", "\\\\").replace("\"", "\\\"")
        s"\"$safeKey\":\"$safeValue\""
    }
    s"{${entries.mkString(",")}}"

  private def formScript(): String =
    s"""
       |document.addEventListener("DOMContentLoaded", function () {
       |  var hidden = document.getElementById("orderedSteps");
       |  var agentsHidden = document.getElementById("stepAgentsJson");
       |  var list = document.getElementById("ordered-step-list");
       |  var preview = document.getElementById("workflow-mermaid-preview");
       |  if (!hidden || !agentsHidden || !list || !preview) return;
       |
       |  var parseCsv = function (value) {
       |    return value.split(",").map(function (x) { return x.trim(); }).filter(function (x) { return x.length > 0; });
       |  };
       |
       |  var parseStepAgents = function (value) {
       |    if (!value || value.trim().length === 0) return {};
       |    try {
       |      var parsed = JSON.parse(value);
       |      if (parsed && typeof parsed === "object") return parsed;
       |      return {};
       |    } catch (e) {
       |      return {};
       |    }
       |  };
       |
       |  var toggles = Array.prototype.slice.call(document.querySelectorAll("[data-step-toggle]"));
       |  var steps = parseCsv(hidden.value);
       |  var stepAgents = parseStepAgents(agentsHidden.value);
       |
       |  if (steps.length === 0) {
       |    steps = toggles.filter(function (el) { return el.checked; }).map(function (el) { return el.getAttribute("data-step-toggle"); });
       |  }
       |
       |  var synchronizeCheckboxes = function () {
       |    toggles.forEach(function (toggle) {
       |      var step = toggle.getAttribute("data-step-toggle");
       |      toggle.checked = steps.indexOf(step) >= 0;
       |    });
       |  };
       |
       |  var toMermaid = function () {
       |    if (steps.length === 0) {
       |      return ["graph LR", '  empty["No steps selected"]'].join(String.fromCharCode(10));
       |    }
       |    var escapeStepLabel = function (value) {
       |      return String(value).replace(/\\/g, "\\\\").replace(/"/g, '\\"');
       |    };
       |    var nodes = steps.map(function (step, idx) {
       |      return '  step' + idx + '["' + escapeStepLabel(step) + '"]';
       |    });
       |    var edges = [];
       |    for (var i = 0; i < steps.length - 1; i++) {
       |      edges.push("  step" + i + " --> step" + (i + 1));
       |    }
       |    return ["graph LR"].concat(nodes).concat(edges).join(String.fromCharCode(10));
       |  };
       |
       |  var updateHiddenValues = function () {
       |    hidden.value = steps.join(",");
       |    agentsHidden.value = JSON.stringify(stepAgents || {});
       |  };
       |
       |  var renderPreview = function () {
       |    updateHiddenValues();
       |    list.innerHTML = "";
       |
       |    steps.forEach(function (step, idx) {
       |      var li = document.createElement("li");
       |      li.className = "rounded-md border border-white/10 bg-slate-800/70 px-3 py-2 text-sm text-slate-100";
       |
       |      var top = document.createElement("div");
       |      top.className = "flex items-center justify-between";
       |
       |      var label = document.createElement("span");
       |      label.textContent = (idx + 1) + ". " + step;
       |      top.appendChild(label);
       |
       |      var controls = document.createElement("div");
       |      controls.className = "flex gap-1";
       |
       |      var up = document.createElement("button");
       |      up.type = "button";
       |      up.textContent = "↑";
       |      up.className = "rounded border border-white/20 px-2 py-0.5 text-xs text-slate-100 disabled:opacity-40";
       |      up.disabled = idx === 0;
       |      up.addEventListener("click", function () {
       |        var tmp = steps[idx - 1];
       |        steps[idx - 1] = steps[idx];
       |        steps[idx] = tmp;
       |        renderPreview();
       |      });
       |
       |      var down = document.createElement("button");
       |      down.type = "button";
       |      down.textContent = "↓";
       |      down.className = "rounded border border-white/20 px-2 py-0.5 text-xs text-slate-100 disabled:opacity-40";
       |      down.disabled = idx === steps.length - 1;
       |      down.addEventListener("click", function () {
       |        var tmp = steps[idx + 1];
       |        steps[idx + 1] = steps[idx];
       |        steps[idx] = tmp;
       |        renderPreview();
       |      });
       |
       |      controls.appendChild(up);
       |      controls.appendChild(down);
       |      top.appendChild(controls);
       |      li.appendChild(top);
       |
       |      list.appendChild(li);
       |    });
       |
       |    synchronizeCheckboxes();
       |
       |    var diagram = toMermaid();
       |    preview.innerHTML = "";
       |    if (window.mermaid) {
       |      window.mermaid.initialize({ startOnLoad: false, securityLevel: "loose" });
       |      var div = document.createElement("div");
       |      div.className = "mermaid text-slate-200";
       |      div.textContent = diagram;
       |      preview.appendChild(div);
       |      window.mermaid.run({ nodes: [div] }).catch(function () {
       |        var pre = document.createElement("pre");
       |        pre.className = "text-sm text-rose-200";
       |        pre.textContent = diagram;
       |        preview.innerHTML = "";
       |        preview.appendChild(pre);
       |      });
       |    } else {
       |      var pre = document.createElement("pre");
       |      pre.className = "text-sm text-slate-300";
       |      pre.textContent = diagram;
       |      preview.appendChild(pre);
       |    }
       |  };
       |
       |  toggles.forEach(function (toggle) {
       |    toggle.addEventListener("change", function () {
       |      var step = toggle.getAttribute("data-step-toggle");
       |      if (toggle.checked && steps.indexOf(step) === -1) {
       |        steps.push(step);
       |      } else if (!toggle.checked) {
       |        steps = steps.filter(function (x) { return x !== step; });
       |      }
       |      renderPreview();
       |    });
       |  });
       |
       |  renderPreview();
       |});
       |""".stripMargin
