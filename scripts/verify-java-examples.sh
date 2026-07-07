#!/usr/bin/env bash
# Verify every examples/java/*.java compiles via scala-cli against the in-tree build.
#
# Publishes core/flow/runner/java locally in ONE sbt session (a single dynver version —
# publishing in separate sessions lets the SNAPSHOT timestamp drift, so transitive deps
# land under a different version than the one you pin), then compiles each example in a
# temp dir with its dep rewritten to that exact version.
#
# scala-cli notes baked in:
#   --offline        resolve from the local caches + ivy2Local only; also skips the
#                    post-compile SNAPSHOT metadata check against Maven Central, which
#                    otherwise hangs on this repo's unpublished versions.
#   timeout 300      belt-and-braces so a wedged resolver can't hang CI.
#
# Usage: scripts/verify-java-examples.sh [--skip-publish]
set -euo pipefail

cd "$(dirname "$0")/.."
SKIP_PUBLISH="${1:-}"

if [[ "$SKIP_PUBLISH" != "--skip-publish" ]]; then
  echo "── publishing all modules locally (one sbt session) ──"
  sbt --batch --no-colors \
    "; print llm4zioJava/version ; llm4zioCore/publishLocal ; llm4zioFlow/publishLocal ; llm4zioRunner/publishLocal ; llm4zioJava/publishLocal" \
    | tee /tmp/verify-java-publish.log >/dev/null
  VERSION=$(grep -aE "^[0-9]+\.[0-9]+\.[0-9]+" /tmp/verify-java-publish.log | head -1 | tr -d '[:space:]')
else
  VERSION=$(ls -1t ~/.ivy2/local/io.github.riccardomerolla/llm4zio-java/ | head -1)
fi

[[ -n "$VERSION" ]] || { echo "could not determine published version" >&2; exit 1; }
echo "── verifying examples against $VERSION ──"

FAILED=0
for src in examples/java/*.java; do
  name=$(basename "$src" .java)
  dir=$(mktemp -d "/tmp/verify-java-$name.XXXX")
  {
    echo "//> using dep \"io.github.riccardomerolla:llm4zio-java:$VERSION\""
    echo "//> using repository ivy2Local"
    tail -n +2 "$src" # drop the example's own pinned-release dep line
  } > "$dir/$name.java"
  if (cd "$dir" && timeout 300 scala-cli --power compile --offline "$name.java" >"$dir/compile.log" 2>&1); then
    echo "  ✔ $name.java"
  else
    echo "  ✖ $name.java  (log: $dir/compile.log)"
    grep -aiE "error" "$dir/compile.log" | grep -aviE "Downloading|Failed to download|maven-metadata" | head -5 | sed 's/^/      /'
    FAILED=1
  fi
done

exit $FAILED
