#!/usr/bin/env bash
#
# Seeds the todo-cli Java project for example 04-epic into a temp directory (or a
# path you supply), copies the epic flow script alongside it, and inits git.
# Edit `test-project/` (not this script) to change the starter.
#
# Usage:
#   examples/04-epic/create-test-project.sh                 # mktemp dest
#   examples/04-epic/create-test-project.sh /path/to/dir    # explicit dest
#   examples/04-epic/create-test-project.sh --local         # publishLocal + pin
#   examples/04-epic/create-test-project.sh --run           # seed, then run
#   examples/04-epic/create-test-project.sh --local --run

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="$SCRIPT_DIR/test-project"
PLANS_DIR="$(cd "$SCRIPT_DIR/../../plans" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=../_seed_lib.sh
. "$SCRIPT_DIR/../_seed_lib.sh"

parse_args "$@"
resolve_dest "llm4zio-04-epic"
init_destination "$SEED_DIR" "$PLANS_DIR" "epic.scala" "Initial todo-cli"
apply_local_flag "$REPO_ROOT" "$DEST/epic.scala"

PROMPT="Persist tasks to a JSON file (load on startup, save on every change), add 'done <id>' and 'delete <id>' commands, and support priority levels (low/medium/high) with a 'list --priority' filter"

echo
echo "Test project ready at: $DEST"
maybe_run "epic.scala" "$PROMPT"   # execs scala-cli when --run; else no-op
cat <<EOF

Next steps:
  cd $DEST
  scala-cli run epic.scala -- "$PROMPT"
  # or pick a backend: LLM4ZIO_CODER=gemini scala-cli run epic.scala -- "$PROMPT"

Requires: JDK 21+, scala-cli, and the chosen agent CLI logged in — \`claude\` by
default, or \`codex\` / \`gemini\` via LLM4ZIO_CODER. No API key needed; one CLI
login does planning, coding, and review.
EOF
