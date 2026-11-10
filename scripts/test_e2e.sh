#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ -n "${AIO_QUERY_JAVA_HOME:-}" ]; then export JAVA_HOME="$AIO_QUERY_JAVA_HOME"; export PATH="$JAVA_HOME/bin:$PATH"; fi
life_root="${AIO_LIFE_SOURCE:-$task_root/../aio-life-server}"
test_dir="$task_root/.cache/e2e"
test_name="aio-life-query-e2e-$(date +%s)-$$"
connector_pid=""
export AIO_QUERY_TEST_GATEWAY_PORT="${AIO_QUERY_TEST_GATEWAY_PORT:-45680}"
export AIO_QUERY_TEST_HASURA_PORT="${AIO_QUERY_TEST_HASURA_PORT:-45682}"
export AIO_QUERY_TEST_CONNECTOR_PORT="${AIO_QUERY_TEST_CONNECTOR_PORT:-45683}"
export AIO_QUERY_TEST_HASURA_URL="http://127.0.0.1:$AIO_QUERY_TEST_HASURA_PORT/graphql"
cleanup() {
  if [ -n "$connector_pid" ]; then kill "$connector_pid" 2>/dev/null || true; fi
  docker rm -f "$test_name-hasura" "$test_name-postgres" "$test_name-redis" >/dev/null 2>&1 || true
}
trap cleanup EXIT
mkdir -p "$test_dir"
test -f "$task_root/.cache/connector/quarkus-app/quarkus-run.jar" || { echo '先执行 scripts/build_connector.sh'; exit 1; }
test -f "$life_root/pom.xml" || { echo '设置 AIO_LIFE_SOURCE 指向配套 aio-life-server'; exit 1; }
python3 - <<'PY'
import socket, os
for port in [int(os.environ[name]) for name in ["AIO_QUERY_TEST_GATEWAY_PORT","AIO_QUERY_TEST_HASURA_PORT","AIO_QUERY_TEST_CONNECTOR_PORT"]]:
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        s.bind(('127.0.0.1',port))
PY
docker run -d --name "$test_name-postgres" -p 127.0.0.1::5432 \
  -e POSTGRES_PASSWORD=aio-life-query-fixture-root -e POSTGRES_DB=aio_life \
  postgres:18-alpine \
  -c statement_timeout=5000 >/dev/null
docker run -d --name "$test_name-redis" -p 127.0.0.1::6379 \
  redis@sha256:520775a41a63e77e06c73e35d2fd9cc15921a609516818796b4ecbb813078bc7 >/dev/null
postgres_port="$(docker port "$test_name-postgres" 5432 | awk -F: '{print $NF}')"
redis_port="$(docker port "$test_name-redis" 6379 | awk -F: '{print $NF}')"
ready=false
for attempt in {1..60}; do
  if docker exec "$test_name-postgres" psql -U postgres -d aio_life -c 'SELECT 1' >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
test "$ready" = true || { echo 'PostgreSQL 未就绪'; exit 1; }
export FLYWAY_URL="jdbc:postgresql://127.0.0.1:$postgres_port/aio_life"
export FLYWAY_USER=postgres FLYWAY_PASSWORD=aio-life-query-fixture-root
(cd "$life_root" && mvn -B -ntp -f tools/flyway/pom.xml compile flyway:migrate flyway:validate)
python3 - "$task_root/deploy/readonly-account.sql.example" <<'PYSQL' > "$test_dir/grants.sql"
from pathlib import Path
import sys
print(Path(sys.argv[1]).read_text().replace('REPLACE_WITH_RANDOM_PASSWORD','fixture-query-read-password'))
PYSQL
docker exec -i "$test_name-postgres" psql -v ON_ERROR_STOP=1 -U postgres -d aio_life < "$test_dir/grants.sql"
for rejected in 'UPDATE time_record SET duration=duration WHERE 1=0' 'SELECT password FROM "user" LIMIT 0' 'UPDATE performance SET ticket_price=ticket_price WHERE 1=0'; do
  if docker exec "$test_name-postgres" psql -U aio_query_read -d aio_life -c "$rejected" >/dev/null 2>&1; then
    echo '只读列权限验证失败'; exit 1
  fi
