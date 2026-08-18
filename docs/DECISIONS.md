# Decision Log (ADRs)

Append-only. Never delete an entry — supersede it with a new one and mark the old
one **Superseded by ADR-XXXX**.

Statuses: `Accepted` · `Provisional` (decided, must be confirmed with evidence at
the stated milestone) · `Open` (not decided) · `Superseded`.

---

## ADR-0001 — This is a new wrapper repository, not a fork of upstream

- **Date:** 2026-08-18 · **Milestone:** M0 · **Status:** Accepted

**Context.** The repository had to be classified before anything else. Observed
state at session start:

```
$ git status
On branch claude/opencode-android-bootstrap-o58939
No commits yet
nothing to commit (create/copy files and use "git add" to track)

$ git ls-files | wc -l
0

$ git ls-remote origin      # empty output — remote has no refs
```

The repository was completely empty: no commits locally, no refs on the remote.
It contains no upstream history and shares no objects with `anomalyco/opencode`.

**Decision.** Treat OpencodeApk as a **new wrapper repository** that consumes
upstream OpenCode as a dependency, rather than a fork that diverges from it.

**Rationale.** Upstream is developed rapidly (HEAD at audit time was
`4e81a0b`, PR #43124). A fork accrues merge cost on every upstream commit. A
wrapper with a pinned upstream and an explicit patch ledger keeps that cost
bounded and visible.

**Consequences.** We need an explicit integration mechanism (ADR-0002) and a
divergence ledger (`docs/UPSTREAM_SYNC.md`).

---

## ADR-0002 — Upstream integration mechanism

- **Date:** 2026-08-18 · **Milestone:** M0 proposed, **decided in M1** · **Status:** Provisional

**Context.** Given ADR-0001, upstream code must reach this repository somehow.
Four options:

| Option | Upstream reuse | Patchability | Bump cost | Repo size |
|---|---|---|---|---|
| A. Git submodule, pinned commit | Full source | Poor — cannot commit edits into the submodule | Low (move the pin) | Small |
| B. Subtree / vendored copy | Full source | Good | High (merge conflicts) | Large (6,512 files at audit) |
| C. Consume published npm packages | Published packages only | None | Low | Smallest |
| D. Fork | Full source | Best | Highest | Large |

**Key evidence pulling against C:** the required change for Android is widening
`PlatformName` in `packages/app/src/context/platform.tsx` (line ~20) and the
`Platform` union (line ~126). Published packages cannot be edited, so C only
works if that widening lands upstream first, or if the union can be bypassed —
neither is established. Also relevant: `@opencode-ai/app` is a workspace package;
whether it is published at all must be checked in M1.

**Key evidence pulling toward A:** upstream already has a `patches/` directory and
uses Bun's `patchedDependencies` — patching a pinned dependency is an established
pattern in this codebase, so a patch-overlay workflow is idiomatic here.

**Provisional decision.** Start from **A (pinned submodule) plus a small tracked
patch set**, in the style of upstream's own `patches/` mechanism.

**This must be confirmed or overturned in M1** on the basis of one measurement:
*how many upstream files actually need patching to build the shared UI with an
Android platform?* If the answer is 1–2 mechanical edits, A holds. If it grows,
reopen and consider D.

**Do not treat this as settled.** It is the single highest-leverage structural
decision in the project.

---

## ADR-0003 — Remote-server mode is a checkpoint, never the deliverable

- **Date:** 2026-08-18 · **Milestone:** M0 · **Status:** Accepted

**Context.** Remote-server mode is far easier than an on-device runtime and could
easily be mistaken for completion.

**Decision.** M5 (remote server) is an **integration checkpoint** whose purpose is
to validate the UI↔server path before the hard runtime work. The project is not
complete until M7 runs the server on-device.

**Consequences.** Status reporting must never present M5 as the goal. Both modes
must share one `ServerConnection` path so M5 work is not thrown away.

---

## ADR-0004 — Use upstream's existing `Platform` and `ServerConnection` boundaries

- **Date:** 2026-08-18 · **Milestone:** M0 · **Status:** Accepted

**Context.** The charter requires an isolated Android boundary and a single
connection abstraction. Reading upstream showed **both already exist**:

- `packages/app/src/context/platform.tsx` — a `Platform` type whose capability
  methods are almost all optional, provided via `PlatformProvider`, consumed via
  `usePlatform()`, exported from `packages/app/src/index.ts`.
- `packages/app/src/context/server.tsx:181` — `ServerConnection` with
  `HttpBase | Http | Sidecar | Ssh`, consumed by `packages/app/src/utils/server.ts`
  to build authenticated clients.

**Decision.** Use both as-is. Do not create parallel abstractions.

**Consequences.** Android ships as a `Platform` implementation plus a
`ServerConnection` value. The only anticipated upstream edit is widening
`PlatformName` and the `Platform` union to admit `"android"`.

---

## ADR-0005 — Port the desktop sidecar pattern; never port Electron

- **Date:** 2026-08-18 · **Milestone:** M0 · **Status:** Accepted

**Context.** `packages/desktop/src/main/sidecar.ts` starts the server
out-of-process on loopback with a per-launch password and a CORS allowlist scoped
to the renderer origin (`oc://renderer`), then points the webview at it.

**Decision.** Port that **pattern** to Android (foreground Service + WebView).
Port **none** of Electron's runtime. No Android target is added to
`electron-builder`.

**Consequences.** Android startup mirrors: generate password → start server on
`127.0.0.1` → pass the WebView origin as CORS allowlist → connect with Basic auth
→ stop on shutdown.

---

## ADR-0006 — Loopback binding with authentication always on

- **Date:** 2026-08-18 · **Milestone:** M0 · **Status:** Accepted

**Context.** On Android, any app can reach another app's loopback ports. Loopback
is therefore not a trust boundary. Upstream already defaults to loopback:
`packages/cli/src/commands/commands.ts:46` sets `hostname` default `"127.0.0.1"`.

**Decision.** The on-device server binds `127.0.0.1` only, and HTTP Basic auth
stays enabled with a per-launch generated password held in memory. Binding to
`0.0.0.0` would be an explicit, opt-in, ADR-gated feature.

**Consequences.** Cleartext HTTP is scoped to `127.0.0.1` in the network security
config. The password is never persisted and never logged.

---

## ADR-0007 — Node-targeted runtime is the leading candidate, pending M6 evidence

- **Date:** 2026-08-18 · **Milestone:** M0 hypothesis, **decided in M6** · **Status:** Provisional

**Context.** Upstream is Bun-first, but reading the code found two facts that
matter more:

1. `packages/opencode/script/build-node.ts` already produces a **Node-targeted**
   ESM bundle from `src/node.ts`, with `jsonc-parser` and `@lydell/node-pty`
   marked `external`. `src/node.ts` exports `Config`, `Server`, `bootstrap`,
   `Database` — exactly a host-embedding surface.
2. The server's HTTP stack is `node:http`'s `createServer` via
   `@effect/platform-node`'s `NodeHttpServer` — not a Bun-only server.
3. A Node SQLite adapter exists in-tree (`@opencode-ai/effect-sqlite-node`)
   alongside the Bun one (`@effect/sql-sqlite-bun`).

**Provisional decision.** Treat a Node-compatible on-device runtime as the leading
candidate for M7.

**This is a hypothesis, not a conclusion.** M6 must test it against real ARM64
Android and produce artifacts. Known unresolved risks: availability of a Node
runtime for Android ARM64, and native modules `@lydell/node-pty`,
`@parcel/watcher`, and `tree-sitter-bash`/`tree-sitter-powershell`
(`web-tree-sitter` is WASM and is the known-portable fallback for parsing).

**If M6 disproves this,** record the disproof and re-open the runtime decision —
do not quietly fall back to remote-only.

---

## ADR-0008 — Serve WebView assets from an app origin, not `file://`

- **Date:** 2026-08-18 · **Milestone:** M0 · **Status:** Accepted

**Context.** The shared UI is a modern SPA that relies on `fetch`, CORS, and web
storage. `file://` origins are opaque and break these.

**Decision.** Serve APK-bundled assets via `WebViewAssetLoader` on an `https://`
app origin. Add that exact origin to the server's CORS allowlist through the
`cors` option of `Server.listen()`. Keep `setAllowFileAccessFromFileURLs` and
`setAllowUniversalAccessFromFileURLs` **false**.

**Consequences.** Mirrors desktop, which passes `["oc://renderer"]`. Upstream's
default allowlist (`packages/server/src/cors.ts:13-16`) already permits
`http://localhost:` and `http://127.0.0.1:` origins, so the custom origin is the
only addition needed.

---

## Open questions (not yet ADRs)

| # | Question | Decide at |
|---|---|---|
| Q1 | `minSdk`, `compileSdk`, AGP, Gradle, Kotlin versions | M1 |
| Q2 | Android project location (`apps/android/` suggested) | M1/M2 |
| Q3 | Are shared-UI packages published to npm, or workspace-only? | M1 |
| Q4 | Shared UI assets bundled in the APK vs. downloaded | M3 |
| Q5 | Terminal/PTY viability on Android | M6/M8 |
| Q6 | SQLite data-at-rest encryption | M10 |
| Q7 | Distribution channel (Play Store, GitHub Releases, F-Droid) | M11 |
