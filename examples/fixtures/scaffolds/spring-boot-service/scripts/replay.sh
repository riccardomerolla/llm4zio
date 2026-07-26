#!/bin/sh
# The pack's replay contract: one equivalence vector as JSON on stdin, a JSON array of
# observations on stdout — nothing else on stdout, ever (build noise goes to stderr).
# Delegates to the ReplayHarness the implementation provides (see the pack's implement prompt).
set -e
cd "$(dirname "$0")/.."
[ -d target/classes ] || mvn -q -B -DskipTests compile >&2
[ -f target/cp.txt ] || mvn -q -B dependency:build-classpath -Dmdep.outputFile=target/cp.txt >&2
exec java -cp "target/classes:$(cat target/cp.txt)" com.meridian.replay.ReplayHarness
