#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ -z "${QUERY_BUNDLE_VERSION:-}" ]]; then
  QUERY_BUNDLE_VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/query-service/pom.xml" | head -1)"
fi
JAR="$ROOT/query-service/target/com.jvmind.mat.query-$QUERY_BUNDLE_VERSION.jar"
DEST="$ROOT/mcp-server/src/jvmind_mat_mcp/vendor"

if [[ ! -f "$JAR" ]]; then
  mvn -f "$ROOT/query-service/pom.xml" clean package
fi

mkdir -p "$DEST"
cp "$JAR" "$DEST/"
echo "Vendored $(basename "$JAR") -> $DEST"
