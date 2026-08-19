#!/usr/bin/env bash
# Install the debug APK on a connected device, launch it, and capture the log
# lines that answer M5's open questions.
#
# This exists because the build sessions run in a cloud container with no access
# to a phone: the device criteria in docs/CURRENT_STATUS.md can only be closed by
# someone with the hardware. Run this, then paste the output file back.
#
# Usage:
#   scripts/dev/capture-device-log.sh [path/to/app-debug.apk] [seconds]
#
# With no APK argument the newest debug APK under any outputs/ directory is used
# (i.e. what `./gradlew assembleDebug` just produced, or an artifact you
# downloaded from CI and unzipped).
#
# Exit codes:
#   0  captured
#   2  environment problem (no adb, no device)

set -uo pipefail

APK="${1:-}"
SECONDS_TO_CAPTURE="${2:-90}"
PACKAGE="ai.opencode.android"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

say()  { printf '%s\n' "$*"; }
head2() { printf '\n== %s ==\n' "$*"; }

ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1; then
  for candidate in "${ANDROID_HOME:-}/platform-tools/adb" "${ANDROID_SDK_ROOT:-}/platform-tools/adb"; do
    [ -x "$candidate" ] && ADB="$candidate" && break
  done
fi
if ! command -v "$ADB" >/dev/null 2>&1 && [ ! -x "$ADB" ]; then
  say "ENV: adb not found. Install Android platform-tools, or set ADB=/path/to/adb."
  exit 2
fi

head2 "device"
DEVICES="$("$ADB" devices | tail -n +2 | grep -c "device$")"
if [ "$DEVICES" -eq 0 ]; then
  say "ENV: no device. Check the USB cable, that USB debugging is on, and that you"
  say "     accepted the 'Allow USB debugging' prompt on the phone."
  say ""
  "$ADB" devices -l
  exit 2
fi
"$ADB" devices -l | tail -n +2
say ""
say "model   : $("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
say "android : $("$ADB" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r') (API $("$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r'))"
say "abi     : $("$ADB" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
say "webview : $("$ADB" shell dumpsys package com.google.android.webview 2>/dev/null | grep -m1 versionName | tr -d '\r' | sed 's/^ *//')"

head2 "apk"
if [ -z "$APK" ]; then
  APK="$(find . -name '*debug*.apk' -path '*outputs*' -not -path './.git/*' 2>/dev/null | head -1)"
fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  say "ENV: no APK found. Pass one, or download opencode-android-debug from CI."
  exit 2
fi
say "installing $APK"
"$ADB" install -r -d "$APK" 2>&1 | tail -3

OUT="device-report-$(date +%Y%m%d-%H%M%S).log"

head2 "capture"
say "Launching, then capturing ${SECONDS_TO_CAPTURE}s of log into $OUT."
say ""
say "  While it captures, do this on the phone:"
say "    1. does the UI appear at all, or a blank screen?"
say "    2. add your server, and watch the health indicator"
say "    3. open a session, send a short prompt"
say "    4. watch whether the reply STREAMS IN or lands all at once"
say ""

"$ADB" logcat -c 2>/dev/null
"$ADB" shell am start -n "$PACKAGE/.MainActivity" >/dev/null 2>&1

# Chromium console lines carry the WebView's own errors - a blocked CORS
# preflight shows up there and nowhere else, which is the single most likely
# failure for M5.
"$ADB" logcat -v time \
  chromium:V \
  OpenCode:V \
  AndroidRuntime:E \
  ActivityManager:W \
  WebViewFactory:V \
  "*:S" > "$OUT.raw" 2>/dev/null &
LOGCAT_PID=$!

sleep "$SECONDS_TO_CAPTURE"
kill "$LOGCAT_PID" 2>/dev/null
wait "$LOGCAT_PID" 2>/dev/null

# Redact before this is pasted anywhere. SafeLog covers what the app logs, but
# WebView console output is not ours and a URL with credentials in it, or a
# logged header, would otherwise travel with the report.
sed -E \
  -e 's#(//)[^:/@[:space:]]+:[^@[:space:]]+@#\1USER:REDACTED@#g' \
  -e 's/([Aa]uthorization[":= ]+)[^",[:space:]]+/\1REDACTED/g' \
  -e 's/([Bb]asic )[A-Za-z0-9+/=]{8,}/\1REDACTED/g' \
  -e 's/(password[":= ]+)[^",[:space:]]+/\1REDACTED/g' \
  -e 's/(token[":= ]+)[^",[:space:]]+/\1REDACTED/g' \
  "$OUT.raw" > "$OUT"
rm -f "$OUT.raw"

head2 "result"
say "wrote $OUT ($(wc -l < "$OUT") lines, credentials redacted)"
say ""
say "Lines worth looking at yourself before pasting:"
grep -iE "CONSOLE|CORS|blocked|ERR_|Uncaught|FATAL|net::" "$OUT" | head -20
say ""
say "Paste the whole file back, plus your answers to the four questions above."
