#!/usr/bin/env bash
# Build the Android app.
#
# Before M2 there is no Gradle project. That is an explicit PHASE STATE, not a
# failure and not a success: this script exits 3 for it, so CI can report the
# phase honestly instead of faking either outcome.
#
# Exit codes:
#   0  build succeeded
#   1  build failed
#   2  environment problem (no JDK, no Android SDK)
#   3  PHASE: no Android Gradle project yet (expected before M2)

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

# Debug-variant tasks specifically: `lint` and `test` would also build the release
# variant, which M2 does not configure signing or shrinking for. Naming the debug
# tasks keeps CI honest about what it actually verified.
TASKS="${TASKS:-lintDebug testDebugUnitTest assembleDebug}"

say()  { printf '%s\n' "$*"; }
head2() { printf '\n== %s ==\n' "$*"; }

head2 "build-android"

# ------------------------------------------------------------- locate project
GRADLEW="$(find . -maxdepth 3 -name gradlew -not -path './.git/*' 2>/dev/null | head -1)"

if [ -z "$GRADLEW" ]; then
  say "PHASE: no Android Gradle project found (searched for a 'gradlew' wrapper)."
  say ""
  say "This is the expected state before milestone M2 (first Android APK shell)."
  say "It is deliberately NOT reported as a failure and NOT as a success."
  say ""
  say "See docs/IMPLEMENTATION_PLAN.md (M2) and docs/CURRENT_STATUS.md."
  exit 3
fi

PROJECT_DIR="$(cd "$(dirname "$GRADLEW")" && pwd)"
say "project : $PROJECT_DIR"
say "tasks   : $TASKS"

# ------------------------------------------------------------------ toolchain
if ! command -v java >/dev/null 2>&1; then
  say "ENV: no JDK on PATH. Android builds need JDK 17 or 21."
  exit 2
fi
say "java    : $(java -version 2>&1 | head -1)"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  say "ENV: Android SDK not found (ANDROID_HOME / ANDROID_SDK_ROOT unset)."
  say "     Cloud sessions do not have the SDK -- CI is the build authority."
  say "     See docs/CLAUDE_CLOUD_SETUP.md."
  exit 2
fi
say "sdk     : $SDK"

# ---------------------------------------------------------------------- build
cd "$PROJECT_DIR" || exit 2
chmod +x ./gradlew 2>/dev/null || true

head2 "gradle"
# --no-daemon: CI runners are ephemeral; a daemon only adds memory pressure.
if ! ./gradlew --no-daemon --stacktrace $TASKS; then
  say ""
  say "[FAIL] Gradle build failed. Read the actual error above and fix the cause."
  say "       Do not disable a check or skip a test to get green"
  say "       (.claude/rules/quality.md Q4)."
  exit 1
fi

head2 "artifacts"
find . -name '*.apk' -path '*outputs*' 2>/dev/null | while read -r apk; do
  say "apk : $apk ($(du -h "$apk" | cut -f1))"
done

say ""
say "OK"
exit 0
