#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="$(sed -n 's/^version = "\(.*\)"$/\1/p' "$ROOT/mcp-server/pyproject.toml" | head -1)"
STAGE="$ROOT/mcp-server/build/mcpb/jvmind-mat"
OUT="$ROOT/mcp-server/dist/jvmind-mat-mcp-$VERSION.mcpb"

if ! command -v npx >/dev/null 2>&1; then
  echo "npx is required (Node.js 18+)." >&2
  exit 1
fi

rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -R "$ROOT/mcp-server/src" "$STAGE/src"
cp "$ROOT/mcp-server/pyproject.toml" "$STAGE/"
cp "$ROOT/mcp-server/README.md" "$STAGE/"
cp "$ROOT/mcp-server/NOTICE" "$STAGE/"
cp "$ROOT/mcp-server/uv.lock" "$STAGE/"
cp "$ROOT/marketplace/smithery/manifest.json" "$STAGE/manifest.json"

find "$STAGE" -name "__pycache__" -type d -prune -exec rm -rf {} +
find "$STAGE" -name "*.egg-info" -type d -prune -exec rm -rf {} +
find "$STAGE" -name "*.pyc" -delete

npx --yes @anthropic-ai/mcpb validate "$STAGE/manifest.json"
mkdir -p "$ROOT/mcp-server/dist"
npx --yes @anthropic-ai/mcpb pack "$STAGE" "$OUT"
npx --yes @anthropic-ai/mcpb info "$OUT"
echo "Built $OUT"
