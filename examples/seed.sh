#!/usr/bin/env bash
#
# Seed a runnable test project for an llm4zio example.
#
#   examples/seed.sh <example>                # mktemp dest, deps from Maven Central
#   examples/seed.sh <example> /path/to/dir   # explicit dest
#   examples/seed.sh <example> --local        # sbt publishLocal + pin the local version
#   examples/seed.sh <example> --run          # seed, then run the flow
#   examples/seed.sh <example> --java         # use the Java-authored flow (examples/java/*.java)
#
# Examples: implement, implement-interactive, implement-enhanced, implement-enhanced-pr,
#           implement-live, epic, issue-pr, issue-pr-bugfix, sdd, pipeline, reverse-engineer,
#           handoff, local, local-claude, judge-gate, judge-suite
#
# `handoff` is a two-phase, human-gated example (handoff-plan.sc → approve → handoff-build.sc);
# seeding it prints the two-invocation workflow instead of running a single script.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

EXAMPLE="${1:-}"
[ -n "$EXAMPLE" ] || { echo "usage: examples/seed.sh <example> [dest] [--local] [--run] [--java]" >&2; exit 2; }
shift

# Parse flags FIRST, so special-case examples below (judge-suite, handoff) still see and honour
# them — an early print-and-exit must not silently swallow --java or an unknown flag.
LOCAL=0; RUN=0; JAVA=0; DEST=""
for arg in "$@"; do
  case "$arg" in
    --local) LOCAL=1 ;;
    --run)   RUN=1 ;;
    --java)  JAVA=1 ;;
    --*)     echo "unknown flag: $arg" >&2; exit 2 ;;
    *)       DEST="$arg" ;;
  esac
done

# example → starter project + demo prompt ("" = the flow needs a real argument, e.g. an issue ref)
case "$EXAMPLE" in
  implement)             STARTER="calculator-rs";      PROMPT="Add a multiply function to the calculator crate" ;;
  implement-enhanced)    STARTER="calculator-rs";      PROMPT="Add a multiply function to the calculator crate" ;;
  implement-enhanced-pr) STARTER="calculator-rs";      PROMPT="Add a multiply function to the calculator crate" ;; # also needs a remote + gh
  implement-interactive) STARTER="calculator-rs-open"; PROMPT="Make the calculator crate more useful" ;;
  implement-live)        STARTER="calculator-rs-open"; PROMPT="Make the calculator crate more useful" ;;
  epic)                  STARTER="todo-java";          PROMPT="Persist tasks to a JSON file (load on startup, save on every change), add 'done <id>' and 'delete <id>' commands, and support priority levels (low/medium/high) with a 'list --priority' filter" ;;
  issue-pr)              STARTER="calculator-scala";   PROMPT="" ;;
  issue-pr-bugfix)       STARTER="calculator-scala";   PROMPT="" ;;
  sdd)                   STARTER="todo-java";          PROMPT="Add due dates: 'add <text> --due YYYY-MM-DD', mark overdue items in 'list', and a 'due' command showing items due today" ;;
  pipeline)              STARTER="todo-java";          PROMPT="Support hashtags: 'add <text> #tag' stores tags, 'list --tag <tag>' filters, and a 'tags' command lists every tag with its count" ;;
  reverse-engineer)      STARTER="todo-java";          PROMPT="for a new contributor" ;;
  handoff)               STARTER="todo-java";          PROMPT="Add due dates: 'add <text> --due YYYY-MM-DD', mark overdue items in 'list', and a 'due' command showing items due today" ;;
  local)                 STARTER="calculator-rs";      PROMPT="Add a multiply function to the calculator crate" ;;
  local-claude)          STARTER="calculator-rs";      PROMPT="Add a multiply function to the calculator crate" ;;
  judge-gate)            STARTER="calculator-rs";      PROMPT="Add multiply and divide functions to the calculator crate; divide must return an error on divide-by-zero" ;;
  judge-suite)           STARTER="";                   PROMPT="" ;; # offline harness — no starter; intercepted below
  *) echo "unknown example: $EXAMPLE" >&2; exit 2 ;;
