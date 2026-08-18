# M4 — Android Platform Adapter and Mobile UX

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

A **complete, tested** Android implementation of upstream's `Platform`, and a UI
that genuinely feels like a phone app rather than a desktop app in a small window.

## Tasks

### The platform adapter

Implement the rest of the `Platform` surface for Android:

- `notify` — real Android notifications, with the tap action wired to `onClick`.
- `openExternal` — `Intent.ACTION_VIEW`, with URL scheme validation (upstream's
  web implementation only permits `http:`, `https:`, `mailto:` — match that).
- `storage` — bridged to Android storage, honouring the `SyncStorage`/`AsyncStorage`
  contract.
- `draftStore` — prompt drafts and their blobs, surviving process death.
- `getDefaultServer` / `setDefaultServer` — persisted server selection.
- `restart` — a correct Android-appropriate restart.

Consult `packages/app/src/context/platform.tsx` for the full `PlatformBase`
surface. Implement what Android can do; omit what it cannot. Do not stub a method
with a lie — an absent optional method is honest, a method that silently does
nothing is not.

### The bridge

- Prefer **`WebMessagePort`/`postMessage`** over `addJavascriptInterface`.
- Keep the surface **small and typed**. Every message is validated on the Kotlin
  side; malformed input is rejected, not coerced.
- Document the complete bridge surface in `docs/ARCHITECTURE.md`. If it is hard to
  document, it is too big.

### Directory access

Project folders via the Storage Access Framework
(`ACTION_OPEN_DOCUMENT_TREE`) with persisted URI permissions. **No**
`MANAGE_EXTERNAL_STORAGE`.

### Mobile UX

- Touch targets at least 48dp.
- Soft keyboard must not occlude the input — handle window insets properly.
- Safe areas / display cutouts respected.
- Android **back button** behaves correctly: in-app navigation first, then exit.
- Orientation changes do not lose state.

## Constraints

- No Android branching inside shared UI — everything goes through `Platform`.
- Every implemented `Platform` method gets a test.
- Do not weaken the WebView's security settings for convenience.

## Exit criteria

See M4 in `docs/IMPLEMENTATION_PLAN.md`. Every implemented method tested; back
button and soft keyboard covered by instrumented tests; bridge documented.

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
