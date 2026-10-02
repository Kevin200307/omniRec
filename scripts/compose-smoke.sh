#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Smoke test of the deploy/ profiles: build, start, wait for health, post an
# event, check the answer, stop. Usage: scripts/compose-smoke.sh [lite|standard ...]
# Uses host port 18081 so it doesn't collide with other stacks.
set -uo pipefail
cd "$(dirname "$0")/.."
export OMNIREC_PORT=18081
status=0

for profile in "${@:-lite standard}"; do
  for p in $profile; do
    file="deploy/$p/docker-compose.yml"
    echo "== $p"
    built=no
    for attempt in 1 2 3; do
      docker compose -f "$file" build >/tmp/omnirec-smoke-build.log 2>&1 && { built=yes; break; }
      echo "   build attempt $attempt failed (network?), retrying"
    done
    [ "$built" = yes ] || { echo "FAIL $p: image did not build"; tail -20 /tmp/omnirec-smoke-build.log; status=1; continue; }
    # --wait blocks until every service is healthy; one retry covers a broker
    # that was slow to pass its first health checks.
    health=unhealthy
    for attempt in 1 2; do
      if docker compose -f "$file" up -d --wait --wait-timeout 300 >/tmp/omnirec-smoke-up.log 2>&1; then
        health=healthy
        break
      fi
      echo "   start attempt $attempt did not become healthy: $(tail -1 /tmp/omnirec-smoke-up.log)"
    done
    now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    answer=$(curl -s -w ' %{http_code}' -X POST "http://localhost:$OMNIREC_PORT/v1/events" \
      -H "Content-Type: application/json" -H "Origin: http://localhost:3000" -H "X-Omnirec-Key: pk_test_demo_store" \
      -d "{\"events\":[{\"eventId\":\"smoke_$p\",\"event\":\"product_viewed\",\"schemaVersion\":\"2.0\",\"timestamp\":\"$now\",\"identity\":{\"anonymousId\":\"a1\",\"sessionId\":\"s1\"},\"context\":{},\"data\":{\"product\":{\"id\":\"p1\"}}}]}")
    if [ "$health" = healthy ] && [[ "$answer" == *'"accepted":1'*' 202' ]]; then
      echo "PASS $p: healthy, event accepted"
    else
      echo "FAIL $p: health=$health answer=$answer"
      docker compose -f "$file" logs event-api | tail -30
      status=1
    fi
    docker compose -f "$file" down -v >/dev/null 2>&1
  done
done
exit $status
