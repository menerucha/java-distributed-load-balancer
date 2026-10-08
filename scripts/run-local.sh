#!/usr/bin/env bash
# Starts 5 demo backends (ports 9001-9005, weights 3/2/1/1/1, backend-5 is slow) + the load balancer, no Docker needed.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f build/lb.jar ] || scripts/build.sh
mkdir -p build/logs; : > build/pids
ALGO="${LB_ALGORITHM:-round_robin}"
for i in 1 2 3 4 5; do
  lat=5; [ "$i" = 5 ] && lat=40
  BACKEND_NAME="backend-$i" BACKEND_PORT="900$i" LATENCY_MS=$lat JITTER_MS=5 \
    setsid java -cp build/lb.jar lb.demo.DemoBackend </dev/null >"build/logs/backend$i.log" 2>&1 & echo $! >> build/pids
done
LB_ALGORITHM="$ALGO" LB_RATE_LIMIT_ENABLED="${LB_RATE_LIMIT_ENABLED:-false}" \
LB_BACKENDS="localhost:9001:3,localhost:9002:2,localhost:9003:1,localhost:9004:1,localhost:9005:1" \
  setsid java -jar build/lb.jar </dev/null >build/logs/lb.log 2>&1 & echo $! >> build/pids
sleep 2
echo "Load balancer: http://localhost:8080/   metrics: http://localhost:9100/metrics   stop: scripts/stop-local.sh"
