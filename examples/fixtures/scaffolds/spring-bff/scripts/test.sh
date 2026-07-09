#!/usr/bin/env bash
# Gate command for the spring-bff scaffold: backend tests, then frontend tests.
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -B test
(cd frontend && npm test)
