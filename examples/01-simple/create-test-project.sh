#!/usr/bin/env bash
#
# Seeds the calculator Rust crate for example 01-simple into a temp directory
# (or a path you supply), copies the flow script alongside it, and inits git so
# the flow has something to commit against. Edit `test-project/` (not this
# script) to change the starter.
#
# Usage:
#   examples/01-simple/create-test-project.sh                 # mktemp dest
#   examples/01-simple/create-test-project.sh /path/to/dir    # explicit dest
#   examples/01-simple/create-test-project.sh --local         # publishLocal + pin
#   examples/01-simple/create-test-project.sh --run           # seed, then run
#   examples/01-simple/create-test-project.sh --local --run

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="$SCRIPT_DIR/test-project"
PLANS_DIR="$(cd "$SCRIPT_DIR/../../plans" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=../_seed_lib.sh
. "$SCRIPT_DIR/../_seed_lib.sh"

parse_args "$@"
resolve_dest "llm4zio-01-simple"
init_destination "$SEED_DIR" "$PLANS_DIR" "implement.sc" "Initial calculator crate"
apply_local_flag "$REPO_ROOT" "$DEST/implement.sc"

PROMPT="Add a multiply function to the calculator crate"

echo
echo "Test project ready at: $DEST"
maybe_run "implement.sc" "$PROMPT"   # execs scala-cli when --run; else no-op
cat <<EOF

Next steps:
  cd $DEST
  scala-cli run implement.sc -- "$PROMPT"

Requires: JDK 21+, scala-cli, cargo, and \`claude\` logged in.
A reasoning API key is read from the environment (e.g. ANTHROPIC_API_KEY).
EOF
