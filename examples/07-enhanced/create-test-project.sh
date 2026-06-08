#!/usr/bin/env bash
#
# Seeds the calculator Rust crate for example 07-enhanced into a temp directory
# (or a path you supply), copies the flow script alongside it, and inits git.
# Same starter as 01-simple; the flow adds plan self-review + a codebase brief.
# Edit `test-project/` (not this script) to change the starter.
#
# Usage:
#   examples/07-enhanced/create-test-project.sh                 # mktemp dest
#   examples/07-enhanced/create-test-project.sh /path/to/dir    # explicit dest
#   examples/07-enhanced/create-test-project.sh --local         # publishLocal + pin
#   examples/07-enhanced/create-test-project.sh --run           # seed, then run
#   examples/07-enhanced/create-test-project.sh --local --run

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="$SCRIPT_DIR/test-project"
PLANS_DIR="$(cd "$SCRIPT_DIR/../../plans" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=../_seed_lib.sh
. "$SCRIPT_DIR/../_seed_lib.sh"

parse_args "$@"
resolve_dest "llm4zio-07-enhanced"
init_destination "$SEED_DIR" "$PLANS_DIR" "implement-enhanced.scala" "Initial calculator crate"
apply_local_flag "$REPO_ROOT" "$DEST/implement-enhanced.scala"

PROMPT="Add a multiply function to the calculator crate"

echo
echo "Test project ready at: $DEST"
# Format after every edit with cargo fmt; the brief + plan review need no extra setup.
export LLM4ZIO_FORMAT="${LLM4ZIO_FORMAT:-cargo fmt}"
maybe_run "implement-enhanced.scala" "$PROMPT"   # execs scala-cli when --run; else no-op
cat <<EOF

Next steps:
  cd $DEST
  LLM4ZIO_FORMAT="cargo fmt" scala-cli run implement-enhanced.scala -- "$PROMPT"

What's different from 01-simple: the planner reviews its own plan (correctness,
completeness, simplicity, conciseness) and writes a one-off codebase brief that's
prepended to every task. The brief persists in .llm4zio/plan-enhanced.md, so a
re-run resumes without re-planning.

Requires: JDK 21+, scala-cli, cargo, and \`claude\` logged in.
A reasoning API key is read from the environment (e.g. ANTHROPIC_API_KEY).
EOF
