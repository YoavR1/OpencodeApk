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
#   python spike/m6/fetch-node-android.py                  # the runtime
#   bun run --cwd packages/opencode script/build-node.ts   # the server bundle
#
# Usage: spike/m6/run-on-device.sh [port]
set -uo pipefail

PORT="${1:-4600}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REMOTE=/data/local/tmp/m6
STAGE="$ROOT/spike/m6/staging/android-node/data/data/com.termux/files/usr"
DIST="$ROOT/packages/opencode/dist/node"

# Without this, Git Bash rewrites /data/... into a Windows path and adb goes
# looking for the device paths under C:/Program Files/Git.
export MSYS_NO_PATHCONV=1

# The same setting stops Git Bash converting *local* paths for a Windows adb,
# so those are converted explicitly. cygpath does not exist elsewhere, where the
# path is already in the right form.
localpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

say() { printf '%s\n' "$*"; }
head2() { printf '\n== %s ==\n' "$*"; }

if [ "$(adb devices | tail -n +2 | grep -c 'device$')" -eq 0 ]; then
  say "ENV: no device. Check the cable and that USB debugging is still authorised."
  exit 2
fi

# -f rather than -x: Windows does not preserve the executable bit through
# extraction, and the device gets an explicit chmod below regardless.
[ -f "$STAGE/bin/node" ] || { say "ENV: no runtime. Run spike/m6/fetch-node-android.py first."; exit 2; }
[ -f "$DIST/node.js" ] || { say "ENV: no bundle. Run the build-node script first."; exit 2; }

head2 "device"
adb shell getprop ro.product.model
adb shell getprop ro.product.cpu.abi

head2 "staging"
adb shell "rm -rf $REMOTE; mkdir -p $REMOTE/app/node_modules/@lydell/node-pty"
adb push "$(localpath "$STAGE/bin/node")" "$REMOTE/node" | tail -1
adb push "$(localpath "$STAGE/lib")" "$REMOTE/" | tail -1
adb push "$(localpath "$DIST/node.js")" "$REMOTE/app/node.js" | tail -1
for wasm in "$DIST"/*.wasm; do
  adb push "$(localpath "$wasm")" "$REMOTE/app/" >/dev/null
done
adb push "$(localpath "$ROOT/spike/m6/shim/@lydell/node-pty/package.json")" \
  "$REMOTE/app/node_modules/@lydell/node-pty/" >/dev/null
adb push "$(localpath "$ROOT/spike/m6/shim/@lydell/node-pty/index.js")" \
  "$REMOTE/app/node_modules/@lydell/node-pty/" >/dev/null
adb push "$(localpath "$ROOT/spike/m6/serve-node.mjs")" "$REMOTE/app/" >/dev/null

# jsonc-parser is the bundle's other external. It is pure JavaScript, so it just
# has to be present - unlike node-pty, which has no Android build at all.
JSONC="$(ls -d "$ROOT"/node_modules/.bun/jsonc-parser@*/node_modules/jsonc-parser 2>/dev/null | head -1)"
if [ -n "$JSONC" ]; then
  adb push "$(localpath "$JSONC")" "$REMOTE/app/node_modules/" >/dev/null
else
  say "WARN: jsonc-parser not found locally; the server will fail to load."
fi
adb shell "chmod 755 $REMOTE/node"

# The Termux build has OpenSSL's config path compiled in, pointing at Termux's
# own prefix. Outside Termux that path is unreadable and every crypto-touching
# invocation fails. An empty config is enough, and this is a packaging artifact
# of reusing someone else's build - one more reason a shipping product compiles
# its own Node (ADR-0022).
adb shell "printf '' > $REMOTE/openssl.cnf"
# HOME must point somewhere writable: OpenCode's bootstrap creates config and
# data directories under it, and with HOME unset "~" resolves to "/" and the
# mkdir fails. In the app this becomes filesDir - the same plumbing M7 will need.
adb shell "mkdir -p $REMOTE/home $REMOTE/tmp"
ENV="OPENSSL_CONF=$REMOTE/openssl.cnf LD_LIBRARY_PATH=$REMOTE/lib HOME=$REMOTE/home TMPDIR=$REMOTE/tmp XDG_CONFIG_HOME=$REMOTE/home/.config XDG_DATA_HOME=$REMOTE/home/.local/share"

head2 "Q4 - does the runtime execute on this phone?"
adb shell "$ENV $REMOTE/node -e \"console.log(process.version, process.arch, process.platform)\""

head2 "Q3 - can it launch a shell command?"
# The shell is passed explicitly: this Termux build has Termux's own sh compiled
# in as the default, which does not exist outside it. Another artifact of reusing
# someone else's build, and one a real integration has to handle - OpenCode
# spawns shell commands for its bash tool.
adb shell "$ENV $REMOTE/node -e \"console.log(require('child_process').execSync('echo shell-from-node',{shell:'/system/bin/sh'}).toString().trim())\""

head2 "Q2 - can it do a filesystem operation?"
adb shell "$ENV $REMOTE/node -e \"const fs=require('fs');const p='$REMOTE/probe.txt';fs.writeFileSync(p,'fs-ok');console.log(fs.readFileSync(p,'utf8'));fs.unlinkSync(p)\""

head2 "Q1 - can it serve OpenCode on loopback?"
adb shell "cd $REMOTE/app && $ENV nohup $REMOTE/node serve-node.mjs $PORT ./node.js > $REMOTE/server.log 2>&1 &"
sleep 25
adb shell "cat $REMOTE/server.log"

say ""
say "health:"
adb shell "toybox wget -qO- http://127.0.0.1:$PORT/global/health 2>/dev/null || echo '(no wget; try: adb forward tcp:$PORT tcp:$PORT then curl locally)'"
say ""

head2 "cleanup"
say "adb shell \"pkill -f serve-node.mjs; rm -rf $REMOTE\""
