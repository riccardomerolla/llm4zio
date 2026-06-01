#!/usr/bin/env bash
#
# Seeds the calculator Rust crate for example 02-interactive into a temp
# directory (or a path you supply), copies the interactive flow script alongside
# it, and inits git. Same starter as 01-simple, but the prompt is open-ended so
# the planner asks a clarifying question (via the terminal) before planning.
#
# Usage:
#   examples/02-interactive/create-test-project.sh               # mktemp dest
#   examples/02-interactive/create-test-project.sh /path/to/dir  # explicit dest
#   examples/02-interactive/create-test-project.sh --local       # publishLocal + pin
#   examples/02-interactive/create-test-project.sh --run         # seed, then run

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="$SCRIPT_DIR/test-project"
PLANS_DIR="$(cd "$SCRIPT_DIR/../../plans" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=../_seed_lib.sh
. "$SCRIPT_DIR/../_seed_lib.sh"

parse_args "$@"
resolve_dest "llm4zio-02-interactive"
init_destination "$SEED_DIR" "$PLANS_DIR" "implement-interactive.sc" "Initial calculator crate"
apply_local_flag "$REPO_ROOT" "$DEST/implement-interactive.sc"

PROMPT="Make the calculator crate more useful"

echo
echo "Test project ready at: $DEST"
maybe_run "implement-interactive.sc" "$PROMPT"
cat <<EOF

Next steps:
  cd $DEST
  scala-cli run implement-interactive.sc -- "$PROMPT"

The planner will ask you a clarifying question on the terminal before planning.
Requires: JDK 21+, scala-cli, cargo, \`claude\` logged in, and a reasoning API
key in the environment (e.g. ANTHROPIC_API_KEY).
EOF
