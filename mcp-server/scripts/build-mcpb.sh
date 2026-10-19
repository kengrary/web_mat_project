#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="$(sed -n 's/^version = "\(.*\)"$/\1/p' "$ROOT/mcp-server/pyproject.toml" | head -1)"
DIST="$ROOT/mcp-server/dist"

if ! command -v npx >/dev/null 2>&1; then
  echo "npx is required (Node.js 18+)." >&2
  exit 1
fi
if ! command -v zip >/dev/null 2>&1; then
  echo "zip is required for the Smithery-specific bundle." >&2
  exit 1
fi

stage() {
  local dir="$1"
  local manifest="$2"
  rm -rf "$dir"
  mkdir -p "$dir"
  cp -R "$ROOT/mcp-server/src" "$dir/src"
  cp "$ROOT/mcp-server/pyproject.toml" "$dir/"
  cp "$ROOT/mcp-server/README.md" "$dir/"
  cp "$ROOT/mcp-server/NOTICE" "$dir/"
  cp "$ROOT/mcp-server/LICENSE" "$dir/"
  cp "$ROOT/mcp-server/uv.lock" "$dir/"
  cp "$manifest" "$dir/manifest.json"
  find "$dir" -name "__pycache__" -type d -prune -exec rm -rf {} +
  find "$dir" -name "*.egg-info" -type d -prune -exec rm -rf {} +
  find "$dir" -name "*.pyc" -delete
}

mkdir -p "$DIST"

GENERAL_STAGE="$ROOT/mcp-server/build/mcpb/general"
stage "$GENERAL_STAGE" "$ROOT/marketplace/smithery/manifest.json"
npx --yes @anthropic-ai/mcpb validate "$GENERAL_STAGE/manifest.json"
npx --yes @anthropic-ai/mcpb pack "$GENERAL_STAGE" "$DIST/jvmind-mat-mcp-$VERSION.mcpb"

# Smithery's server-card API requires an inputSchema on every tool, but the
# MCPB manifest schema does not allow that key. The Smithery archive therefore
# uses the extended manifest and is zipped directly.
SMITHERY_STAGE="$ROOT/mcp-server/build/mcpb/smithery"
stage "$SMITHERY_STAGE" "$ROOT/marketplace/smithery/manifest.smithery.json"
(
  cd "$SMITHERY_STAGE"
  rm -f "$DIST/jvmind-mat-smithery-$VERSION.mcpb"
  zip -qr "$DIST/jvmind-mat-smithery-$VERSION.mcpb" \
    manifest.json src pyproject.toml README.md NOTICE LICENSE uv.lock
)
unzip -t "$DIST/jvmind-mat-smithery-$VERSION.mcpb" >/dev/null

npx --yes @anthropic-ai/mcpb info "$DIST/jvmind-mat-mcp-$VERSION.mcpb"
echo "Built $DIST/jvmind-mat-mcp-$VERSION.mcpb"
echo "Built $DIST/jvmind-mat-smithery-$VERSION.mcpb"
