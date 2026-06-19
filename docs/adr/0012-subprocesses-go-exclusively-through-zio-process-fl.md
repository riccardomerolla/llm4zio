# 12. Subprocesses go exclusively through zio-process (flow.Proc), never raw ProcessBuilder

- Status: accepted

## Context
The library shells out heavily — git, gh, the Azure DevOps REST flow indirectly, and the CLI coding agents — and these must integrate with ZIO's interruption, resource safety, and typed-error model. Raw ProcessBuilder would bypass interruption and structured concurrency and risk leaking processes or hanging on prompts.

## Decision
Route every external process through zio-process via a flow.Proc wrapper. GitTool, GhTool, AdoTool, and the CLI providers' executors (CliProcessExecutor/LiveCliProcessExecutor) all build on it; CLI stream-JSON output is parsed by CliStreamJson. Calls carry non-interactive environments to prevent TTY hangs.

## Consequences
Subprocesses participate in interruption and resource scoping, and a Ctrl-C cleanly unwinds running stages. There is one well-tested path for spawning processes. Integration tests spawn real git against a temp repo and local bare remote with no network, exercising this path. Contributors must use Proc rather than reaching for ProcessBuilder.