esac
SCRIPT_NAME="$EXAMPLE.sc"

# --java: swap the .sc for its Java-authored counterpart under examples/java/ (kebab-case → PascalCase,
# e.g. issue-pr-bugfix → IssuePrBugfix.java). Not every example has one — fail with the available list.
if [ "$JAVA" -eq 1 ]; then
  PASCAL="$(printf '%s' "$EXAMPLE" | awk -F- '{ for (i = 1; i <= NF; i++) printf toupper(substr($i,1,1)) substr($i,2) }')"
  SCRIPT_NAME="java/$PASCAL.java"
  if [ ! -f "$SCRIPT_DIR/$SCRIPT_NAME" ]; then
    echo "no Java counterpart for '$EXAMPLE' — available:" >&2
    ls "$SCRIPT_DIR/java/" | grep '\.java$' | sed 's/^/  /' >&2
    exit 2
  fi
fi

# `judge-suite` is an offline eval harness — it scores a built-in dataset with no repo, so
# there's nothing to seed. Print how to run it (the .java counterpart when --java) and exit
# (mirrors `handoff`'s print-and-exit).
if [ "$EXAMPLE" = "judge-suite" ]; then
  echo
  echo "$(basename "$SCRIPT_NAME") is an offline LLM-as-a-Judge harness — no starter repo needed."
  echo "Run it directly (needs a logged-in agent CLI / API for the judge):"
  echo "  scala-cli run $SCRIPT_DIR/$SCRIPT_NAME"
  echo
  echo "Swap the judge backend with LLM4ZIO_CODER=claude|codex|gemini|pi (default claude)."
  exit 0
fi

if [ -z "$DEST" ]; then
  tmp="${TMPDIR:-/tmp}"
  DEST="$(mktemp -d "${tmp%/}/llm4zio-$EXAMPLE.XXXXXXXX")"
fi
mkdir -p "$DEST"

# The flow script lives OUTSIDE the seeded repo, so the CLI coding agent — rooted in $DEST —
# never reads Scala source sitting in a Java/Rust/… project (which burns tokens and confuses it).
# Only the starter project is committed; the script is run by path with the repo as the cwd.
RUN_SCRIPT="$SCRIPT_DIR/$SCRIPT_NAME"

cp -R "$SCRIPT_DIR/starters/$STARTER/." "$DEST/"
( cd "$DEST" \
    && git init -q -b main \
    && git add -A \
    && git -c user.email=seed@llm4zio.dev -c user.name=llm4zio commit -q -m "Seed $EXAMPLE starter" )

# `handoff` is two scripts with a manual approval step between them — print the workflow rather than
# running a single script. (The other examples fall through to the generic single-script path below.)
if [ "$EXAMPLE" = "handoff" ]; then
  PLAN_SCRIPT="$SCRIPT_DIR/handoff-plan.sc"
  BUILD_SCRIPT="$SCRIPT_DIR/handoff-build.sc"
  echo
  echo "Test project ready at: $DEST"
  echo "Flow scripts (outside the repo): $PLAN_SCRIPT , $BUILD_SCRIPT"
  cat <<EOF

Two-phase, human-gated hand-off — run both phases from OUTSIDE the repo with the same --repo (both
scripts share a default requirement; add --prompt-file <file> to both to use your own):

  # 1) draft a reviewable spec + plan, then stop:
  scala-cli run $PLAN_SCRIPT  -- --repo $DEST

  # 2) review specs/<epicId>.md and change "- [ ] Approved" to "- [x] Approved"

  # 3) refuse to build until approved, then implement:
  scala-cli run $BUILD_SCRIPT -- --repo $DEST

The spec + plan land in the current directory (the workspace), never in $DEST.

Requires: JDK 21+, scala-cli, maven (the todo-java starter), and an agent CLI logged in.
EOF
  exit 0
