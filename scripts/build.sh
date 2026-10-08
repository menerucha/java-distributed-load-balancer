#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf build && mkdir -p build/classes build/test-classes
javac -d build/classes $(find src/main -name '*.java')
jar cfe build/lb.jar lb.Main -C build/classes .
javac -cp build/classes -d build/test-classes $(find src/test -name '*.java') 2>&1 | grep -v '^Note:' || true
echo "Built build/lb.jar"
