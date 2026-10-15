#!/usr/bin/env bash
set -euo pipefail
for port in 8080 8081; do
  for probe in health health/liveness health/readiness; do
    body=$(curl --fail --silent --show-error --max-time 5 "http://localhost:${port}/actuator/${probe}")
    python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"' <<< "$body"
    echo "PASS port=${port} probe=${probe}"
  done
done
workers=$(docker compose ps -q worker)
worker_count=$(docker compose ps -q worker | wc -l | tr -d ' ')
[ "$worker_count" -eq "${DURABLEFLOW_EXPECTED_WORKERS:-3}" ]
for id in $workers; do
  for probe in health health/liveness health/readiness; do
    docker exec "$id" curl --fail --silent --max-time 5 "http://localhost:8080/actuator/${probe}" | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'
    echo "PASS worker=${id:0:12} probe=${probe}"
  done
done
