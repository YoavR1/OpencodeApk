#!/usr/bin/env bash
#
# Builds the release APK twice and compares the results.
#
# Reproducibility is a claim that decays silently: one dependency that stamps a
# timestamp, one tool version picked up from the environment, and the property is
# gone with nothing failing. This makes the claim checkable instead of asserted.
#
# Deliberately compares the UNSIGNED artifact. Signing happens after packaging,
# and a signature is expected to differ; including it would measure the wrong
# thing and hide a real regression behind an expected difference.
#
# Not run on every push - two clean release builds is several minutes of runner
# time for a property that changes rarely. Run it when the build configuration or
# the toolchain moves.
#
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT="$PWD"
APP="$ROOT/apps/android"
OUT="$APP/app/build/outputs/apk/release"
WORK="${TMPDIR:-/tmp}/opencode-repro.$$"

say()  { printf '%s\n' "$*"; }
head2() { printf '\n== %s ==\n' "$*"; }

trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK"

head2 "check-reproducible"

if [ ! -x "$APP/gradlew" ] && [ ! -f "$APP/gradlew" ]; then
  say "ENV: no Gradle wrapper at $APP/gradlew"
  exit 2
fi

# The version must be identical across both builds or they SHOULD differ. Pin it
# here rather than inheriting whatever the environment happens to carry.
export OPENCODE_VERSION_NAME="${OPENCODE_VERSION_NAME:-0.0.0-repro}"
export OPENCODE_VERSION_CODE="${OPENCODE_VERSION_CODE:-1}"

# Signing would make the two artifacts differ by design; measure the input to it.
unset OPENCODE_KEYSTORE OPENCODE_KEYSTORE_PASSWORD OPENCODE_KEY_ALIAS OPENCODE_KEY_PASSWORD

build() {
  say "building ($1) ..."
  ( cd "$APP" && ./gradlew clean assembleRelease --no-daemon -q )
  local apk
  apk="$(find "$OUT" -name '*.apk' | head -1)"
  if [ -z "$apk" ]; then
    say "FAIL: no release APK produced"
    exit 1
  fi
  cp "$apk" "$WORK/$1.apk"
}

build a
build b

# Git Bash on Windows hands POSIX-looking paths to a native python that cannot
# resolve them. Translate when cygpath exists; elsewhere the path is already
# what python expects.
localpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

head2 "comparison"
python - "$(localpath "$WORK/a.apk")" "$(localpath "$WORK/b.apk")" <<'PY'
import hashlib, sys, zipfile

a, b = sys.argv[1], sys.argv[2]
ha = hashlib.sha256(open(a, "rb").read()).hexdigest()
hb = hashlib.sha256(open(b, "rb").read()).hexdigest()

print(f"  a sha256 : {ha}")
print(f"  b sha256 : {hb}")

if ha == hb:
    print("\n  [ok]   the release APK is bit-for-bit reproducible")
    raise SystemExit(0)

# Not identical: say precisely what moved, because "not reproducible" on its own
# is not actionable.
za, zb = zipfile.ZipFile(a), zipfile.ZipFile(b)
na = [i.filename for i in za.infolist()]
nb = [i.filename for i in zb.infolist()]

if na != nb:
    only_a = [n for n in na if n not in set(nb)]
    only_b = [n for n in nb if n not in set(na)]
    if only_a or only_b:
        print(f"\n  entries only in a: {only_a[:10]}")
        print(f"  entries only in b: {only_b[:10]}")
    else:
        print("\n  same entries, different ORDER - the packaging step is not deterministic")

differing = [n for n in na if n in set(nb) and za.read(n) != zb.read(n)]
print(f"\n  entries with differing content: {len(differing)}")
for n in differing[:20]:
    print(f"    {n}")

print("\n  [FAIL] the release APK is not reproducible; see docs/RELEASE.md section 5")
raise SystemExit(1)
PY
