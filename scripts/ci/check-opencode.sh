#!/usr/bin/env bash
# Safe workspace checks for the OpenCode side of the project (lint + typecheck).
#
# Two hard rules encoded here:
#   1. NEVER invoke the upstream root "test" script. Upstream defines it as
#        "test": "echo 'do not run tests from root' && exit 1"
#      deliberately. Invoking it is a guaranteed false failure.
#   2. NEVER mutate the lockfile. Installs use a frozen lockfile; if that fails,
#      we report it rather than "fixing" it by regenerating.
#
# Exit codes:
#   0  checks passed, or upstream is not integrated yet (an explicit phase state)
#   1  a real check failed
#   2  environment problem (no bun, install blocked) -- reported, not disguised

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

RUN_INSTALL="${RUN_INSTALL:-1}"
STATUS=0

say()  { printf '%s\n' "$*"; }
head2() { printf '\n== %s ==\n' "$*"; }
fail() { printf '\n[FAIL] %s\n' "$*"; STATUS=1; }

head2 "check-opencode"

# ---------------------------------------------------------------- phase check
if [ ! -f package.json ] && [ ! -d packages ]; then
  say "PHASE: upstream OpenCode is not integrated into this repository yet."
  say "       (no root package.json, no packages/ directory)"
  say "       This is the expected state before M1. Nothing to check."
  say "       See ADR-0002 in docs/DECISIONS.md."
  exit 0
fi

# ------------------------------------------------------------------ guardrail
if [ -f package.json ]; then
  ROOT_TEST="$(node -e "try{const p=require('./package.json');process.stdout.write(String((p.scripts&&p.scripts.test)||''))}catch(e){}" 2>/dev/null)"
  if printf '%s' "$ROOT_TEST" | grep -q 'do not run tests from root'; then
    say "guard : root 'test' script is the upstream sentinel -- will not be invoked."
  fi
fi

# ----------------------------------------------------------------------- bun
if ! command -v bun >/dev/null 2>&1; then
  say "ENV: bun is not installed. Cannot run workspace checks here."
  say "     Upstream declares packageManager bun@1.3.14."
  exit 2
fi

DECLARED="$(node -e "try{const p=require('./package.json');process.stdout.write(String(p.packageManager||''))}catch(e){}" 2>/dev/null)"
say "bun declared : ${DECLARED:-'(none declared)'}"
say "bun present  : bun@$(bun --version 2>/dev/null)"
if [ -n "$DECLARED" ] && [ "$DECLARED" != "bun@$(bun --version 2>/dev/null)" ]; then
  say "NOTE : version mismatch. Prefer the declared version; do NOT upgrade or"
  say "       downgrade globally, and do NOT regenerate the lockfile, just to"
  say "       clear this. See CLAUDE.md section 6."
fi

# --------------------------------------------------------------------- install
if [ "$RUN_INSTALL" = "1" ]; then
  head2 "install (frozen lockfile)"
  if ! bun install --frozen-lockfile; then
    say ""
    say "ENV: 'bun install --frozen-lockfile' failed."
    say "     Common causes in a cloud session: proxy/TLS restrictions on the"
    say "     package registry."
    say "     DO NOT rerun without --frozen-lockfile to 'fix' this: that silently"
    say "     migrates the lockfile, which is a reviewable decision, not a"
    say "     debugging step. Report the failure and let CI be the authority."
    exit 2
  fi
else
  say "install: skipped (RUN_INSTALL=0)"
fi

# ------------------------------------------------------------------- lint
head2 "lint"
if node -e "const p=require('./package.json');process.exit(p.scripts&&p.scripts.lint?0:1)" 2>/dev/null; then
  bun run lint || fail "lint failed"
else
  say "no root 'lint' script -- skipped"
fi

# --------------------------------------------------------------- typecheck
head2 "typecheck"
if node -e "const p=require('./package.json');process.exit(p.scripts&&p.scripts.typecheck?0:1)" 2>/dev/null; then
  bun run typecheck || fail "typecheck failed"
else
  say "no root 'typecheck' script -- skipped"
fi

# ------------------------------------------------------------ package tests
# Only packages we actually depend on, and only via 'bun test --cwd'.
# Set PACKAGE_TESTS to a space-separated list of package dirs to enable.
head2 "package tests"
PACKAGE_TESTS="${PACKAGE_TESTS:-}"
if [ -z "$PACKAGE_TESTS" ]; then
  say "PACKAGE_TESTS is empty -- no per-package tests requested."
  say "Set e.g. PACKAGE_TESTS='packages/app packages/ui' to run them."
else
  for pkg in $PACKAGE_TESTS; do
    if [ -d "$pkg" ]; then
      say "-- $pkg"
      bun test --cwd "$pkg" || fail "tests failed in $pkg"
    else
      fail "requested package test dir does not exist: $pkg"
    fi
  done
fi

head2 "result"
if [ "$STATUS" -eq 0 ]; then say "OK"; else say "FAILED"; fi
exit "$STATUS"
