#!/usr/bin/env bash
# Usage: scripts/bench.sh [--requests 5000 --concurrency 50 ...]   (defaults target the local stack)
cd "$(dirname "$0")/.."
exec java -cp build/lb.jar lb.bench.Benchmark --url http://localhost:8080/ --admin http://localhost:9100 \
  --weights backend-1=3,backend-2=2,backend-3=1,backend-4=1,backend-5=1 "$@"
