# M11 — Polish and Release

## Before you do anything

1. Read `CLAUDE.md`.
2. Read `docs/CURRENT_STATUS.md` — this is the authoritative state of the project.
3. Read `.claude/rules/architecture.md`, `.claude/rules/android.md`, and
   `.claude/rules/quality.md`.
4. Read this milestone's exit criteria in `docs/IMPLEMENTATION_PLAN.md`.
5. Verify the repository actually matches what `docs/CURRENT_STATUS.md` claims.
   If it does not, correct the status file first and tell me — do not build on a
   false premise.

Do not skip ahead. If you find work belonging to a later milestone, write it into
`docs/IMPLEMENTATION_PLAN.md` rather than doing it.

## Goal

A real, shippable app — not a demo that technically works.

## Tasks

1. **Visual identity**: app icon, adaptive icon, splash screen, theming, dark mode
   that follows the system setting.

2. **Onboarding from a clean install**: provider setup, project selection, and a
   first-run experience that does not assume the user has read anything.

3. **States**: error, empty, and loading states everywhere. No dead-end screens, no
   infinite spinners, no raw stack traces shown to the user.

4. **Performance pass**: cold start time, memory footprint, scroll performance in
   long sessions. Record targets and measured results — a number, not an
   impression.

5. **Release build**: R8/ProGuard rules that do not break the WebView bridge or
   reflection-dependent code, and a signing config that reads its material from
   **CI secrets**. Never commit a keystore.

6. **User-facing `README.md`**: what the app is, how to install it, what it can and
   cannot do. Be honest about limitations — especially any feature dropped in M8.

7. **Accessibility pass**: content descriptions, touch target sizes, contrast
   ratios, TalkBack navigation.

## Constraints

- No keystore, signing key, or credential in the repository.
- The release build must pass the same verification as debug
  (`scripts/ci/verify-apk.sh`).
- Do not disable lint or tests to ship.

## Exit criteria

See M11 in `docs/IMPLEMENTATION_PLAN.md`. A signed release APK builds in CI;
onboarding works from a clean install; the full `docs/TEST_MATRIX.md` passes;
performance targets recorded and met; user README complete.

## Before declaring the project done

Re-read `docs/PROJECT_CHARTER.md` and check every success criterion, with its
evidence. In particular confirm, explicitly:

- **no PC required**,
- **no visible Termux workflow**,
- **no external OpenCode server required for the core experience**.

If any of those is not true, the project is not done — say so.

## Finish the session with

1. `docs/CURRENT_STATUS.md` updated: what changed, the **real** commands you ran
   and their **real** output, every claim tagged **VERIFIED** / **ASSUMED** /
   **BLOCKED**, and what is still missing.
2. New decisions appended to `docs/DECISIONS.md`.
3. Any upstream file you modified logged in `docs/UPSTREAM_SYNC.md`.
4. Any new tests registered in `docs/TEST_MATRIX.md`.
5. Commits pushed to the session branch.
6. A plain statement of what is **not** done and what the next session should run.

Never fabricate command output. If a command could not be run, say so with the
actual error and mark the claim BLOCKED.
