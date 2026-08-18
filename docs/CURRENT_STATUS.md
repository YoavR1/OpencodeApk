# Current Status

> **This file is the authoritative state of the project.** Every session reads it
> first and updates it before finishing. If it disagrees with the repository, the
> repository is right — fix this file.

| | |
|---|---|
| **Last updated** | 2026-08-18 |
| **Session** | M0 bootstrap (first cloud session) |
| **Branch** | `claude/opencode-android-bootstrap-o58939` |
| **Current milestone** | **M0 — complete** |
| **Next milestone** | **M1 — architecture audit** |
| **Next prompt** | **`prompts/01_ARCHITECTURE_AUDIT.md`** |

---

## Where the project actually is

A **project-control layer and nothing else.** There is no application code, no
Android project, and no upstream OpenCode code in this repository.

That is the correct state for the end of M0. The bootstrap session was explicitly
instructed not to begin the Android implementation.

## What this session did

1. Inspected the repository and its remote — found both empty.
2. Cloned upstream `anomalyco/opencode` to a scratch path and **read the real
   architecture** rather than assuming it.
3. Wrote the control layer: `CLAUDE.md`, `.claude/**`, `docs/**`, `prompts/**`,
   `scripts/**`, `.github/workflows/android-ci.yml`, `.gitignore`.
4. Ran the baseline checks and recorded the real output (below).

---

## Repository state — VERIFIED

At session start:

```
$ git status
On branch claude/opencode-android-bootstrap-o58939
No commits yet
nothing to commit (create/copy files and use "git add" to track)

$ git ls-files | wc -l
0

$ ls -la
total 12
drwxr-xr-x 3 root root 4096 Aug 18 08:14 .
drwxr-xr-x 3 root root 4096 Aug 18 08:14 ..
drwxr-xr-x 7 root root 4096 Aug 18 08:15 .git

$ git ls-remote origin
(no output — the remote has no refs)
```

**Conclusion:** completely empty repository, no upstream history, no shared
objects with `anomalyco/opencode`. This is a **new wrapper repository**, not a
fork or a copy (ADR-0001).

## Environment — VERIFIED

```
node    v22.22.2
npm     10.9.7
bun     1.3.11          <-- upstream declares bun@1.3.14
pnpm    10.33.0
go      go1.24.7 linux/amd64
java    openjdk 21.0.10 2026-01-20
gradle  8.14.3 (system)
git     2.43.0

ANDROID_HOME     (unset)
ANDROID_SDK_ROOT (unset)
```

Host architecture is `x86_64`. The device target is `arm64-v8a`.

**Consequences:**
- No Android SDK → `./gradlew assembleDebug` cannot run in a cloud session.
  **CI is the Android build authority** (`docs/CLAUDE_CLOUD_SETUP.md`).
- The Bun version gap is noted, not "fixed". Do not upgrade Bun or regenerate the
  lockfile to work around it.

## Upstream audit — VERIFIED

