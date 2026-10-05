#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
revision=45729d9437d93a2b54fc592a10e29e8267d4dfa7
source_dir="$task_root/.cache/engine-source"
if [ ! -d "$source_dir/.git" ]; then
  mkdir -p "$source_dir"
  git -C "$source_dir" init
  git -C "$source_dir" remote add origin https://github.com/hasura/graphql-engine.git
fi
git -C "$source_dir" fetch --depth 1 origin "$revision"
git -C "$source_dir" checkout --detach "$revision"
docker build -t "aio-life-query-hasura:$revision" -f "$task_root/deploy/engine.Dockerfile" "$source_dir/v3"
