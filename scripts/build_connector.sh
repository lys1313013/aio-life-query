#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
docker build -f "$task_root/deploy/connector.Dockerfile" \
  -t "${AIO_QUERY_CONNECTOR_IMAGE:-aio-life-query-postgresql-connector:local}" "$task_root"
