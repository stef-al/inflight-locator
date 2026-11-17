#!/usr/bin/env bash
# Runs the engine tests after engine/build.sh.
#   engine/run.sh selftest
#   engine/run.sh mc --runs 400 --airports data/airports.csv --check
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
if command -v kotlin >/dev/null 2>&1 && [ ! -f "$ROOT/build/stdlib.path" ]; then
  exec kotlin -cp "$ROOT/build/classes" inflight.engine.TestMainKt "$@"
fi
exec java -Xmx2g -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$ROOT/build/classes:$(cat "$ROOT/build/stdlib.path")" inflight.engine.TestMainKt "$@"
