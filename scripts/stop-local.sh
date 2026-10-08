#!/usr/bin/env bash
cd "$(dirname "$0")/.."
[ -f build/pids ] && xargs kill < build/pids 2>/dev/null; rm -f build/pids; echo stopped
