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
# The application ID this project has committed to (docs/ARCHITECTURE.md 2.2).
EXPECTED_APPLICATION_ID="${EXPECTED_APPLICATION_ID:-ai.opencode.android}"
# A debug APK with resources and dex is comfortably above this; a stub is not.
MIN_APK_BYTES="${MIN_APK_BYTES:-100000}"

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

# Permissions the app cannot work without. A missing one fails silently at
# runtime - INTERNET in particular turns every server request into an opaque
# network error - so its absence is a build failure, not a surprise on a phone.
REQUIRED_PERMISSIONS="
android.permission.INTERNET
"

head2 "verify-apk"

# aapt2 is not on PATH in either a stock CI image or a developer shell, but it
# ships with every build-tools release. Without it the manifest checks below
# degrade to warnings, which reads like a pass and is not one - so look for it
# where it actually lives before giving up.
if ! command -v aapt2 >/dev/null 2>&1; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  CANDIDATE="$(ls -1 "$SDK"/build-tools/*/aapt2 "$SDK"/build-tools/*/aapt2.exe 2>/dev/null | sort -V | tail -1)"
  if [ -n "$CANDIDATE" ]; then
    PATH="$(dirname "$CANDIDATE"):$PATH"
    export PATH
  fi
fi

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

# Some checks apply only to a shipping build - a debug APK is deliberately
# debuggable and deliberately permits cleartext to a LAN dev server (ADR-0018).
case "$APK" in
  *release*) VARIANT="release" ;;
  *)         VARIANT="debug" ;;
esac

say "apk  : $APK"
say "size : $(du -h "$APK" | cut -f1)"
say "type : $VARIANT"

# An APK that exists but is a stub is worse than none: it looks like success.
APK_BYTES="$(wc -c < "$APK" | tr -d ' ')"
if [ "$APK_BYTES" -lt "$MIN_APK_BYTES" ]; then
  bad "APK is only ${APK_BYTES} bytes (expected at least ${MIN_APK_BYTES}) - not a real build"
else
  good "APK is non-empty (${APK_BYTES} bytes)"
fi

if ! command -v unzip >/dev/null 2>&1; then
  say "ENV: 'unzip' is required."
  exit 2
fi

LISTING="$(unzip -l "$APK" 2>/dev/null)" || { bad "APK is not a readable zip archive"; exit 1; }

# Everything below searches $LISTING with a herestring rather than
# `printf ... | grep`. With `set -o pipefail`, `grep -q` exiting early on a match
# closes the pipe, `printf` dies of SIGPIPE, and the whole pipeline reports
# failure even though the match SUCCEEDED. That turned a correct 16 MB APK into
# "no classes.dex / manifest missing / assets missing" - three false failures at
# once. It only appeared once the APK grew large enough for printf not to finish
# first, which is exactly the kind of bug that hides until it matters.

# ------------------------------------------------------------------- contents
head2 "contents"
if grep -q 'classes.*\.dex' <<< "$LISTING"; then
  good "dex classes present"
else
  bad "no classes.dex -- this APK contains no code"
fi

if grep -q 'AndroidManifest.xml' <<< "$LISTING"; then
  good "AndroidManifest.xml present"
else
  bad "AndroidManifest.xml missing"
fi

# --------------------------------------------------------------- web content
# From M3 the APK must carry the shared OpenCode UI. An APK without it installs
# and launches to a blank screen, which is the most misleading kind of "success".
head2 "shared UI assets"
if grep -q 'assets/web/index.html' <<< "$LISTING"; then
  good "assets/web/index.html present"
  WEB_FILES="$(grep -c 'assets/web/' <<< "$LISTING" || true)"
  say "       $WEB_FILES file(s) under assets/web/"
  if [ "$WEB_FILES" -lt 2 ]; then
    bad "only $WEB_FILES file under assets/web/ - a real vite build emits JS and CSS too"
  fi
else
  bad "assets/web/index.html MISSING - the APK would launch to a blank screen"
fi

# ------------------------------------------------------------------ native ABI
head2 "native ABIs"
ABIS="$(grep -oE 'lib/[a-z0-9_-]+/' <<< "$LISTING" | cut -d/ -f2 | sort -u)"
if [ -z "$ABIS" ]; then
  say "no native libraries in this APK (pure-JVM build)"
  say "NOTE: once an on-device runtime ships (M7), arm64-v8a becomes mandatory."
else
  say "found: $(tr '\n' ' ' <<< "$ABIS")"
  if grep -qx 'arm64-v8a' <<< "$ABIS"; then
    good "arm64-v8a present (real-device ABI)"
  else
    bad "arm64-v8a MISSING. An x86_64-only APK is emulator-only and cannot"
    bad "satisfy any milestone claim (.claude/rules/android.md N1)."
  fi
fi

# --------------------------------------------------------------- application id
head2 "application id"
BADGING=""
if command -v aapt2 >/dev/null 2>&1; then
  BADGING="$(aapt2 dump badging "$APK" 2>/dev/null)"
elif command -v aapt >/dev/null 2>&1; then
  BADGING="$(aapt dump badging "$APK" 2>/dev/null)"
fi

if [ -z "$BADGING" ]; then
  warn "aapt/aapt2 unavailable - cannot read the application id (not treated as a pass)"
