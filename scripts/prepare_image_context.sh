#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
destination="${1:?Usage: prepare_image_context.sh OUTPUT_DIRECTORY [JAR_DIRECTORY]}"
jar_directory="${2:-$task_root/target}"
shopt -s nullglob
jars=("$jar_directory/"*.jar)
test "${#jars[@]}" -eq 1 || { echo 'Expected exactly one tested application JAR'; exit 1; }
mkdir -p "$destination"
test -z "$(ls -A "$destination")" || { echo 'Image context must be empty'; exit 1; }
cp "${jars[0]}" "$destination/app.jar"
cp "$task_root/Dockerfile.runtime" "$destination/Dockerfile"
