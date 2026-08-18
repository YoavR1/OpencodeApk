# M6 — Local Runtime Feasibility Spike

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

## Read this first

This is the **highest-risk milestone in the project**. Everything about the end
goal — "no external OpenCode server required" — depends on its outcome.

**This is a spike. Write no production code.** The deliverable is a report backed
by artifacts.

## Goal

Determine, **with evidence**, how to run the OpenCode server on Android ARM64 —
or establish that no candidate is viable.

## The candidates

From `docs/ARCHITECTURE.md` §2.4:

| # | Approach | Basis | Main risk |
|---|---|---|---|
| A | Node-compatible runtime in the APK running `dist/node` | `build-node.ts` targets Node; `effect-sqlite-node` exists | Android ARM64 Node builds; native module gaps |
| B | Bun as an ARM64 Android binary | Upstream is Bun-first | Bun does not target Android officially |
| C | Port the server to a JS engine already present on Android | No extra binary | The Node/Bun API surface in `core` is large |
| D | JVM/native reimplementation | Native | Violates "reuse upstream"; enormous divergence |

**A is the leading candidate** (ADR-0007, Provisional), on three pieces of
evidence found in M0:

1. `packages/opencode/script/build-node.ts` already produces a Node-targeted ESM
   bundle from `src/node.ts`, with `jsonc-parser` and `@lydell/node-pty` marked
   `external`.
2. `src/node.ts` exports exactly a host-embedding surface: `Config`, `Server`,
   `bootstrap`, `Database`.
3. The server's HTTP stack is `node:http`'s `createServer` via
   `@effect/platform-node` — not Bun-only. And a Node SQLite adapter
   (`@opencode-ai/effect-sqlite-node`) is maintained in-tree alongside the Bun one.

**This is a hypothesis, not a conclusion. Test it.**

## Tasks

1. **Produce `packages/opencode/dist/node`** and inventory what it actually
   requires at runtime — imports, native bindings, filesystem assumptions,
   subprocess use, environment variables.

2. **Build the native-dependency matrix.** For each, its Android ARM64 status and
   the fallback if unavailable:

   | Dependency | Kind | Fallback to investigate |
   |---|---|---|
   | `@lydell/node-pty` | native N-API | pipe-based process IO; or terminal disabled (M8 decision) |
   | `@parcel/watcher` | native N-API | polling watcher; `inotify` directly |
   | `tree-sitter-bash`, `tree-sitter-powershell` | native | **`web-tree-sitter` (WASM) — already a dependency** |
   | SQLite driver | Bun vs Node | `@opencode-ai/effect-sqlite-node` |

3. **Test the chosen candidate on a real ARM64 Android target** (emulator or
   device). Booting the runtime and serving a successful `/health` response is
   decisive evidence. Reasoning about it is not.

4. **Measure**: cold start time, resident memory, and APK size impact. A runtime
   that works but adds 150MB and takes 30 seconds to start is a different decision
   from one that does not.

5. **Write `docs/LOCAL_RUNTIME_REPORT.md`**: candidates evaluated, what you ran,
   what actually happened, the dependency matrix, measurements, a recommendation,
   and an explicit list of features that would be lost or degraded.

6. **Record the runtime decision as an ADR** and update ADR-0007 (confirm it, or
   supersede it).

## Constraints

- **`arm64-v8a` is the target.** An x86_64-only result proves nothing about phones.
- No root. No Termux as a shipped dependency — Termux is a legitimate *research
  reference* for what runs on Android ARM64, nothing more.
- Evidence over reasoning. A plausible argument is not a result.

## If no candidate is viable

Say so plainly. Record the disproof with its evidence. **Escalate to me before
proceeding.** Do not quietly redefine the project as remote-only — that would
fail the charter, and I would rather know.

## Exit criteria

See M6 in `docs/IMPLEMENTATION_PLAN.md`. A written report with real artifacts, a
clear recommendation or a clear negative result, the dependency matrix, an ADR,
and an explicit feature-loss list.

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
