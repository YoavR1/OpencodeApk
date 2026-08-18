# M3 — Shared OpenCode UI

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

Upstream's **real** SolidJS application rendering inside the Android WebView.

Not a mock, not a reimplementation, not a screenshot. The actual
`@opencode-ai/app`.

## Tasks

1. **Build `packages/app` into static assets** using the command established in
   M1, and package the output into the APK.

2. **Widen the platform union.** In `packages/app/src/context/platform.tsx`:
   - `type PlatformName = "web" | "desktop"` → add `"android"` (~line 20)
   - add an `{ platform: "android"; … }` arm to the exported `Platform` union
     (~line 126)

   Log both in `docs/UPSTREAM_SYNC.md` as applied divergence (entries D1, D2).
   Keep the edit mechanical and upstreamable — a union widening, nothing more.

3. **Provide a minimal Android `Platform` implementation.** Enough to boot:
   `version`, `openExternal`, `restart`, `notify`, `storage`. `PlatformBase`'s
   capabilities are almost all optional — implement only what you can now and
   leave the rest for M4. `packages/app/src/entry.tsx` builds the `"web"` platform
   and is the worked example to follow.

4. **Decide asset packaging** (Q4): bundled at build time vs. downloaded at
   runtime. **Bundled is strongly preferred** — it keeps the app offline-capable
   and free of an external dependency, which the charter requires. Record as an ADR.

5. **Confirm the app boots, routes, and renders with no server connected.** The UI
   should reach a coherent "not connected" state rather than crash or hang. That
   is success for M3.

## Constraints

- **Do not fork the UI.** No copy of upstream UI source may land in this
  repository. If you find yourself copying a component, stop and reconsider.
- **No Android branching inside shared UI.** Anything Android-specific goes behind
  the `Platform` boundary. If shared UI needs an Android capability, add an
  *optional* method to `PlatformBase` — do not add a platform check.
- Divergence budget for this milestone is the two `platform.tsx` edits. If you
  need more, stop and write down why before proceeding.

## Exit criteria

See M3 in `docs/IMPLEMENTATION_PLAN.md`. Screenshot evidence of the real upstream
UI rendering, plus an instrumented test asserting the UI mounted.

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
