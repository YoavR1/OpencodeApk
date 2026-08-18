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

## ADR-0002 — Upstream integration mechanism: vendor upstream into this repository

- **Date:** 2026-08-18 · **Milestone:** proposed M0, **decided M1** · **Status:** Accepted
- **Supersedes** the Provisional lean toward a pinned submodule recorded at M0.

**Context.** M1 measured the two things this decision turns on.

*Measurement 1 — can the shared UI be consumed as a package?* **No.** Queried
against the npm registry:

| Package | Registry status |
|---|---|
| `@opencode-ai/app` | **NOT PUBLISHED** |
| `@opencode-ai/session-ui` | **NOT PUBLISHED** |
| `@opencode-ai/ui` | published `1.18.18` |
| `@opencode-ai/sdk` | published `1.18.18` |
| `@opencode-ai/client` | published but `0.0.0` (placeholder) |

`packages/app/package.json` also exports **raw TypeScript** (`".": "./src/index.ts"`)
and builds through `@opencode-ai/app/vite`, which supplies `vite-plugin-solid` and
`@tailwindcss/vite`. The shared application therefore cannot be consumed from a
registry at all; it must be built inside the upstream Bun workspace, exactly as
`packages/desktop` is.

*Measurement 2 — how many upstream files must change?* **One, for M3.**
`packages/app/src/context/platform.tsx`: widen `PlatformName` (~L20) and add an
arm to the `Platform` union (~L126). A second file, `packages/app/src/utils/persist.ts`,
wants two capability-check edits at M4. Nothing in `session-ui` or `ui` needs
touching, and shared UI reaches no Electron API (one optional-chained
`window.api?.setTitlebar?.()` in `app.tsx:404`, a no-op when absent).

**Decision.** **Vendor upstream into this repository** by adding
`https://github.com/anomalyco/opencode` as a git remote and merging its history,
keeping our control layer at the root. Android code is added as **new files**
(`packages/android/`, `apps/android/`), so upstream divergence stays at the
measured minimum.

**Why the alternatives lose.**

| Option | Verdict |
|---|---|
| **C. Consume published npm packages** | **Impossible.** `@opencode-ai/app` and `session-ui` are unpublished. Eliminated by measurement, not preference. |
| **A. Pinned git submodule** | **Rejected.** Our Android renderer must be a workspace package to resolve `@opencode-ai/app`, but a package committed to our repo cannot live inside a submodule. Making our repo the workspace root instead would force us to replicate upstream's root `package.json` — ~60 `catalog:` entries, 17 `patchedDependencies`, and `overrides` — and re-sync all of it on every upstream bump. That is a far larger and *recurring* divergence than the 1–2 files a merge costs. |
| **B. Subtree / vendored copy without history** | **Rejected.** Same file layout as a merge but throws away the ability to `git merge upstream/dev`, which is the entire mechanism for staying current. |
| **D. Vendor upstream history (chosen)** | Full workspace with catalog, patches and overrides intact; `bun install` behaves exactly as upstream intends; upstream CI preserved; bumps are `git fetch upstream && git merge upstream/dev`. |

**Consequences.**

- Divergence is bounded by *modified* files, not by repository size. Additive files
  never conflict, so the merge cost tracks the ledger in `docs/UPSTREAM_SYNC.md`.
- Two of our root files collide with upstream's and must be resolved once, then
  kept: `README.md` (keep ours) and `.gitignore` (upstream's plus our Android
  section). `CLAUDE.md`, `docs/`, `prompts/`, `scripts/` do not collide —
  upstream uses `AGENTS.md`, `packages/docs`, and `script/` (singular).
- `packages/android/` sits inside upstream's existing `packages/*` workspace glob,
  so **no edit to upstream's root `package.json` is required**.
- `apps/android/` matches no upstream workspace glob, so Bun ignores the Gradle
  project.
