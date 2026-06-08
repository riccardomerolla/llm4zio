#!/usr/bin/env bash
#
# Seeds the sbt Calculator project for example 06-issue-pr into a temp directory
# (or a path you supply), copies the flow script alongside it, and inits git.
#
# Like 03-bugfix, this flow needs a real GitHub repo + an issue, so --run is NOT
# supported — the script prints the manual GitHub steps instead.
#
# Usage:
#   examples/06-issue-pr/create-test-project.sh                 # mktemp dest
#   examples/06-issue-pr/create-test-project.sh /path/to/dir    # explicit dest
#   examples/06-issue-pr/create-test-project.sh --local         # publishLocal + pin

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="$SCRIPT_DIR/test-project"
PLANS_DIR="$(cd "$SCRIPT_DIR/../../plans" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=../_seed_lib.sh
. "$SCRIPT_DIR/../_seed_lib.sh"

parse_args "$@"
if [ "$RUN" -eq 1 ]; then
  echo "--run is not supported for 06-issue-pr (it needs a GitHub repo + issue)." >&2
  exit 2
fi
resolve_dest "llm4zio-06-issue-pr"
init_destination "$SEED_DIR" "$PLANS_DIR" "issue-pr.scala" "Initial calculator"
apply_local_flag "$REPO_ROOT" "$DEST/issue-pr.scala"

echo
echo "Test project ready at: $DEST"
cat <<EOF

This flow needs a real GitHub repo and an issue. Manual steps:
  cd $DEST
  gh repo create <you>/calculator-demo --private --source=. --push
  gh issue create --title "Add a multiply operation" \\
    --body "Calculator supports add/subtract/divide but not multiply."
  # then run the flow against the issue reference it prints:
  scala-cli run issue-pr.scala -- "<you>/calculator-demo#1"

The flow skeptically assesses the issue first: if it's not ready to implement it
posts the reason as an issue comment and stops; otherwise it plans, implements
each task with review, pushes, and opens a PR that closes the issue.

Requires: JDK 21+, scala-cli, sbt, \`claude\` logged in, \`gh\` authenticated,
and a reasoning API key in the environment (e.g. ANTHROPIC_API_KEY).
EOF
