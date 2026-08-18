#!/usr/bin/env bash
# Build the shared OpenCode UI for Android.
#
# Produces packages/android/dist, which Gradle's syncSharedUi task copies into
# the APK. From M3 the Android build genuinely depends on this, so a failure
# here is a real failure and is not softened.
#
# Exit codes:
#   0  built
#   1  the build failed
#   2  environment problem (no bun, install blocked) - reported, not disguised

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

say()  { printf '%s\n' "$*"; }
head2() { printf '\n== %s ==\n' "$*"; }

head2 "build-shared-ui"

if [ ! -d packages/android ]; then
  say "packages/android does not exist. Nothing to build."
  exit 2
fi

if ! command -v bun >/dev/null 2>&1; then
  say "ENV: bun is not installed."
  exit 2
fi

DECLARED="$(node -e "try{process.stdout.write(String(require('./package.json').packageManager||''))}catch(e){}" 2>/dev/null)"
say "bun declared : ${DECLARED:-none}"
say "bun present  : bun@$(bun --version 2>/dev/null)"

head2 "install (frozen lockfile)"
if ! bun install --frozen-lockfile; then
  say ""
  say "ENV: 'bun install --frozen-lockfile' failed."
  say ""
  say "Known cloud-session cause: this environment's proxy returns 403 for"
  say "GitHub archive downloads (api.github.com tarball and codeload.github.com),"
  say "which is how Bun resolves the 'github:' dependency ghostty-web. The npm"
  say "registry and git protocol both work; only archive downloads are blocked."
  say ""
  say "DO NOT rerun without --frozen-lockfile, and DO NOT repoint the dependency."
  say "Either would rewrite the lockfile, which is a reviewable decision rather"
  say "than a debugging step. CI has unrestricted access and is the authority."
  say "See CLAUDE.md section 6 and docs/CLAUDE_CLOUD_SETUP.md."
  exit 2
fi

head2 "vite build"
if ! bun run --cwd packages/android build; then
  say ""
  say "[FAIL] The shared UI build failed. Fix the cause; do not skip the step."
  exit 1
fi

head2 "output"
DIST="packages/android/dist"
if [ ! -f "$DIST/index.html" ]; then
  say "[FAIL] $DIST/index.html was not produced."
  exit 1
fi
say "dist  : $DIST ($(du -sh "$DIST" | cut -f1))"
say "files : $(find "$DIST" -type f | wc -l | tr -d ' ')"

# The assets are served from a sub-path, so absolute /assets URLs would 404.
if grep -qE '(src|href)="/[^/]' "$DIST/index.html"; then
  say ""
  say "[FAIL] index.html contains root-absolute asset URLs."
  say "       WebViewAssetLoader serves these under /assets/, not at the origin"
  say "       root, so they would 404 on device. vite 'base' must stay relative."
  grep -nE '(src|href)="/[^/]' "$DIST/index.html" | head -5
  exit 1
fi
say "urls  : relative (correct for the WebViewAssetLoader sub-path)"

say ""
say "OK"
