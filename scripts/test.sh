#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d build/test-classes ] || scripts/build.sh
exec java -cp build/classes:build/test-classes lb.TestRunner