- **This does not contradict ADR-0001.** That ADR recorded a fact — the repository
  began empty and shared no history with upstream. This ADR decides to adopt
  upstream history from here on. The project is a wrapper in intent and a fork in
  mechanism, and the mechanism is what keeps divergence measurable.

**Execution.** The vendoring merge is the **first task of M2**, not of M1. M1 is a
decision milestone; performing a ~6,500-file merge here would bury the
architecture review it is supposed to deliver in an unreviewable diff.

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

**M1 addition — a hard Android constraint on every candidate.** Since Android 10
(API 29), an app targeting API 29+ cannot `exec()` a file in its writable data
directory (W^X enforcement). A runtime downloaded or unpacked at first launch
therefore **cannot be executed**. Any exec-based candidate must ship its binary
inside the APK as `lib<name>.so` under `jniLibs/<abi>/` and run it from
`context.applicationInfo.nativeLibraryDir`. A JNI in-process candidate avoids
`exec` and is not subject to this. See `docs/ARCHITECTURE.md` §2.5.

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

## ADR-0009 — Android shell: native Kotlin + WebView, not Tauri or Capacitor

- **Date:** 2026-08-18 · **Milestone:** M1 · **Status:** Accepted

**Context.** The shared UI is a vite-built SolidJS SPA. Every candidate shell
consumes the identical static-asset output, so *upstream divergence is the same
across all of them* — it is not a differentiator. What differs is the native layer,
the toolchain, and what we must maintain forever.

The native work this project requires regardless of shell: a foreground service
owning the runtime, Android Keystore credential storage, SAF directory access,
notifications, and process-death handling. **All of that is Kotlin either way.**

**Decision.** A native Android application in Kotlin, hosting the shared UI in a
`WebView` served by `androidx.webkit.WebViewAssetLoader`, with a typed
`WebMessagePort` bridge.

**Comparison.**

| Criterion | **Native Kotlin + WebView** | Tauri v2 | Capacitor | CEF / GeckoView |
|---|---|---|---|---|
| Upstream divergence | identical | identical | identical | identical |
| Android support maturity | platform itself | production-capable, but the newest Tauri target | very mature | not a realistic host |
| Native bridge quality | `WebMessagePort`, typed, exactly as wide as we make it | Rust `invoke` commands, then Kotlin via plugin plumbing | JS plugin layer over Kotlin | n/a |
| WebView behaviour | system WebView, direct `WebSettings` control | system WebView via `wry` | system WebView | bundles a whole browser engine |
| Extra toolchain | none — JDK + Android SDK | **Rust + Android NDK + cargo-mobile** | Node + Capacitor CLI | enormous |
| Path to a foreground service | direct | through Tauri's generated Android project | through a plugin | n/a |
| Total moving parts | Kotlin only | Kotlin **+** Rust **+** Tauri | Kotlin **+** JS plugin layer | n/a |

**Rejected — Tauri v2.** Genuinely production-capable on Android as of 2026, and
not rejected on capability grounds. Rejected because it adds a Rust and NDK
toolchain to CI and an indirection layer in front of Android services we must
write in Kotlin anyway, buying nothing in return: our UI is already built by vite,
so Tauri's main contribution — packaging a web app — is the part we least need.
Its cross-platform story is also irrelevant here, since upstream already ships
desktop via Electron. Noted separately: upstream's desktop **was** Tauri and
migrated to Electron (`packages/desktop/src/main/migrate.ts`), so there is no
Tauri investment upstream for us to reuse.

**Rejected — Capacitor.** Mature on Android, but it is a JS-centric wrapper whose
value is its plugin ecosystem. We need a handful of bespoke capabilities
(foreground-service runtime, Keystore, SAF) that we would write as custom plugins
in Kotlin regardless. That is the native path plus a layer.

**Rejected — embedded CEF / GeckoView.** Bundling a browser engine adds tens of
megabytes to an APK that must also carry a JS runtime. Disproportionate.