done
export AIO_QUERY_WEBHOOK_KEY=fixture-webhook-key-at-least-32-characters
export AIO_QUERY_CONNECTOR_KEY=fixture-connector-key-at-least-32-characters
export AIO_QUERY_CONNECTOR_URL="http://host.docker.internal:$AIO_QUERY_TEST_CONNECTOR_PORT"
export AIO_QUERY_AUTH_WEBHOOK_URL="http://host.docker.internal:$AIO_QUERY_TEST_GATEWAY_PORT/internal/hasura/auth"
python3 "$task_root/scripts/build_metadata.py" --output "$test_dir/metadata"
AIO_QUERY_TEST_JDBC_URL="jdbc:postgresql://127.0.0.1:$postgres_port/aio_life?user=aio_query_read&password=fixture-query-read-password&sslmode=disable" \
  java --class-path "$task_root/.cache/connector/quarkus-app/lib/main/*" "$task_root/scripts/ConnectorDeadlineCheck.java" "$test_dir/metadata/configuration.json"
HASURA_CONFIGURATION_DIRECTORY="$test_dir/metadata" \
  HASURA_SERVICE_TOKEN_SECRET="$AIO_QUERY_CONNECTOR_KEY" \
  JDBC_URL="jdbc:postgresql://127.0.0.1:$postgres_port/aio_life?user=aio_query_read&password=fixture-query-read-password&sslmode=disable" \
  QUARKUS_HTTP_PORT="$AIO_QUERY_TEST_CONNECTOR_PORT" QUARKUS_HTTP_HOST=0.0.0.0 QUARKUS_OTEL_SDK_DISABLED=true QUARKUS_HTTP_ACCESS_LOG_ENABLED=false \
  java -jar "$task_root/.cache/connector/quarkus-app/quarkus-run.jar" > "$test_dir/connector.log" 2>&1 &
connector_pid=$!
docker run -d --name "$test_name-hasura" --add-host=host.docker.internal:host-gateway \
  -p "127.0.0.1:$AIO_QUERY_TEST_HASURA_PORT:3000" -v "$test_dir/metadata:/metadata:ro" \
  ghcr.io/hasura/v3-engine@sha256:7f30c6697a3921eb936002b44a32ab01efdd892a8b9af236cad2f81e0a1b1d91 \
  --metadata-path /metadata/open_dd.json --authn-config-path /metadata/auth_config.json \
  --ndc-response-size-limit 1000000 --request-body-limit 65536 > /dev/null
ready=false
for attempt in {1..60}; do
  if curl --fail --silent -H "Authorization: Bearer $AIO_QUERY_CONNECTOR_KEY" "http://127.0.0.1:$AIO_QUERY_TEST_CONNECTOR_PORT/capabilities" > "$test_dir/capabilities.json" && curl --fail --silent "http://127.0.0.1:$AIO_QUERY_TEST_HASURA_PORT/health" > /dev/null; then ready=true; break; fi
  sleep 1
done
test "$ready" = true || { docker logs "$test_name-hasura" > "$test_dir/hasura.log" 2>&1; echo '引擎或连接器未就绪，检查 .cache/e2e'; exit 1; }
(cd "$task_root" && AIO_QUERY_TEST_REDIS_PORT="$redis_port" mvn -B package)
export AIO_QUERY_E2E=1
export AIO_QUERY_TEST_GATEWAY_JAR="$task_root/target/aio-life-query-0.1.0-SNAPSHOT.jar"
export AIO_LIFE_DB_URL="127.0.0.1:$postgres_port" AIO_LIFE_DB_USERNAME=postgres AIO_LIFE_DB_PASSWORD=aio-life-query-fixture-root
export AIO_LIFE_REDIS_HOST=127.0.0.1 AIO_LIFE_REDIS_PORT="$redis_port" AIO_LIFE_REDIS_PASSWORD= AIO_LIFE_NEO4J_ENABLED=false
(cd "$life_root" && mvn -B -Dtest=QueryAccessHttpIntegrationTest,QueryHasuraEndToEndTest,PersistenceArchitectureTest \
  -Dspring.datasource.url="jdbc:postgresql://127.0.0.1:$postgres_port/aio_life?stringtype=unspecified" \
  -Dspring.datasource.username=postgres -Dspring.datasource.password=aio-life-query-fixture-root -Dspring.data.redis.host=127.0.0.1 \
  -Dspring.data.redis.port="$redis_port" -Dspring.data.redis.password= test)
docker logs "$test_name-hasura" > "$test_dir/hasura.log" 2>&1
echo '六数据集端到端测试完成；隔离容器会自动清理。'
