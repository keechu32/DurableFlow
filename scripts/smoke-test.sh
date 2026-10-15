#!/usr/bin/env bash
set -euo pipefail
for port in 8080 8081 8082; do
  for probe in health health/liveness health/readiness; do
    body=$(curl --fail --silent --show-error --max-time 5 "http://localhost:${port}/actuator/${probe}")
    python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"' <<< "$body"
    echo "PASS port=${port} probe=${probe}"
  done
done