**Rejected — native UI rewrite (Compose / React Native / Flutter).** Excluded by
the charter. It would discard the entire shared UI and create unbounded
divergence.

**Consequences.**

- CI needs only JDK 21 and the Android SDK — already what `android-ci.yml` provisions.
- The bridge surface is ours to define and keep minimal (`docs/ARCHITECTURE.md` §2.4).
- No framework upgrade treadmill beyond AGP/Kotlin/androidx.
- We own more low-level code than a framework would give us — accepted, because
  that code is exactly the part that must be right for lifecycle and security.

---

## ADR-0010 — Android project layout

- **Date:** 2026-08-18 · **Milestone:** M1 · **Status:** Accepted

**Decision.**

- **`packages/android/`** — the SolidJS renderer package (`@opencode-ai/android`),
  mirroring `packages/desktop/src/renderer`.
- **`apps/android/`** — the Gradle project.

**Rationale.** Upstream's `workspaces.packages` already includes the glob
`packages/*`, so a package placed there joins the Bun workspace and resolves
`@opencode-ai/app`, the shared `catalog:`, `patchedDependencies`, and `overrides`
**with no edit to upstream's root `package.json`**. Conversely `apps/*` matches no
upstream workspace glob, so Bun ignores the Gradle project entirely rather than
trying to interpret it as a package.

**Consequences.** The Android renderer is built exactly like the desktop
renderer — its own `index.html` and vite root, with `@opencode-ai/app/vite` as the
plugin. Vite output is copied into `apps/android/app/src/main/assets/web/` at
build time and is git-ignored.

---

## ADR-0011 — Android toolchain and API levels

- **Date:** 2026-08-18 · **Milestone:** M1 · **Status:** Accepted (revisit `minSdk` at M6)

**Decision.**

| Setting | Value | Reason |
|---|---|---|
| JDK | **21** | Already provisioned in CI and present in cloud sessions (`openjdk 21.0.10`). |
| Gradle | **8.14.3+** via committed wrapper | Matches the version available locally; the wrapper is the source of truth. |
| AGP | **8.x**, latest stable compatible with the wrapper | |
| Kotlin | latest stable supported by the chosen AGP | |
| `compileSdk` / `targetSdk` | **36** | Stay current; Play requires a recent target and W^X behaviour is already in force. |
| `minSdk` | **26** (Android 8.0) | Modern process, filesystem, and notification-channel behaviour. Below 26 the foreground-service and notification model diverges enough to cost real complexity. |
| Primary ABI | **`arm64-v8a`** | Real devices. `x86_64` may be added for emulator CI only. |

**Note.** `minSdk` may need to rise if M6's runtime choice requires it. That is a
legitimate reason to revisit; reach is not.

## Open questions (not yet ADRs)

| # | Question | Decide at |
|---|---|---|
| ~~Q1~~ | ~~Toolchain and API levels~~ | **Resolved — ADR-0011** |
| ~~Q2~~ | ~~Android project location~~ | **Resolved — ADR-0010** |
| ~~Q3~~ | ~~Are shared-UI packages published to npm?~~ | **Resolved — no. See ADR-0002** |
| ~~Q4~~ | ~~Assets bundled vs. downloaded~~ | **Resolved — bundled. Forced by W^X (ADR-0007) and required for offline use** |
| Q5 | Terminal/PTY viability on Android (`@lydell/node-pty` is native; WebSocket transport confirmed) | M6/M8 |
| Q6 | SQLite data-at-rest encryption | M10 |
| Q7 | Distribution channel (Play Store, GitHub Releases, F-Droid) | M11 |
| Q8 | `EmbeddedProcessRuntime` (exec `lib*.so`) vs `EmbeddedInProcessRuntime` (JNI in a `:opencode` process) | M6 |
| Q9 | Does SSE over `fetch` + `ReadableStream` work reliably in Android WebView? | M5 — verify on a real device |
