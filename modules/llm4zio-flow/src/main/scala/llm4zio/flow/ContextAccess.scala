package llm4zio.flow

import java.nio.file.Path

import llm4zio.core.LlmService

/** Bare-name access to the [[FlowContext]] members, orca-style: inside a flow body (`FlowContext ?=> …`) write
  * `git.push(…)`, `gh.createPr(…)`, `Chat.start(coder, …)` instead of `ctx.git.push(…)`. Each is a one-line summon —
  * go-to-definition lands here, not in macro territory.
  */

def git(using ctx: FlowContext): GitTool = ctx.git

def gh(using ctx: FlowContext): GhTool = ctx.gh

def coder(using ctx: FlowContext): LlmService = ctx.coder

def reasoning(using ctx: FlowContext): LlmService = ctx.reasoning

def userPrompt(using ctx: FlowContext): String = ctx.userPrompt

def workDir(using ctx: FlowContext): Path = ctx.workDir
