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
| **Integration mechanism** | **Not yet decided** — ADR-0002, decided in M1 |

## Current pin

| | |
|---|---|
| **Commit** | `4e81a0b` |
| **Subject** | `fix(console): preserve inference sessions (#43124)` |
| **Audited** | 2026-08-18 (M0) |
| **Integrated** | ❌ **Not yet.** Read for the M0 architecture audit only. |

At M0 the upstream was cloned to a scratch path for reading. **No upstream code
is present in this repository.** Integration happens in M1 once ADR-0002 is decided.

---

## Divergence ledger

Every upstream file this project modifies goes here, with the reason. An entry is
a liability: it must be re-applied and re-verified on every upstream bump.

### Anticipated (not yet applied)

| # | File | Change | Milestone | Upstreamable? |
|---|---|---|---|---|
| D1 | `packages/app/src/context/platform.tsx` (~line 20) | Widen `type PlatformName = "web" \| "desktop"` to include `"android"` | M3 | Yes — mechanical union widening |
| D2 | `packages/app/src/context/platform.tsx` (~line 126) | Add an `{ platform: "android"; … }` arm to the `Platform` union | M3 | Yes |

**Anticipated divergence budget: 1 file, 2 edits.** M1 must confirm this is the
complete list. If it grows beyond a handful of mechanical edits, ADR-0002 should
be re-opened in favour of a fork (see the trade-off table in `docs/DECISIONS.md`).

### Applied

*None.*

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

---

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
