#!/usr/bin/env bash
#
# Statement coverage for llm4zio.
#
# sbt-scoverage has no sbt 2.0 build yet, so we use Scala 3's native `-coverage-out`
# instrumentation (applied via `set`, since a `-D` would only reach the sbt *client*,
# not the server JVM that runs the build) and summarise the scoverage data ourselves.
#
#   scripts/coverage.sh [data-dir]   # default: /tmp/llm4zio-cov
#
# Runs unit + integration tests instrumented, then prints overall % and the
# lowest-covered files. Requires scala-cli.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIR="${1:-/tmp/llm4zio-cov}"
rm -rf "$DIR"; mkdir -p "$DIR/core" "$DIR/flow" "$DIR/runner"

sbt -batch -Dsbt.log.noformat=true \
  "; set llm4zioCore/Compile/scalacOptions += \"-coverage-out:$DIR/core\" \
   ; set llm4zioFlow/Compile/scalacOptions += \"-coverage-out:$DIR/flow\" \
   ; set llm4zioRunner/Compile/scalacOptions += \"-coverage-out:$DIR/runner\" \
   ; clean ; testFull ; llm4zioFlow/It/testFull ; llm4zioRunner/It/testFull"

scala-cli run "$SCRIPT_DIR/coverage-report.sc" -- "$DIR" core flow runner
