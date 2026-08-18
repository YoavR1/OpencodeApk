# Upstream Sync

Tracks the upstream pin and **every** divergence from it. This file is the early
warning system for the project's biggest long-term risk: unbounded merge cost.

---

## Upstream

| | |
|---|---|
| **Repository** | `https://github.com/anomalyco/opencode` |
| **License** | MIT |
| **Default branch** | `dev` (upstream CI targets `dev`) |
| **Integration mechanism** | **Vendor upstream history into this repository** (ADR-0002, decided M1). Upstream added as a git remote; bumps are `git fetch upstream && git merge upstream/dev`. |

## Current pin

| | |
|---|---|
| **Commit** | `4e81a0b` |
| **Subject** | `fix(console): preserve inference sessions (#43124)` |
| **Audited** | 2026-08-18 (M0) |
| **Integrated** | ✅ **Yes**, merged in M3 (commit `f0745dc`). |

The merge went exactly as M1 predicted: **two conflicts, both anticipated**
(`README.md`, `.gitignore`), 6,511 files added, and no upstream-tracked file
caught by our added ignore patterns.

**One thing M1 did not predict:** the first fetch used `--depth=1`, which made the
repository shallow and caused the push to be rejected with
`remote: fatal: did not receive expected object`. Vendoring by merge requires
full upstream history — `git fetch --unshallow upstream dev`. Recorded here so a
future bump does not repeat it.

---

## Divergence ledger

Every upstream file this project modifies goes here, with the reason. An entry is
a liability: it must be re-applied and re-verified on every upstream bump.

### Anticipated (not yet applied)

| # | File | Change | Milestone | Upstreamable? |
|---|---|---|---|---|
| D1 | `packages/app/src/context/platform.tsx` (~line 20) | Widen `type PlatformName = "web" \| "desktop"` to include `"android"` | M3 | Yes — mechanical union widening |
| D2 | `packages/app/src/context/platform.tsx` (~line 126) | Add an `{ platform: "android"; … }` arm to the `Platform` union | M3 | Yes |
| D4 | `README.md` | Keep ours; upstream's is replaced at the vendoring merge | M2 | No — project identity |
| D5 | `.gitignore` | Upstream's, plus our Android/secrets section | M2 | No — additive, trivial to re-merge |

D1, D2, D3 and D6 are applied; see the next table.

**M1 budgeted 2 upstream source files and 3 edits, plus 2 trivial root-file
merges. M4 came in at 3 files and 4 edits** — one file over, because the
one-handed default (D6) was not foreseen in M1. Measured in M1 by auditing all
28 `platform.platform` branch sites across `packages/app`, `packages/session-ui`,
and `packages/ui`.

Why D3 matters: without it, Android's native `storage` adapter is silently
ignored and persistence falls back to WebView `localStorage`, which is losable on
cache clear. It is a functional bug, not a cosmetic one.

Everything else Android needs is **additive** — new files under `packages/android/`
and `apps/android/`, which never conflict on an upstream merge. Notably,
`packages/android/` falls inside upstream's existing `packages/*` workspace glob,
so **upstream's root `package.json` needs no edit at all**.

### Applied

| # | File | Change | Applied | Verified |
|---|---|---|---|---|
| D1 | `packages/app/src/context/platform.tsx` (line 20) | `type PlatformName = "web" \| "desktop"` → `… \| "android"` | M3 | `git diff` shows +1/−1 |
| D2 | `packages/app/src/context/platform.tsx` (line 129) | Added `\| { platform: "android"; os?: never }` to the `Platform` union | M3 | `git diff` shows +1 |
| D4 | `README.md` | Ours kept over upstream's at the merge | M3 | conflict resolved `--ours` |
| D3 | `packages/app/src/utils/persist.ts` (lines 547, 579) | `platform.platform === "desktop" && !!platform.storage` → `!!platform.storage`, with the six reader occurrences renamed `isDesktop` → `hasNativeStorage` | M4 | `git diff` shows +12/−8 |
| D5 | `.gitignore` | Upstream's verbatim + our section below a marked line | M3 | 0 upstream files newly ignored |
| D6 | `packages/app/src/context/settings.tsx` (~line 424) | `mobileTitlebarPosition` default becomes `"bottom"` when `platform.platform === "android"` | M4 | `git diff` shows +3/−1 |

**Actual divergence in upstream source: 3 files, +17/−10 lines** (`platform.tsx` +2/−1, `persist.ts` +12/−8, `settings.tsx` +3/−1).

Two notes on the applied set:

- **D3 is the one worth upstreaming.** It replaces an identity check with a
  capability check, which is what the surrounding code already meant. Any
  platform that supplies `storage` should get it used.
- **D6 is an identity check on purpose**, and is the one place in this project
  where that is the right answer. There is no capability to test for "is held in
  one hand". It changes only a *default*; the setting remains the user's, and
  upstream already ships both positions.

---

## Upstream facts this project depends on

If any of these change upstream, our integration breaks. Re-verify each on every
bump; the paths are the check.