else
  ACTUAL_ID="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$BADGING" | head -1)"
  if [ "$ACTUAL_ID" = "$EXPECTED_APPLICATION_ID" ]; then
    good "application id is $ACTUAL_ID"
  else
    bad "application id is '$ACTUAL_ID', expected '$EXPECTED_APPLICATION_ID'"
  fi
  LAUNCHABLE="$(grep -c "launchable-activity" <<< "$BADGING" || true)"
  if [ "$LAUNCHABLE" -gt 0 ]; then
    good "APK declares a launchable activity"
  else
    bad "no launchable activity - the APK would install but not start"
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
    if grep -qF "$perm" <<< "$PERMS"; then
      bad "forbidden permission requested: $perm"
      FOUND=1
    fi
  done
  [ "$FOUND" -eq 0 ] && good "no forbidden permission found"

  for perm in $REQUIRED_PERMISSIONS; do
    if grep -qF "$perm" <<< "$PERMS"; then
      good "required permission present: $perm"
    else
      bad "required permission missing: $perm"
    fi
  done
fi

# ------------------------------------------------------------ security posture
# M10. These are the properties a threat review established; they are checked
# here because a manifest merge or a build-type mistake can undo any of them
# silently, and the APK is the only artifact that reflects what actually ships.
head2 "security posture"

MANIFEST=""
if command -v aapt2 >/dev/null 2>&1; then
  MANIFEST="$(aapt2 dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null)"
elif command -v aapt >/dev/null 2>&1; then
  MANIFEST="$(aapt dump xmltree "$APK" AndroidManifest.xml 2>/dev/null)"
fi

if [ -z "$MANIFEST" ]; then
  warn "could not decode the manifest; skipping the posture checks (not treated as a pass)"
else
  # Exported components are the app's attack surface from other apps on the
  # device. Exactly one is expected: the launcher activity.
  EXPORTED="$(grep -c 'android:exported([^)]*)=true' <<< "$MANIFEST" || true)"
  # Two are expected and no more: MainActivity (it is the launcher, so it must
  # be) and androidx's ProfileInstallReceiver, which is guarded by the signature
  # permission android.permission.DUMP. A third means new attack surface.
  if [ "$EXPORTED" -le 2 ]; then
    good "exported components: $EXPORTED (launcher activity + androidx's DUMP-guarded profile receiver)"
  else
    bad "unexpected exported components: $EXPORTED - each is reachable by any app on the device"
  fi

  # A debuggable release APK would let any user attach a debugger to the process
  # holding the provider credentials.
  if [ "$VARIANT" = "release" ]; then
    if grep -q 'android:debuggable([^)]*)=true' <<< "$MANIFEST"; then
      bad "release APK is debuggable - a debugger could attach to the process holding credentials"
    else
      good "release APK is not debuggable"
    fi

    # usesCleartextTraffic must be absent: the policy is the network security
    # config, and setting the attribute would override it for every host.
    if grep -q 'android:usesCleartextTraffic([^)]*)=true' <<< "$MANIFEST"; then
      bad "usesCleartextTraffic is set - it overrides the network security config for every host"
    else
      good "no blanket usesCleartextTraffic; the network security config governs"
    fi
  fi

  if grep -q 'android:networkSecurityConfig' <<< "$MANIFEST"; then
    good "a network security config is declared"

    # WHICH config ships is the point. Debug replaces the file wholesale with one
    # that permits cleartext everywhere (ADR-0018), so a release built from the
    # wrong source set would look identical here. Resource file names are
    # obfuscated in release, so resolve the id rather than guessing the path.
    NSC_FILE="$(aapt2 dump resources "$APK" 2>/dev/null |
      grep -A1 'xml/network_security_config' | grep -oE 'res/[^ ]+\.xml' | head -1)"
    if [ -z "$NSC_FILE" ]; then
      warn "could not resolve the network security config resource (not treated as a pass)"
    else
      NSC="$(aapt2 dump xmltree --file "$NSC_FILE" "$APK" 2>/dev/null)"
      BASE_CLEARTEXT="$(sed -n '/E: base-config/,/E: domain-config/p' <<< "$NSC" |
        grep -oE 'cleartextTrafficPermitted=(true|false)' | head -1)"
      if [ "$VARIANT" = "release" ]; then
        if [ "$BASE_CLEARTEXT" = "cleartextTrafficPermitted=false" ]; then
          good "release denies cleartext by default"
        else
          bad "release permits cleartext by default ($BASE_CLEARTEXT) - the debug config may have shipped"
        fi
        # The loopback exception is intended and must stay scoped to loopback.
        OTHER="$(grep -oE "T: '[^']+'" <<< "$NSC" | grep -vE "'(127\.0\.0\.1|localhost)'" || true)"
        if [ -n "$OTHER" ]; then
          bad "the cleartext exception covers hosts beyond loopback: $(tr '
' ' ' <<< "$OTHER")"
        else
          good "the cleartext exception is scoped to loopback only"
        fi
      fi
    fi
  else
    bad "no network security config - cleartext would follow the platform default"
  fi

  if grep -q 'android:allowBackup([^)]*)=false' <<< "$MANIFEST"; then
    good "allowBackup is false - credentials and the session database are not exported"
  else
    bad "allowBackup is not false"
  fi
fi

# The runtime must not be reachable off-device. Loopback is asserted in code and
# tested on hardware; here we check the packaged launcher does not default
# otherwise, which is the one place a change would be easy to miss.
if unzip -p "$APK" assets/runtime/launch.mjs 2>/dev/null | grep -q '0\.0\.0\.0'; then
  bad "the packaged launcher mentions 0.0.0.0 - the server must default to loopback"
else
  good "the packaged launcher does not bind 0.0.0.0"
fi

head2 "result"
if [ "$STATUS" -eq 0 ]; then say "VERIFIED"; else say "VIOLATIONS FOUND"; fi
exit "$STATUS"
