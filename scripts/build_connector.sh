#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
revision=7ea80f966e7abfeaba56957291ff555649e21809
archive_sha=969590bc77db018cf9d5d51be28612d37445c96a9016405e4a2e5ae5ada5de46
task_cache="$task_root/.cache/connector-source"
mkdir -p "$task_cache"
archive="$task_cache/source.tar.gz"
if [ ! -f "$archive" ]; then
  curl --fail --location --retry 3 "https://codeload.github.com/hasura/ndc-jvm-mono/tar.gz/$revision" -o "$archive"
fi
python3 - "$archive" "$archive_sha" <<'PY'
import hashlib,sys
assert hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest()==sys.argv[2], 'Upstream archive checksum mismatch'
PY
source_dir="$task_cache/ndc-jvm-mono-$revision"
patch_sha="$(python3 -c 'import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$task_root/connector/mysql-readonly.patch")"
if [ ! -d "$source_dir" ]; then
  tar -xzf "$archive" -C "$task_cache"
  (cd "$source_dir" && patch -p1 < "$task_root/connector/mysql-readonly.patch")
  printf '%s' "$patch_sha" > "$source_dir/.aio-query-patch-sha"
fi
test "$(cat "$source_dir/.aio-query-patch-sha")" = "$patch_sha" || { echo '补丁已改变，请移走 .cache/connector-source 后重新构建'; exit 1; }
cd "$source_dir"
set --
if [ -n "${AIO_QUERY_JAVA_HOME:-${JAVA_HOME:-}}" ]; then set -- "-Dorg.gradle.java.home=${AIO_QUERY_JAVA_HOME:-$JAVA_HOME}"; fi
"${AIO_QUERY_GRADLE:-./gradlew}" "$@" :ndc-connector-mysql:build -x test --no-daemon --console=plain
mkdir -p "$task_root/.cache/connector"
cp -R ndc-connector-mysql/build/quarkus-app "$task_root/.cache/connector/"
printf 'Connector built from %s; runtime is .cache/connector/quarkus-app/quarkus-run.jar\n' "$revision"
