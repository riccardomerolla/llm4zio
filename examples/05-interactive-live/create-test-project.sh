#!/usr/bin/env bash
#
# Seeds the calculator Rust crate for example 05-interactive-live into a temp
# directory (or a path you supply), copies the live-coding flow script alongside
# it, and inits git. Like 02-interactive, but each task is implemented by a held,
# steerable `claude` session (live streaming + ask_user + approval gate) wired
# over an in-process MCP server.
#
# Usage:
#   examples/05-interactive-live/create-test-project.sh               # mktemp dest
#   examples/05-interactive-live/create-test-project.sh /path/to/dir  # explicit dest
#   examples/05-interactive-live/create-test-project.sh --local       # publishLocal + pin
#   examples/05-interactive-live/create-test-project.sh --run         # seed, then run

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="$SCRIPT_DIR/test-project"
PLANS_DIR="$(cd "$SCRIPT_DIR/../../plans" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=../_seed_lib.sh
. "$SCRIPT_DIR/../_seed_lib.sh"

parse_args "$@"
resolve_dest "llm4zio-05-interactive-live"
init_destination "$SEED_DIR" "$PLANS_DIR" "implement-live.scala" "Initial calculator crate"
apply_local_flag "$REPO_ROOT" "$DEST/implement-live.scala"

PROMPT="Make the calculator crate more useful"

echo
echo "Test project ready at: $DEST"
maybe_run "implement-live.scala" "$PROMPT"
cat <<EOF

Next steps:
  cd $DEST
  scala-cli run implement-live.scala -- "$PROMPT"

The planner asks a clarifying question, then a live claude session implements
each task — you'll see it stream its work, and it may ask you follow-ups on the
terminal. Requires: JDK 21+, scala-cli, cargo, and \`claude\` logged in (no API
key — reasoning and coding both run over the claude CLI).
EOF
