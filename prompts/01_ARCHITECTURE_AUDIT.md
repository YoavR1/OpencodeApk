# M1 — Architecture Audit

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

Turn M0's reading into **decided, evidence-backed integration choices**. M0
established what upstream *is*. M1 establishes how we *consume* it.

This is still not implementation. The deliverable is decisions plus the evidence
behind them.

## The central question

**ADR-0002 in `docs/DECISIONS.md` is Provisional and you must resolve it.**

How does upstream code reach this repository: pinned git submodule, vendored
subtree, published npm packages, or a fork?

The provisional answer is *submodule + a small tracked patch set*. Confirm or
overturn it with one measurement:

> How many upstream files must actually be patched to build the shared UI with an
> Android platform?

If the answer is one or two mechanical edits, the submodule approach holds. If it
grows, reopen the decision in favour of a fork.

## Tasks

1. **Resolve ADR-0002.** Record the decision, the measurement that drove it, and
   the rejected alternatives with reasons.

2. **Enumerate required upstream modifications.** `docs/UPSTREAM_SYNC.md` predicts
   exactly two edits, both in `packages/app/src/context/platform.tsx`: widening
   `PlatformName` (~line 20) and adding an arm to the `Platform` union (~line 126).
   **Verify this is the complete list.** Look for anywhere `platform === "desktop"`
   or `platform === "web"` is branched on in `packages/app`, `packages/session-ui`,
   and `packages/ui`, and check whether any of it gates behaviour Android needs.

3. **Determine how to build the shared UI into APK-ready static assets.** Upstream
   builds `packages/app` with `vite build`. Work out the base path, asset URL
   handling, and whether the output is relocatable under an app origin. Produce a
   reproducible command, or record the actual error that blocks it.

4. **Check whether the shared-UI packages are published to npm** (`@opencode-ai/app`,
   `session-ui`, `ui`, `client`, `sdk`) or workspace-only. This is Q3 in
   `docs/DECISIONS.md` and it directly constrains ADR-0002.

5. **Inventory the runtime requirements of `packages/core` and `packages/server`.**
   Which Node/Bun APIs, which native modules, what filesystem assumptions, what
   subprocess use. Write the inventory into `docs/ARCHITECTURE.md` — M6 depends on
   it. Start from the dependency table already in `docs/ARCHITECTURE.md` §1.3.

6. **Pin the upstream commit** in `docs/UPSTREAM_SYNC.md` and prove the pin is
   buildable — or record precisely why it is not in this environment.

7. **Decide the Android toolchain** (Q1, Q2): `minSdk`, `compileSdk`, AGP, Gradle,
   Kotlin version, and where the Android project lives (`apps/android/` suggested).
   Record as an ADR. Bias `minSdk` toward a modern floor (API 26+) rather than
   maximum reach — the local-runtime work needs modern process and filesystem
   behaviour.

## Constraints

- Reuse upstream before rewriting it.
- Minimize divergence — every patched file is a permanent liability.
- Do **not** run the upstream root `test` script.
- Do **not** regenerate or migrate a lockfile.
- If Bun install fails behind the cloud proxy, report it and treat CI as the build
  authority (`docs/CLAUDE_CLOUD_SETUP.md`).

## Exit criteria

See M1 in `docs/IMPLEMENTATION_PLAN.md`. In short: ADR-0002 decided with evidence;
a definitive upstream-modification list; a reproducible shared-UI asset build (or
a recorded blocker); a runtime-requirement inventory; Android toolchain decided.

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