| # | Fact | Location | Why we depend on it |
|---|---|---|---|
| U1 | `Platform` boundary with mostly-optional capabilities, exported publicly | `packages/app/src/context/platform.tsx`; re-exported from `packages/app/src/index.ts` | The entire Android integration strategy |
| U2 | `ServerConnection` union (`HttpBase`/`Http`/`Sidecar`/`Ssh`) | `packages/app/src/context/server.tsx:181` | The single connection abstraction |
| U3 | Client factories accept `ServerConnection.HttpBase` and add Basic auth | `packages/app/src/utils/server.ts` | Local and remote share one code path |
| U4 | `Server.listen({ port, hostname, username, password, cors })` | `packages/opencode/src/server/server.ts:73` | On-device server startup |
| U5 | Node build target exists | `packages/opencode/script/build-node.ts`, `packages/opencode/src/node.ts` | The leading on-device runtime candidate (ADR-0007) |
| U6 | Server HTTP stack is `node:http` via `@effect/platform-node` | `packages/opencode/src/server/server.ts` | Node-compatibility of the server |
| U7 | Node SQLite adapter exists alongside the Bun one | `packages/effect-sqlite-node`, `@effect/sql-sqlite-bun` in `packages/core` | Node runtime viability |
| U8 | CORS origins are caller-supplied; loopback origins allowed by default | `packages/server/src/cors.ts:13-16` | WebView origin allowlisting |
| U9 | `serve` defaults `hostname` to `127.0.0.1` | `packages/cli/src/commands/commands.ts:46` | Loopback-by-default posture (ADR-0006) |
| U10 | Sidecar pattern: password + loopback + CORS + Basic auth | `packages/desktop/src/main/sidecar.ts` | The model M7 ports |
| U11 | Root `test` script deliberately exits 1 | root `package.json` | CI must never call it |
| U12 | Package manager is `bun@1.3.14` | root `package.json#packageManager` | Reproducible installs |
| U13 | `packages/*` is a workspace glob | root `package.json#workspaces.packages` | Lets `packages/android/` join the workspace with zero root edits (ADR-0010) |
| U14 | Shared UI reaches no Electron API | 1 optional-chained `window.api?.setTitlebar?.()` at `packages/app/src/app.tsx:404`; 0 `node:` imports in app/session-ui/ui | The port is viable at near-zero divergence |
| U15 | SSE is consumed via `fetch` + `ReadableStream`, not `EventSource` | `packages/client/src/generated/client.ts:196` | Basic auth works on the event stream with no upstream change |
| U16 | Terminal uses WebSocket with URL-borne ticket auth | `packages/app/src/components/terminal.tsx:620`; `packages/server/src/handlers/pty.ts:165` | M8 terminal transport |
| U17 | `ServerConnection.local()` is true for `sidecar`/`variant:"base"` | `packages/app/src/context/server.tsx:241` | Android's on-device server is treated as local with no upstream change |
| U18 | Ready payload shape `{ url, username, password }` | `packages/desktop/src/preload/types.ts:19` (`ServerReadyData`) | The Android runtime boundary mirrors it exactly |
| U19 | Health endpoints `/api/health`, fallback `/global/health` | `packages/desktop/src/main/server.ts:186` | Android runtime readiness check |
| U20 | `@opencode-ai/app` and `session-ui` are unpublished; `app` exports raw TS | npm registry; `packages/app/package.json` | Forces in-workspace builds — the basis of ADR-0002 |
| U21 | Desktop main loads `dist/node/node.js` | `packages/desktop/electron.vite.config.ts` (`virtual:opencode-server`) | Upstream already runs the artifact ADR-0007 proposes for Android |

---

## Known issues at the current pin

Problems that exist in upstream at `4e81a0b` and are **not** caused by this
project. Recorded so they are visible rather than quietly worked around, and so a
future bump can check whether they are gone.

| # | Issue | Evidence | Effect here |
|---|---|---|---|
| K1 | `bun run lint` (oxlint) reports **1 error** across the workspace: `'0'-prefixed octal literals and octal escape sequences are deprecated` | Appears in CI amongst warnings for upstream's plugin/ai-sdk files. `packages/android` contains no octal escapes (grepped). Present before this project added any TypeScript. | Whole-workspace lint cannot be our CI gate. `scripts/ci/check-opencode.sh` lints `packages/android` by default; `LINT_ALL=1` runs everything. |

Upstream's own lint totals at this pin, for reference: **4,864 warnings and 1
error** over 3,252 files. The warnings are upstream's normal state — oxlint only
fails on errors.

**On every bump, run with `LINT_ALL=1` and `TYPECHECK_ALL=1`** and update this
table. If K1 has been fixed upstream, re-scope our lint to the whole workspace.

## Bump procedure

1. Read upstream's changelog/commits between the old and new pin.
2. Update the pin above (commit + subject + date).
3. Re-verify **every** U-fact in the table. Any that moved is a breaking change —
   note the new path.
4. Re-apply the divergence ledger; note anything that no longer applies cleanly.
5. Run `scripts/ci/check-opencode.sh`.
6. Rebuild shared-UI assets and re-run the Android build.
7. Run the M-appropriate rows of `docs/TEST_MATRIX.md`.
8. Record the bump and any breakage in `docs/CURRENT_STATUS.md`.

**Never** bump the pin and a feature in the same commit.

## Rules

- Never modify an upstream file without adding a ledger entry.
- Prefer widening a type over branching on platform.
- Prefer an optional `Platform` method over an inline platform check.
- Never regenerate upstream's lockfile as a side effect (`CLAUDE.md` §6).
- If a needed change is genuinely general, consider contributing it upstream —
  an accepted upstream change removes a ledger entry permanently.
