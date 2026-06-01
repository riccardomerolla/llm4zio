#!/usr/bin/env bash
#
# Seeds the sbt Calculator project for example 03-bugfix into a temp directory
# (or a path you supply), copies the flow script alongside it, and inits git.
#
# Unlike 01/04, this flow needs a real GitHub repo + an issue + CI, so --run is
# NOT supported — the script prints the manual GitHub steps instead.
#
# Usage:
#   examples/03-bugfix/create-test-project.sh                 # mktemp dest
#   examples/03-bugfix/create-test-project.sh /path/to/dir    # explicit dest
#   examples/03-bugfix/create-test-project.sh --local         # publishLocal + pin

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="$SCRIPT_DIR/test-project"
PLANS_DIR="$(cd "$SCRIPT_DIR/../../plans" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=../_seed_lib.sh
. "$SCRIPT_DIR/../_seed_lib.sh"

parse_args "$@"
if [ "$RUN" -eq 1 ]; then
  echo "--run is not supported for 03-bugfix (it needs a GitHub repo + issue + CI)." >&2
  exit 2
fi
resolve_dest "llm4zio-03-bugfix"
init_destination "$SEED_DIR" "$PLANS_DIR" "issue-pr-bugfix.sc" "Initial calculator (with a bug in average)"
apply_local_flag "$REPO_ROOT" "$DEST/issue-pr-bugfix.sc"

echo
echo "Test project ready at: $DEST"
cat <<EOF

This flow needs a real GitHub repo, an issue, and CI. Manual steps:
  cd $DEST
  gh repo create <you>/calculator-demo --private --source=. --push
  # then open an issue describing the bug, e.g.:
  gh issue create --title "average([]) throws ArithmeticException" \\
    --body "Calculator.average on an empty list divides by zero."
  # finally run the flow against the issue reference it prints:
  scala-cli run issue-pr-bugfix.sc -- "<you>/calculator-demo#1"

Requires: JDK 21+, scala-cli, sbt, \`claude\` logged in, \`gh\` authenticated,
and a reasoning API key in the environment (e.g. ANTHROPIC_API_KEY).
EOF
