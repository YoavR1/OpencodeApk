#!/usr/bin/env bash
# Places the PTY shim where the Node bundle will resolve it.
#
# The bundle lives in packages/opencode/dist/node, which is build output, so the
# shim is copied in rather than committed there.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEST="$ROOT/packages/opencode/dist/node/node_modules/@lydell"
mkdir -p "$DEST"
rm -rf "$DEST/node-pty"
cp -r "$ROOT/spike/m6/shim/@lydell/node-pty" "$DEST/node-pty"
echo "shim installed at $DEST/node-pty"
