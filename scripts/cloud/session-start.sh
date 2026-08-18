#!/usr/bin/env bash
# Claude Code cloud session bootstrap for OpencodeApk.
#
# Diagnostic only. It reports environment state; it never mutates the repository
# and never installs anything. It ALWAYS exits 0 -- a session must not be blocked
# by its own bootstrap. Fresh clones, a missing Android SDK, and an unintegrated
# upstream are all normal states to report, not failures.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 0

say()  { printf '%s\n' "$*"; }
head2() { printf '\n== %s ==\n' "$*"; }

# JVM tooling prints a "Picked up JAVA_TOOL_OPTIONS:" banner to stderr in this
# environment; strip it so version lines stay readable.
strip_jvm_noise() { grep -v '^Picked up JAVA_TOOL_OPTIONS' || true; }

# First non-blank output line: `gradle --version` leads with a blank line.
ver() {
  command -v "$1" >/dev/null 2>&1 || { echo "(not installed)"; return; }
  "$@" 2>&1 | strip_jvm_noise | grep -m1 -v '^[[:space:]]*$' || echo "(no version output)"
}

say "OpencodeApk :: session start"
say "root: $ROOT"

head2 "Toolchain"
say "node    : $(ver node --version)"
say "bun     : $(ver bun --version)"
say "java    : $(ver java -version)"
# `gradle --version` prints a banner box; the useful line is "Gradle <version>".
say "gradle  : $(command -v gradle >/dev/null 2>&1 \
      && (gradle --version 2>&1 | strip_jvm_noise | grep -m1 '^Gradle ' || echo '(no version output)') \
      || echo '(not installed)')"
say "git     : $(ver git --version)"

head2 "Android SDK"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -n "$SDK" ] && [ -d "$SDK" ]; then
  say "present : $SDK"
else
  say "absent  : ANDROID_HOME / ANDROID_SDK_ROOT are unset."
  say "          Expected in a cloud session. CI is the Android build authority."
  say "          Do not install the SDK here just to get local green output."
fi

head2 "Repository"
BRANCH="$(git branch --show-current 2>/dev/null)"
[ -n "$BRANCH" ] || BRANCH="(detached or unknown)"
say "branch  : $BRANCH"
if git rev-parse HEAD >/dev/null 2>&1; then
  say "head    : $(git log --oneline -1)"
else
  say "head    : (no commits yet)"
fi
DIRTY="$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
say "dirty   : ${DIRTY} uncommitted path(s)"

head2 "Project phase"
if [ -f package.json ] || [ -d packages ]; then
  say "upstream: integrated (package.json / packages present)"
else
  say "upstream: NOT integrated yet -- see ADR-0002 in docs/DECISIONS.md (decided in M1)"
fi

GRADLEW="$(find . -maxdepth 3 -name gradlew -not -path './.git/*' 2>/dev/null | head -1)"
if [ -n "$GRADLEW" ]; then
  say "android : Gradle project at $(dirname "$GRADLEW")"
else
  say "android : no Gradle project yet -- expected before M2, not an error"
fi

head2 "Current status"
if [ -f docs/CURRENT_STATUS.md ]; then
  # Print the header block up to the first horizontal rule, capped for sanity.
  sed -n '1,/^---$/p' docs/CURRENT_STATUS.md | head -30
else
  say "docs/CURRENT_STATUS.md missing -- this is a problem; recreate it."
fi

head2 "Reminders"
say "* Read CLAUDE.md, then docs/CURRENT_STATUS.md, then your prompts/ file."
say "* NEVER run the upstream root 'test' script -- it intentionally exits 1."
say "* Never silently migrate or regenerate a lockfile."
say "* A milestone is complete only when a real test or build supports the claim."
say "* Update docs/CURRENT_STATUS.md before ending the session."

exit 0