fi

if [ "$LOCAL" -eq 1 ]; then
  echo "Publishing llm4zio locally (sbt publishLocal)…"
  # Capture the output (don't hide failures) and read the version sbt ACTUALLY published from it — never guess from
  # the ivy cache with `ls -t`, which silently pins a stale prior publish if this run is a no-op, fails, or a
  # long-lived sbt server is reporting an outdated sbt-dynver version (run `sbt shutdown` if the version looks wrong).
  if ! publishLog="$( cd "$REPO_ROOT" && sbt -batch -Dsbt.log.noformat=true publishLocal 2>&1 )"; then
    printf '%s\n' "$publishLog" >&2
    echo "sbt publishLocal failed" >&2; exit 1
  fi
  version="$(printf '%s\n' "$publishLog" | grep -oE 'llm4zio-runner_3/[^/]+' | sed 's#.*/##' | tail -1)"
  [ -n "$version" ] || { echo "could not parse the published version from sbt publishLocal output" >&2; exit 1; }
  echo "Pinning script to local version $version"
  # Pin the local version on a copy OUTSIDE the repo (never mutate the source example, never add a .sc to $DEST).
  FLOW_DIR="$(mktemp -d "${TMPDIR:-/tmp}/llm4zio-$EXAMPLE-flow.XXXXXXXX")"
  FLOW_FILE="$(basename "$SCRIPT_NAME")"
  cp "$SCRIPT_DIR/$SCRIPT_NAME" "$FLOW_DIR/$FLOW_FILE"
  RUN_SCRIPT="$FLOW_DIR/$FLOW_FILE"
  # .sc examples depend on llm4zio-runner (:: cross coordinate); .java ones on llm4zio-java (single colon, crossPaths off).
  sed -i.bak -E "s#(io\.github\.riccardomerolla::llm4zio-runner:)[^\"]+#\1$version#; s#(io\.github\.riccardomerolla:llm4zio-java:)[^\"]+#\1$version#" "$RUN_SCRIPT"
  rm -f "$RUN_SCRIPT.bak"
  if ! grep -q 'using repository ivy2Local' "$RUN_SCRIPT"; then
    printf '%s\n' '//> using repository ivy2Local' | cat - "$RUN_SCRIPT" > "$RUN_SCRIPT.tmp"
    mv "$RUN_SCRIPT.tmp" "$RUN_SCRIPT"
  fi
fi

echo
echo "Test project ready at: $DEST"
echo "Flow script (outside the repo):  $RUN_SCRIPT"
if [ "$RUN" -eq 1 ]; then
  if [ -z "$PROMPT" ]; then
    echo "$EXAMPLE needs an issue reference (owner/repo#number); run it yourself:"
    echo "  (cd $DEST && scala-cli run $RUN_SCRIPT -- \"owner/repo#42\")"
    exit 0
  fi
  echo "Running: (cd $DEST && scala-cli run $RUN_SCRIPT -- \"$PROMPT\")"
  cd "$DEST"
  exec scala-cli run "$RUN_SCRIPT" -- "$PROMPT"
fi

if [ -z "$PROMPT" ]; then
  NEXT_PROMPT="owner/repo#42"
else
  NEXT_PROMPT="$PROMPT"
fi
cat <<EOF

Next steps — run the script from OUTSIDE the repo, with the repo as the working directory:
  cd $DEST
  scala-cli run $RUN_SCRIPT -- "$NEXT_PROMPT"

The coding agent only ever sees $DEST, so no llm4zio files land in (or confuse) the target repo.
Alternatively, target the repo without cd-ing into it:
  scala-cli run $RUN_SCRIPT -- --repo $DEST "$NEXT_PROMPT"

Requires: JDK 21+, scala-cli, the starter's toolchain (cargo / sbt / maven), and the
chosen agent CLI logged in (claude by default; LLM4ZIO_CODER=codex|gemini to swap).
EOF
