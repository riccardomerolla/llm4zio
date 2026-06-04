# Shared helpers for the llm4zio example seeders. Source this from each
# example's create-test-project.sh. Mirrors the UX of orca's examples:
#
#   create-test-project.sh                 # mktemp dest, resolve from Maven Central
#   create-test-project.sh /path/to/dir    # explicit dest
#   create-test-project.sh --local         # sbt publishLocal + pin the local version
#   create-test-project.sh --run           # seed, then run the flow
#   create-test-project.sh --local --run /path
#
# Not bash-strict-sourced on its own; callers `set -euo pipefail`.

LOCAL=0
RUN=0
DEST=""

parse_args() {
  for arg in "$@"; do
    case "$arg" in
      --local) LOCAL=1 ;;
      --run)   RUN=1 ;;
      --*)     echo "unknown flag: $arg" >&2; exit 2 ;;
      *)       DEST="$arg" ;;
    esac
  done
}

# resolve_dest <prefix> — sets DEST to the given path or a fresh temp dir.
# Portable across macOS/BSD and Linux/GNU: an explicit `<dir>/<prefix>.XXXXXXXX`
# template works on both, unlike `mktemp -t <prefix>` (deprecated on GNU and
# rejected there without trailing X's).
resolve_dest() {
  local prefix="$1"
  if [ -z "$DEST" ]; then
    local tmp="${TMPDIR:-/tmp}"
    DEST="$(mktemp -d "${tmp%/}/${prefix}.XXXXXXXX")"
  fi
  mkdir -p "$DEST"
}

# init_destination <seed_dir> <plans_dir> <script_name> <commit_msg>
# Copies the starter project + the flow script into DEST and inits git.
init_destination() {
  local seed_dir="$1" plans_dir="$2" script_name="$3" commit_msg="$4"
  cp -R "$seed_dir/." "$DEST/"
  cp "$plans_dir/$script_name" "$DEST/$script_name"
  ( cd "$DEST" \
      && git init -q -b main \
      && git add -A \
      && git -c user.email=seed@llm4zio.dev -c user.name=llm4zio commit -q -m "$commit_msg" )
}

# apply_local_flag <repo_root> <script_path>
# Under --local: sbt publishLocal, discover the dynver version from the ivy2
# local repo, then pin the script's dep to it and add the ivy2Local resolver.
apply_local_flag() {
  local repo_root="$1" script_path="$2"
  [ "$LOCAL" -eq 1 ] || return 0

  echo "Publishing llm4zio locally (sbt publishLocal)…"
  ( cd "$repo_root" && sbt -batch -Dsbt.log.noformat=true publishLocal >/dev/null )

  local ivy="$HOME/.ivy2/local/io.github.riccardomerolla/llm4zio-runner_3"
  local version
  version="$(ls -t "$ivy" 2>/dev/null | head -1)"
  if [ -z "$version" ]; then
    echo "could not find a locally published llm4zio-runner under $ivy" >&2
    exit 1
  fi
  echo "Pinning script to local version $version"

  # Pin the dependency version (sed -i.bak + -E are accepted by both BSD and GNU sed).
  sed -i.bak -E "s#(io\.github\.riccardomerolla::llm4zio-runner:)[^\"]+#\1$version#" "$script_path"
  rm -f "$script_path.bak"

  # Add the resolver directive. Prepending via a temp file avoids sed's `a\` append
  # idiom, which differs between BSD and GNU sed; directive order is irrelevant to scala-cli.
  if ! grep -q 'using repository ivy2Local' "$script_path"; then
    printf '%s\n' '//> using repository ivy2Local' | cat - "$script_path" > "$script_path.tmp"
    mv "$script_path.tmp" "$script_path"
  fi
}

# maybe_run <script_name> <prompt> — under --run, cd into DEST and exec the flow.
maybe_run() {
  local script_name="$1" prompt="$2"
  [ "$RUN" -eq 1 ] || return 0
  echo "Running: scala-cli run $script_name -- \"$prompt\""
  cd "$DEST"
  exec scala-cli run "$script_name" -- "$prompt"
}
