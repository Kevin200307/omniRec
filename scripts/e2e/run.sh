#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Live end-to-end run of the Definition of Done, with every component real:
#
#   @omnirec/commerce-web (built) -> Event API (jar) -> RabbitMQ -> Amazon adapter
#     -> real AWS SDK, SigV4-signed PutEvents -> local capture server
#
# The capture server stands in for Personalize itself, so no AWS account is
# needed. Point AWS_PERSONALIZE_ENDPOINT at nothing and supply real credentials
# to run the same journey against the real service.
#
# Prerequisites: Docker running; `npx turbo run build` and `mvn -f backend install` done.
# Uses ports 25672 / 26379 / 4566 / 8124 so it won't collide with other stacks.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="$(mktemp -d)"
cleanup() {
  kill "${API_PID:-}" "${CAPTURE_PID:-}" 2>/dev/null || true
  docker rm -f omnirec-e2e-rabbit omnirec-e2e-redis >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run -d --name omnirec-e2e-redis -p 26379:6379 redis:7-alpine >/dev/null
# Readiness comes from the server log, not `docker exec rabbitmq-diagnostics`:
# the CLI runs as root, and if it fires before the server has initialised it
# creates /var/lib/rabbitmq/.erlang.cookie owned by root, the server (running as
# the rabbitmq user) then cannot read its own cookie, and dies with EACCES.
start_rabbit() {
  for attempt in 1 2 3; do
    docker rm -f omnirec-e2e-rabbit >/dev/null 2>&1 || true
    docker run -d --name omnirec-e2e-rabbit -p 25672:5672 rabbitmq:3.13-management-alpine >/dev/null
    for _ in $(seq 1 45); do
      docker logs omnirec-e2e-rabbit 2>&1 | grep -q "Server startup complete" && return 0
      [ "$(docker inspect -f "{{.State.Running}}" omnirec-e2e-rabbit)" = "true" ] || break
      sleep 2
    done
    echo "RabbitMQ failed to start (attempt $attempt), retrying..." >&2
  done
  return 1
}
start_rabbit || { echo "RabbitMQ would not start"; exit 1; }

node scripts/e2e/capture-server.mjs "$OUT/captured.json" >"$OUT/capture.log" 2>&1 & CAPTURE_PID=$!

AWS_ACCESS_KEY_ID=e2e AWS_SECRET_ACCESS_KEY=e2e java -jar backend/omnirec-event-api-app/target/omnirec-event-api-app-*.jar \
  --server.port=8124 --spring.rabbitmq.port=25672 \
  --omnirec.state.redis.enabled=true --omnirec.state.redis.port=26379 \
  --omnirec.destinations.amazon-personalize.enabled=true \
  --omnirec.destinations.amazon-personalize.tracking-id=trk-e2e \
  --omnirec.destinations.amazon-personalize.endpoint-override=http://localhost:4566 \
  --omnirec.destinations.recently-viewed.enabled=true --omnirec.destinations.recently-viewed.port=26379 \
  --omnirec.destinations.amazon-personalize.region=us-east-1 >"$OUT/api.log" 2>&1 & API_PID=$!
for _ in $(seq 1 90); do
  grep -q "Started OmnirecEventApiApplication" "$OUT/api.log" && break
  grep -q "FAILED TO START" "$OUT/api.log" && { cat "$OUT/api.log"; exit 1; }
  sleep 2
done

node scripts/e2e/drive-sdk.mjs packages/commerce-web/dist/index.js
sleep 4

node -e '
const c = require(process.argv[1]);
let failed = false;
const check = (ok, msg) => { console.log((ok ? "PASS " : "FAIL ") + msg); if (!ok) failed = true; };
const events = c.flatMap(r => r.body.eventList.map(e => ({ ...e, userId: r.body.userId, sessionId: r.body.sessionId, signed: r.signed })));
const byItem = id => events.find(e => e.eventType === "product_viewed" && e.itemId === id);
check(c.length > 0 && c.every(r => r.signed), "PutEvents calls are SigV4-signed by the real AWS SDK");
check(byItem("p1") && !byItem("p1").userId, "day 1 anonymous: userId omitted");
check(byItem("p2") && !byItem("p2").userId, "day 2 anonymous: userId omitted");
check(byItem("p1").sessionId !== byItem("p2").sessionId, "day 1 and day 2 are different sessions");
check(byItem("p3") && byItem("p3").userId === "customer_123", "after login: userId = customer_123");
check(!events.some(e => (e.properties || "").includes("4111")), "no card data reached the provider");
const p1Views = events.filter(e => e.eventType === "product_viewed" && e.itemId === "p1").length;
check(p1Views === 1, "dwell-time follow-ups are not counted as extra views (p1 views: " + p1Views + ")");
process.exit(failed ? 1 : 0);' "$OUT/captured.json"

LINK=$(docker exec omnirec-e2e-redis redis-cli --scan --pattern 'identity:anon:*' | head -1)
[ "$(docker exec omnirec-e2e-redis redis-cli get "$LINK" | tr -d '\r')" = "customer_123" ] \
  && echo "PASS identity link anon -> customer_123 stored in Redis" \
  || { echo "FAIL identity link missing"; exit 1; }

# Only the view after login belongs in the user's list: history is not backfilled.
RV=$(docker exec omnirec-e2e-redis redis-cli lrange recently-viewed:customer_123 0 -1 | tr -d '\r' | paste -sd, -)
[ "$RV" = '"p3"' ] \
  && echo "PASS recently-viewed for customer_123 fed by the pipeline: [$RV]" \
  || { echo "FAIL recently-viewed was [$RV], expected [\"p3\"]"; exit 1; }
