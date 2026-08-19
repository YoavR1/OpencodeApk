#!/usr/bin/env bash
# M6: run the OpenCode Node build on a connected Android arm64 phone.
#
# Everything is staged under /data/local/tmp/m6 and can be removed with
#   adb shell rm -rf /data/local/tmp/m6
#
# This runs as the `shell` user, which is deliberate: it answers "does this
# runtime work on Android arm64", separately from "how would an app package it".
# The second question is answered by RuntimeFeasibilityTest, which runs as the
# app's own uid and measures the W^X constraint that decides the packaging.
#
# Prerequisites:
#   python spike/m6/fetch-node-android.py          # the runtime
#   bun run --cwd packages/opencode script/build-node.ts   # the server bundle
#
# Usage: spike/m6/run-on-device.sh [port]
set -uo pipefail

PORT="${1:-4600}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REMOTE=/data/local/tmp/m6
STAGE="$ROOT/spike/m6/staging/android-node/data/data/com.termux/files/usr"
DIST="$ROOT/packages/opencode/dist/node"

# Git Bash rewrites /data/... into a Windows path without this.
export MSYS_NO_PATHCONV=1

say() { printf '%s\n' "$*"; }
head2() { printf '\n== %s ==\n' "$*"; }

if [ "$(adb devices | tail -n +2 | grep -c 'device$')" -eq 0 ]; then
  say "ENV: no device. Check the cable and that USB debugging is still authorised."
  exit 2
fi

[ -x "$STAGE/bin/node" ] || { say "ENV: no runtime. Run spike/m6/fetch-node-android.py first."; exit 2; }
[ -f "$DIST/node.js" ] || { say "ENV: no bundle. Run the build-node script first."; exit 2; }

head2 "device"
adb shell getprop ro.product.model
adb shell getprop ro.product.cpu.abi

head2 "staging"
adb shell "rm -rf $REMOTE; mkdir -p $REMOTE/app/node_modules/@lydell/node-pty"
adb push "$STAGE/bin/node" "$REMOTE/node" | tail -1
adb push "$STAGE/lib" "$REMOTE/" | tail -1
adb push "$DIST/node.js" "$REMOTE/app/node.js" | tail -1
for wasm in "$DIST"/*.wasm; do adb push "$wasm" "$REMOTE/app/" >/dev/null; done
adb push "$ROOT/spike/m6/shim/@lydell/node-pty/package.json" "$REMOTE/app/node_modules/@lydell/node-pty/" >/dev/null
adb push "$ROOT/spike/m6/shim/@lydell/node-pty/index.js" "$REMOTE/app/node_modules/@lydell/node-pty/" >/dev/null
adb push "$ROOT/spike/m6/serve-node.mjs" "$REMOTE/app/" >/dev/null
adb shell "chmod 755 $REMOTE/node"

head2 "runtime"
adb shell "LD_LIBRARY_PATH=$REMOTE/lib $REMOTE/node --version"

head2 "Q3 — can it launch a shell command?"
adb shell "LD_LIBRARY_PATH=$REMOTE/lib $REMOTE/node -e \"console.log(require('child_process').execSync('echo shell-from-node').toString().trim())\""

head2 "Q2 — can it do a filesystem operation?"
adb shell "LD_LIBRARY_PATH=$REMOTE/lib $REMOTE/node -e \"const fs=require('fs');const p='$REMOTE/probe.txt';fs.writeFileSync(p,'fs-ok');console.log(fs.readFileSync(p,'utf8'));fs.unlinkSync(p)\""

head2 "Q1 — can it serve OpenCode on loopback?"
adb shell "cd $REMOTE/app && LD_LIBRARY_PATH=$REMOTE/lib nohup $REMOTE/node serve-node.mjs $PORT ./node.js > $REMOTE/server.log 2>&1 &"
sleep 20
adb shell "cat $REMOTE/server.log"

say ""
say "health:"
adb shell "toybox wget -qO- http://127.0.0.1:$PORT/global/health 2>/dev/null || curl -s -m 10 http://127.0.0.1:$PORT/global/health"
say ""

head2 "cleanup"
say "adb shell \"pkill -f serve-node.mjs; rm -rf $REMOTE\""
