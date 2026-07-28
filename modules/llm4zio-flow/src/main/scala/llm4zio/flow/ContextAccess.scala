package llm4zio.flow

import java.nio.file.Path

import llm4zio.core.LlmService

/** Bare-name access to the [[FlowContext]] members, orca-style: inside a flow body (`FlowContext ?=> …`) write
  * `git.push(…)`, `gh.createPr(…)`, `Chat.start(coder, …)` instead of `ctx.git.push(…)`. Each is a one-line summon —
  * go-to-definition lands here, not in macro territory.
  */
def git(using ctx: FlowContext, cap: Caps.GitRead): GitTool = ctx.git

def gh(using ctx: FlowContext, cap: Caps.GhRead): GhTool = ctx.gh

def coder(using ctx: FlowContext, cap: Caps.Coder): LlmService = ctx.coder

def reasoning(using ctx: FlowContext, cap: Caps.Reasoning): LlmService = ctx.reasoning

def reviewers(using ctx: FlowContext): List[LlmService] = ctx.reviewers

def userPrompt(using ctx: FlowContext): String = ctx.userPrompt

def workDir(using ctx: FlowContext): Path = ctx.workDir

def workspace(using ctx: FlowContext): Path = ctx.workspace

/** The resumable plan file for `prompt`, under this flow's bookkeeping directory — `workspace/.llm4zio` when the repo
  * is the workspace, else the repo-namespaced `workspace/.llm4zio/<repo-id>`. The context-aware counterpart to
  * [[Plan.defaultPath]]: a script writes `planPath(userPrompt)` and gets the right location whether it runs inside the
  * repo or against an external `--repo`.
  */
def planPath(prompt: String)(using ctx: FlowContext): Path =
  Plan.defaultPath(prompt, Workspace.llm4zioDir(ctx.workspace, ctx.workDir))
