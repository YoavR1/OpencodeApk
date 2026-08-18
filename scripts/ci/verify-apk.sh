#!/usr/bin/env bash
# Verify a built APK against the project's hard rules.
#
# Checks:
#   * the APK exists and is a valid zip
#   * native libraries include arm64-v8a (the real-device ABI) if any exist
#   * no forbidden permission is requested
#   * classes are present
#
# Usage: scripts/ci/verify-apk.sh [path/to/app.apk]
#        (with no argument, the first debug APK under any outputs/ dir is used)
#
# Exit codes:
#   0  verified
#   1  a rule was violated
#   2  environment problem
#   3  PHASE: no APK exists yet (expected before M2)

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

STATUS=0
say()  { printf '%s\n' "$*"; }
head2() { printf '\n== %s ==\n' "$*"; }
bad()  { printf '[FAIL] %s\n' "$*"; STATUS=1; }
good() { printf '[ok]   %s\n' "$*"; }
warn() { printf '[warn] %s\n' "$*"; }

# Permissions this project has committed to never requesting without an ADR.
FORBIDDEN_PERMISSIONS="
android.permission.MANAGE_EXTERNAL_STORAGE
android.permission.REQUEST_INSTALL_PACKAGES
android.permission.READ_SMS
android.permission.RECEIVE_SMS
android.permission.READ_CONTACTS
android.permission.ACCESS_FINE_LOCATION
android.permission.ACCESS_BACKGROUND_LOCATION
android.permission.RECORD_AUDIO
android.permission.CAMERA
android.permission.READ_PHONE_STATE
"

head2 "verify-apk"

APK="${1:-}"
if [ -z "$APK" ]; then
  APK="$(find . -name '*debug*.apk' -path '*outputs*' -not -path './.git/*' 2>/dev/null | head -1)"
fi

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  say "PHASE: no APK found."
  say "       Expected before milestone M2 (first Android APK shell)."
  say "       Reported as a phase state, not a failure and not a success."
  exit 3
fi

say "apk  : $APK"
say "size : $(du -h "$APK" | cut -f1)"

if ! command -v unzip >/dev/null 2>&1; then
  say "ENV: 'unzip' is required."
  exit 2
fi

LISTING="$(unzip -l "$APK" 2>/dev/null)" || { bad "APK is not a readable zip archive"; exit 1; }

# ------------------------------------------------------------------- contents
head2 "contents"
if printf '%s' "$LISTING" | grep -q 'classes.*\.dex'; then
  good "dex classes present"
else
  bad "no classes.dex -- this APK contains no code"
fi

if printf '%s' "$LISTING" | grep -q 'AndroidManifest.xml'; then
  good "AndroidManifest.xml present"
else
  bad "AndroidManifest.xml missing"
fi

# ------------------------------------------------------------------ native ABI
head2 "native ABIs"
ABIS="$(printf '%s' "$LISTING" | grep -oE 'lib/[a-z0-9_-]+/' | cut -d/ -f2 | sort -u)"
if [ -z "$ABIS" ]; then
  say "no native libraries in this APK (pure-JVM build)"
  say "NOTE: once an on-device runtime ships (M7), arm64-v8a becomes mandatory."
else
  say "found: $(printf '%s' "$ABIS" | tr '\n' ' ')"
  if printf '%s' "$ABIS" | grep -qx 'arm64-v8a'; then
    good "arm64-v8a present (real-device ABI)"
  else
    bad "arm64-v8a MISSING. An x86_64-only APK is emulator-only and cannot"
    bad "satisfy any milestone claim (.claude/rules/android.md N1)."
  fi
fi

# ----------------------------------------------------------------- permissions
head2 "permissions"
PERMS=""
if command -v aapt2 >/dev/null 2>&1; then
  PERMS="$(aapt2 dump permissions "$APK" 2>/dev/null)"
elif command -v aapt >/dev/null 2>&1; then
  PERMS="$(aapt dump permissions "$APK" 2>/dev/null)"
fi

if [ -z "$PERMS" ]; then
  warn "aapt/aapt2 unavailable -- falling back to a raw scan of the binary manifest."
  PERMS="$(unzip -p "$APK" AndroidManifest.xml 2>/dev/null | strings 2>/dev/null || true)"
fi

if [ -z "$PERMS" ]; then
  warn "could not read permissions; skipping this check (not treated as a pass)"
else
  FOUND=0
  for perm in $FORBIDDEN_PERMISSIONS; do
    if printf '%s' "$PERMS" | grep -qF "$perm"; then
      bad "forbidden permission requested: $perm"
      FOUND=1
    fi
  done
  [ "$FOUND" -eq 0 ] && good "no forbidden permission found"
fi

head2 "result"
if [ "$STATUS" -eq 0 ]; then say "VERIFIED"; else say "VIOLATIONS FOUND"; fi
exit "$STATUS"
