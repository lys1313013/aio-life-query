#!/usr/bin/env bash
set -euo pipefail
image="${1:?Usage: smoke_image.sh IMAGE [PLATFORM]}"
run_arguments=(docker run)
if [ -n "${2:-}" ]; then run_arguments+=(--platform "$2"); fi
name="aio-life-query-smoke-$(date +%s)-$$"
task_temporary="$(mktemp -d)"
cleanup() {
  docker rm -f "$name" >/dev/null 2>&1 || true
  rm -rf "$task_temporary"
}
trap cleanup EXIT
"${run_arguments[@]}" -d --name "$name" -p 127.0.0.1::45680 \
  -e AIO_QUERY_MANAGEMENT_PORT=0 \
  -e AIO_QUERY_ACCESS_URL=http://127.0.0.1:9/unavailable \
  -e AIO_QUERY_HASURA_URL=http://127.0.0.1:9/unavailable \
  -e AIO_QUERY_SERVICE_KEY=fixture-service-key-at-least-32-characters \
  -e AIO_QUERY_WEBHOOK_KEY=fixture-webhook-key-at-least-32-characters \
  "$image" > /dev/null
port="$(docker port "$name" 45680 | awk -F: '{print $NF}')"
ready=false
for attempt in {1..60}; do
  status="$(curl --silent --output "$task_temporary/response.json" --write-out '%{http_code}' "http://127.0.0.1:$port/api/v1/datasets" || true)"
  if [ "$status" = 401 ]; then ready=true; break; fi
  if [ "$(docker inspect --format '{{.State.Running}}' "$name")" != true ]; then break; fi
  sleep 1
done
test "$ready" = true || { docker logs "$name"; echo 'Image startup or REST authentication check failed'; exit 1; }
python3 - "$task_temporary/response.json" <<'PY'
import json, sys
assert json.load(open(sys.argv[1]))['rscode'] == 'AUTH_REQUIRED'
PY
test "$(curl --silent --output "$task_temporary/response.json" --write-out '%{http_code}' \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' "http://127.0.0.1:$port/mcp")" = 401
python3 - "$task_temporary/response.json" <<'PY'
import json, sys
assert json.load(open(sys.argv[1]))['rscode'] == 'AUTH_REQUIRED'
PY
test "$(curl --silent --output "$task_temporary/response.json" --write-out '%{http_code}' \
  -H 'Authorization: Bearer fixture-invalid-credential' "http://127.0.0.1:$port/api/v1/datasets")" = 503
python3 - "$task_temporary/response.json" <<'PY'
import json, sys
assert json.load(open(sys.argv[1]))['rscode'] == 'UPSTREAM_UNAVAILABLE'
PY
test "$(docker exec "$name" id -u)" = 10001
docker exec "$name" sh -c 'test -w /app/audit && test "$(date +%Z)" = CST && test "$(cat /proc/1/comm)" = java'
echo 'Runtime image verified: REST/MCP require authentication, dependency failure denied, audit writable, non-root Java PID 1, Asia/Shanghai timezone.'
