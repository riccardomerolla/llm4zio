# 9. The runtime owns git; the coder is seeded never to commit/branch/push

- Status: Accepted

## Context
Diff-based review only works if the coder's edits are visible as an uncommitted working-tree diff. If the coding agent committed, branched, or pushed on its own, the flow would lose its single reviewable diff and its control over branch/PR semantics, and tokens would be spent on mechanics an ordinary program does for free.

## Decision
Enforce a git-ownership invariant at the type/construction level. Chat.start prepends CoderSystem.gitOwnership to the coder's system prompt so it edits the tree but does not commit/branch/push (opt out with manageGit = true). The flow performs all version-control actions through ctx.git/ctx.gh (GitTool/GhTool over zio-process), and every git call carries a non-interactive environment so a TTY-less run cannot hang on a credential prompt.

## Alternatives considered
Let the coder agent manage git itself — rejected because it dissolves the single reviewable diff and surrenders branch/PR semantics to a non-deterministic agent. Snapshot the tree before/after and diff out-of-band to allow the coder to commit freely — rejected as fragile and wasteful versus simply forbidding commits. Restrict the coder by sandboxing away the git binary — rejected as heavier and less portable than a system-prompt instruction plus runtime-owned git tools.

## Consequences
reviewAndFixLoop always has a meaningful git.diff to judge, and branch/commit/PR semantics stay deterministic and in the program's hands. The coder must be opted-out explicitly for the rare case it should manage git. Robustness details (non-interactive env, last-resort GH_TOKEN credential helper) live in GitTool.