Cloned and read at commit `4e81a0b` — *"fix(console): preserve inference sessions
(#43124)"*.

Findings (full detail in `docs/ARCHITECTURE.md`):

| # | Finding | Evidence |
|---|---|---|
| 1 | Bun workspace monorepo, `packageManager: "bun@1.3.14"`, Turborepo, oxlint, `tsgo` | root `package.json`, `turbo.json` |
| 2 | Root `test` script **deliberately** exits 1 | `"test": "echo 'do not run tests from root' && exit 1"` |
| 3 | A `Platform` boundary already exists, with mostly-**optional** capabilities | `packages/app/src/context/platform.tsx` (`PlatformName` ~L20, `Platform` union ~L126) |
| 4 | A **single** connection abstraction already exists | `packages/app/src/context/server.tsx:181` — `ServerConnection.{HttpBase,Http,Sidecar,Ssh}` |
| 5 | Client factories take `ServerConnection.HttpBase` and add Basic auth | `packages/app/src/utils/server.ts` |
| 6 | The desktop uses a **sidecar** pattern: separate process, loopback, per-launch password, CORS scoped to the renderer origin | `packages/desktop/src/main/sidecar.ts` |
| 7 | `Server.listen({ port, hostname, username, password, cors })` | `packages/opencode/src/server/server.ts:73` |
| 8 | Server HTTP stack is `node:http`'s `createServer` via `@effect/platform-node` — **not Bun-only** | same file |
| 9 | **A Node build target already exists** | `packages/opencode/script/build-node.ts` (`target: "node"`, entry `src/node.ts`) |
| 10 | `src/node.ts` exports exactly a host-embedding surface | `Config`, `Server`, `bootstrap`, `Database` |
| 11 | A Node SQLite adapter exists in-tree alongside the Bun one | `packages/effect-sqlite-node`; `@effect/sql-sqlite-bun` in `packages/core` |
| 12 | `serve` defaults `hostname` to `127.0.0.1` | `packages/cli/src/commands/commands.ts:46` |
| 13 | CORS allows loopback and `tauri://` origins by default; callers supply more | `packages/server/src/cors.ts:13-16` |
| 14 | Native deps needing Android ARM64 assessment | `@lydell/node-pty`, `@parcel/watcher`, `tree-sitter-bash`, `tree-sitter-powershell`; `web-tree-sitter` is **WASM** |

**Findings 9–11 are the most consequential.** Upstream already produces a
Node-targeted server bundle and maintains a Node SQLite path, which makes a
Node-compatible on-device runtime the leading M7 candidate (ADR-0007,
**Provisional** — M6 must prove it).

**Findings 3–5 are the second most consequential.** Both boundaries the charter
demands already exist upstream, so the Android work is an *implementation of*
existing abstractions rather than an invention of new ones (ADR-0004).

## Baseline checks — VERIFIED

Actually run in this session, exactly as shown:

```
$ scripts/ci/check-opencode.sh
== check-opencode ==
PHASE: upstream OpenCode is not integrated into this repository yet.
       (no root package.json, no packages/ directory)
       This is the expected state before M1. Nothing to check.
       See ADR-0002 in docs/DECISIONS.md.
EXIT=0

$ scripts/ci/build-android.sh
== build-android ==
PHASE: no Android Gradle project found (searched for a 'gradlew' wrapper).

This is the expected state before milestone M2 (first Android APK shell).
It is deliberately NOT reported as a failure and NOT as a success.
EXIT=3

$ scripts/ci/verify-apk.sh
== verify-apk ==
PHASE: no APK found.
       Expected before milestone M2 (first Android APK shell).
       Reported as a phase state, not a failure and not a success.
EXIT=3
```

All three correctly report a **phase state** rather than a fake pass or a fake
failure, which is what the CI design requires.

Shell syntax (`bash -n`) and JSON validity were checked for every script and for
`.claude/settings.json`; the workflow YAML parses. The full hygiene check that
CI runs was also executed locally and passed (`OVERALL FAIL=0`).

`scripts/cloud/session-start.sh` was run rather than merely written, which
surfaced three real defects that were then fixed:

1. `java -version` and `gradle --version` output was polluted by this
   environment's `Picked up JAVA_TOOL_OPTIONS:` stderr banner — now filtered.
2. Branch detection printed `HEAD` followed by `(unknown)`, because
   `git rev-parse --abbrev-ref HEAD` both prints `HEAD` **and** exits non-zero on a
   repository with no commits, so the fallback fired too. Replaced with
   `git branch --show-current`.
3. `gradle --version` leads with a blank line and a banner rule, so the naive
   "first line" read produced an empty value, then a row of dashes.

Final output is correct: `java : openjdk version "21.0.10" 2026-01-20`,
`gradle : Gradle 8.14.3`, `branch : claude/opencode-android-bootstrap-o58939`.

## Not run — BLOCKED, with reasons

| Check | Why not |
|---|---|
| `bun install` | No `package.json` in this repository yet. Upstream is not integrated (M1). |
| `bun run lint` / `bun run typecheck` | Same — nothing to lint or typecheck. |
| Any upstream package test | Upstream is not integrated. Note: the **root** `test` script must never be run. |
| `./gradlew assembleDebug` | No Gradle project (M2) **and** no Android SDK in this environment. |
| Any device or emulator test | No device; host is `x86_64`, target is `arm64-v8a`. |
| `.github/workflows/android-ci.yml` end to end | Not yet pushed at the time of writing. **Verify on the first CI run.** |

## Assumptions not yet verified — ASSUMED

These are believed true from reading code, not from execution. They are the first
things M1 should confirm:

1. `packages/app` can be built to relocatable static assets suitable for an APK.
2. Widening `PlatformName` and the `Platform` union is the **only** upstream change
   needed for an Android platform.
3. A Node-compatible runtime can host `packages/opencode/dist/node` on Android
   ARM64 (ADR-0007 — **this is a hypothesis for M6, not a conclusion**).
4. The shared UI's streaming/event transport works inside Android WebView (M5).

---

## Decisions made this session

| ADR | Decision | Status |
|---|---|---|
| 0001 | New wrapper repository, not a fork | Accepted |
| 0002 | Upstream integration mechanism | **Provisional — decide in M1** |
| 0003 | Remote-server mode is a checkpoint, never the deliverable | Accepted |
| 0004 | Use upstream's existing `Platform` and `ServerConnection` boundaries | Accepted |
| 0005 | Port the desktop sidecar pattern; never port Electron | Accepted |
| 0006 | Loopback binding with authentication always on | Accepted |
| 0007 | Node-targeted runtime is the leading candidate | **Provisional — decide in M6** |
| 0008 | Serve WebView assets from an app origin, not `file://` | Accepted |

## Files created

```
CLAUDE.md
.gitignore
.claude/settings.json
.claude/rules/{architecture,android,quality}.md
docs/{PROJECT_CHARTER,ARCHITECTURE,IMPLEMENTATION_PLAN,CURRENT_STATUS,
      DECISIONS,TEST_MATRIX,UPSTREAM_SYNC,CLAUDE_CLOUD_SETUP,PHONE_WORKFLOW}.md
prompts/{README,00_BOOTSTRAP,01_ARCHITECTURE_AUDIT,02_ANDROID_SHELL,03_SHARED_UI,
         04_MOBILE_PLATFORM_ADAPTER,05_REMOTE_SERVER_MODE,06_LOCAL_RUNTIME_SPIKE,
         07_LOCAL_RUNTIME_INTEGRATION,08_TERMINAL_FILES_GIT,09_ANDROID_LIFECYCLE,
         10_SECURITY_STORAGE,11_POLISH_RELEASE,REVIEW_PROMPT}.md
scripts/cloud/session-start.sh
scripts/ci/{check-opencode,build-android,verify-apk}.sh
.github/workflows/android-ci.yml
```

---

## What is NOT done

- **No Android implementation.** No Gradle project, no Kotlin, no APK. Deliberate —
  M0 was scoped to exclude it.
- **No upstream integration.** No upstream code is in this repository. The
  mechanism is still undecided (ADR-0002).
- **No tests exist.** `docs/TEST_MATRIX.md` is a plan, not a result.
- **CI has never run.** The workflow is written and its YAML parses, but it has not
  executed. Its real behaviour is unverified until the first push.
- **Nothing has run on a phone.**

## Recommended next session

Paste **`prompts/01_ARCHITECTURE_AUDIT.md`** into a fresh Claude Code cloud session.

Its central task is to **resolve ADR-0002** — the upstream integration mechanism —
by measuring how many upstream files actually need patching. That single
measurement is the highest-leverage open question in the project, because it
determines the maintenance cost of everything built afterwards.

Before that session does anything else, it should confirm that the first CI run on
this branch behaved as designed: the `hygiene` job green, and
`android build (pre-M2 phase)` green **with a summary saying nothing was built**.
