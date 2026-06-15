#!/usr/bin/env bash
#
# Seed a runnable test project for an llm4zio example.
#
#   examples/seed.sh <example>                # mktemp dest, deps from Maven Central
#   examples/seed.sh <example> /path/to/dir   # explicit dest
#   examples/seed.sh <example> --local        # sbt publishLocal + pin the local version
#   examples/seed.sh <example> --run          # seed, then run the flow
#
# Examples: implement, implement-interactive, implement-enhanced, implement-live,
#           epic, issue-pr, issue-pr-bugfix, sdd, local

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

EXAMPLE="${1:-}"
[ -n "$EXAMPLE" ] || { echo "usage: examples/seed.sh <example> [dest] [--local] [--run]" >&2; exit 2; }
shift

# example → starter project + demo prompt ("" = the flow needs a real argument, e.g. an issue ref)
case "$EXAMPLE" in
  implement)             STARTER="calculator-rs";      PROMPT="Add a multiply function to the calculator crate" ;;
  implement-enhanced)    STARTER="calculator-rs";      PROMPT="Add a multiply function to the calculator crate" ;;
  implement-interactive) STARTER="calculator-rs-open"; PROMPT="Make the calculator crate more useful" ;;
  implement-live)        STARTER="calculator-rs-open"; PROMPT="Make the calculator crate more useful" ;;
  epic)                  STARTER="todo-java";          PROMPT="Persist tasks to a JSON file (load on startup, save on every change), add 'done <id>' and 'delete <id>' commands, and support priority levels (low/medium/high) with a 'list --priority' filter" ;;
  issue-pr)              STARTER="calculator-scala";   PROMPT="" ;;
  issue-pr-bugfix)       STARTER="calculator-scala";   PROMPT="" ;;
  sdd)                   STARTER="todo-java";          PROMPT="Add due dates: 'add <text> --due YYYY-MM-DD', mark overdue items in 'list', and a 'due' command showing items due today" ;;
  local)                 STARTER="calculator-rs";      PROMPT="Add a multiply function to the calculator crate" ;;
  *) echo "unknown example: $EXAMPLE" >&2; exit 2 ;;
esac
SCRIPT_NAME="$EXAMPLE.sc"

LOCAL=0; RUN=0; DEST=""
for arg in "$@"; do
  case "$arg" in
    --local) LOCAL=1 ;;
    --run)   RUN=1 ;;
    --*)     echo "unknown flag: $arg" >&2; exit 2 ;;
    *)       DEST="$arg" ;;
  esac
done

if [ -z "$DEST" ]; then
  tmp="${TMPDIR:-/tmp}"
  DEST="$(mktemp -d "${tmp%/}/llm4zio-$EXAMPLE.XXXXXXXX")"
fi
mkdir -p "$DEST"

cp -R "$SCRIPT_DIR/starters/$STARTER/." "$DEST/"
cp "$SCRIPT_DIR/$SCRIPT_NAME" "$DEST/$SCRIPT_NAME"
( cd "$DEST" \
    && git init -q -b main \
    && git add -A \
    && git -c user.email=seed@llm4zio.dev -c user.name=llm4zio commit -q -m "Seed $EXAMPLE starter" )

if [ "$LOCAL" -eq 1 ]; then
  echo "Publishing llm4zio locally (sbt publishLocal)…"
  ( cd "$REPO_ROOT" && sbt -batch -Dsbt.log.noformat=true publishLocal >/dev/null )
  ivy="$HOME/.ivy2/local/io.github.riccardomerolla/llm4zio-runner_3"
  version="$(ls -t "$ivy" 2>/dev/null | head -1)"
  [ -n "$version" ] || { echo "no locally published llm4zio-runner under $ivy" >&2; exit 1; }
  echo "Pinning script to local version $version"
  sed -i.bak -E "s#(io\.github\.riccardomerolla::llm4zio-runner:)[^\"]+#\1$version#" "$DEST/$SCRIPT_NAME"
  rm -f "$DEST/$SCRIPT_NAME.bak"
  if ! grep -q 'using repository ivy2Local' "$DEST/$SCRIPT_NAME"; then
    printf '%s\n' '//> using repository ivy2Local' | cat - "$DEST/$SCRIPT_NAME" > "$DEST/$SCRIPT_NAME.tmp"
    mv "$DEST/$SCRIPT_NAME.tmp" "$DEST/$SCRIPT_NAME"
  fi
fi

echo
echo "Test project ready at: $DEST"
if [ "$RUN" -eq 1 ]; then
  if [ -z "$PROMPT" ]; then
    echo "$EXAMPLE needs an issue reference (owner/repo#number); run it yourself:"
    echo "  cd $DEST"
    echo "  scala-cli run $SCRIPT_NAME -- \"owner/repo#42\""
    exit 0
  fi
  echo "Running: scala-cli run $SCRIPT_NAME -- \"$PROMPT\""
  cd "$DEST"
  exec scala-cli run "$SCRIPT_NAME" -- "$PROMPT"
fi

if [ -z "$PROMPT" ]; then
  NEXT_PROMPT="owner/repo#42"
else
  NEXT_PROMPT="$PROMPT"
fi
cat <<EOF

Next steps:
  cd $DEST
  scala-cli run $SCRIPT_NAME -- "$NEXT_PROMPT"

Requires: JDK 21+, scala-cli, the starter's toolchain (cargo / sbt / maven), and the
chosen agent CLI logged in (claude by default; LLM4ZIO_CODER=codex|gemini to swap).
EOF
