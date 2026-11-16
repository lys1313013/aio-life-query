#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
docker build -f "$task_root/deploy/engine.Dockerfile" \
  -t "${AIO_QUERY_ENGINE_IMAGE:-aio-life-query-hasura:local}" "$task_root"
