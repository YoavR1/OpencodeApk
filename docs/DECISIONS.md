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

- **Date:** 2026-08-18 · **Milestone:** M1, **corrected in M2 by CI evidence** · **Status:** Accepted

**The M1 version of this ADR was wrong and CI proved it.** It chose AGP 8.13.2 on
the reasoning that the AGP 9.x DSL could not be validated from a cloud session
without an Android SDK, and that a green CI build mattered more than being
current. The first real build failed at `CheckAarMetadata`:

```
1. Dependency 'androidx.core:core:1.19.0' requires libraries and applications that
   depend on it to compile against version 37 or later of the Android APIs.
   :app is currently compiled against android-36.
2. Dependency 'androidx.core:core:1.19.0' requires Android Gradle plugin 9.1.0 or higher.
   This build currently uses Android Gradle plugin 8.13.2.
```

The current androidx line *requires* AGP 9.1+. Staying on AGP 8.x would have meant
pinning deliberately older libraries on a brand-new project and migrating anyway —
later, during M7, when the build carries native libraries and packaging rules and
the migration is far more expensive.

**Decision.**

| Setting | Value | Reason |
|---|---|---|
| AGP | **9.3.1** | Required by androidx.core 1.19.0; latest stable |
| Gradle | **9.7.0** via committed wrapper | AGP 9.3.1 needs Gradle 9.x APIs — proven locally: under Gradle 8.14.3 it fails with `NoClassDefFoundError: org/gradle/features/binding/ProjectTypeBinding` |
| Kotlin plugin | **none** | AGP 9 ships built-in Kotlin. Applying `org.jetbrains.kotlin.android` is a hard error: *"no longer required for Kotlin support since AGP 9.0"* |
| JDK | **21** | Provisioned in CI and present in cloud sessions |
| `compileSdk` | **37** | Demanded by androidx.core 1.19.0 |
| `targetSdk` | **36** | Deliberately one behind. `targetSdk` opts into runtime behaviour changes this project has not tested; Google's own guidance separates the two. Lint's `OldTargetApi` warning is the accepted, visible cost |
| `minSdk` | **26** (Android 8.0) | Modern process, filesystem and notification-channel behaviour |
| Primary ABI | **`arm64-v8a`** | Real devices. `x86_64` may be added for emulator CI only |

**Consequences.**

- `android.nonTransitiveRClass` is not set — AGP 9 removed the flag.
- `kotlinOptions` and a `kotlin { compilerOptions }` block are both absent; AGP 9's
  built-in Kotlin owns that configuration.
- `lint.htmlReport` / `xmlReport` are not set — AGP 9 always generates reports and
  the setters are deprecated. Lint now emits HTML and SARIF.
- `minSdk` may still need to rise if M6's runtime choice requires it. Reach is not
  a reason to revisit; the runtime is.

**Process note.** The M1 reasoning — "avoid what cannot be validated locally" — was
sound in itself but was applied to a question that only a real build could answer.
The fix was to make local validation possible: the Android SDK command-line tools
were installed in the session so the build could be iterated in seconds instead of
CI rounds. `docs/CLAUDE_CLOUD_SETUP.md` previously discouraged that; it has been
amended to distinguish *shortening a real debug loop* (worthwhile) from *manufacturing
local green output in place of CI* (still discouraged).

---

## ADR-0012 — Vendoring upstream moves from M2 to M3

- **Date:** 2026-08-18 · **Milestone:** M2 · **Status:** Accepted
- **Amends the execution timing in ADR-0002. The integration mechanism itself is unchanged.**

**Context.** ADR-0002 decided to vendor upstream history into this repository and
scheduled the merge as M2's first task. Two things about M2 make that the wrong
moment.

1. **M2 does not need it.** The M2 shell renders a page bundled in the APK. The
   Gradle project has no dependency on upstream at all. The first thing that
   genuinely needs upstream is the shared-UI asset build in M3.
2. **It would put M2's own acceptance criterion at risk.** Merging turns on the
   `opencode-checks` CI job, which runs `bun install --frozen-lockfile`,
   `bun run lint` and `bun run typecheck` across roughly thirty upstream packages.
   "Do not mark complete until Gradle and CI are green" would then depend on code
   this project has not written and cannot fix.

There is also a review cost: the merge adds ~6,500 files to a diff intended to be
reviewed on a phone, alongside the Android shell it would bury.

**Decision.** Vendor upstream at the **start of M3**, where the shared-UI build
needs it, rather than at the start of M2.

**Consequences.**

- M2's diff stays small and reviewable, and its CI signal is about the Android
  build alone.
- M3 absorbs the merge plus the first upstream `bun install`. When
  `opencode-checks` first runs, any redness in it is upstream's, and must be
  reported as such rather than "fixed" by weakening the check.
- Nothing about ADR-0002 changes: the mechanism is still a merge of upstream
  history, and the measured divergence (D1–D5) is unaffected.

**Rejected alternative.** Merging now and disabling `opencode-checks` until M3.
That would make CI green by removing a check rather than by passing it, which is
the exact failure mode `.claude/rules/quality.md` Q4 forbids.

---

## ADR-0013 — The bridge is a `WebMessagePort`, not `addJavascriptInterface`

**Status.** Accepted (M4). Supersedes the three-method host object M3 shipped.

**Context.** M3's bridge was an `addJavascriptInterface` object with three
fire-and-forget methods. M4 needs return values, binary payloads (clipboard
images, draft blobs) and host-initiated events (lifecycle, keyboard, back), none
of which that shape supports.

`addJavascriptInterface` also injects a reflective Java object into *every* frame
in the WebView. With three void methods that was tolerable. With twenty-one
methods reaching preferences, the clipboard, the document picker and
notifications, it is not.

**Decision.** A single `WebMessagePort` pair. The host keeps one end and posts
the other into the page, scoped to the app origin (`WebOrigin.ORIGIN`), so no
other origin can receive it. Messages are JSON envelopes:

```
renderer → host   {"id":1,"method":"store.get","params":{…}}
host → renderer   {"id":1,"ok":true,"result":…}
                  {"id":1,"ok":false,"error":{"code":"…","message":"…"}}
host → renderer   {"event":"lifecycle","state":"resumed"}     (no id)
```

**Consequences.**

- The page can send strings down a channel; it cannot enumerate or reflect on
  native objects. What any string *means* is decided entirely by
  `BridgeHost`'s `when`.
- Unknown methods are refused **by name**, so a typo surfaces as a rejected
  promise rather than one that never settles.
- The method list exists twice — a TypeScript union and a Kotlin `Set<String>` —
  because neither language can import the other. `packages/android/src/contract.test.ts`
  reads both files and fails on drift, in both directions.
- Every port feature is checked once in `BridgePort.connect()`. A WebView missing
  any of them gets no bridge at all rather than a half-working one.
- Requests time out after 15s rather than hanging, because a promise that never
  settles is indistinguishable from a frozen app.

**Rejected alternative.** Keeping `addJavascriptInterface` and adding a callback
id parameter to each method. It works, but it grows the reflective surface in
proportion to the capability list, which is the thing worth avoiding.

---

## ADR-0014 — Back is decided by the renderer, with a host-side timeout

**Status.** Accepted (M4). Fixes a real defect introduced in M2.

**Context.** M2's back handler asked `webView.canGoBack()`. That question cannot
be answered correctly here: the renderer uses a **memory** router precisely so
that back does not navigate the WebView's document. The WebView's own history is
therefore always empty, `canGoBack()` is always false, and **back exited the app
from any screen, at any depth.**

What back should undo — an open dialog, the navigation drawer, the previous
route — is state that lives in the web app. The Activity cannot see it.

**Decision.** The Activity offers each press to the renderer over the bridge and
acts on the answer.

- The renderer runs a small dispatcher (`packages/android/src/back.ts`). Handlers
  are consulted innermost-first: dialog, then drawer, then route.
- The drawer handler reads upstream's real `layout.mobileSidebar` state, mounted
  through `AppInterface`'s existing `serverScoped` slot — an upstream extension
  point, not a fork.
- Route depth mirrors `createMemoryHistory`'s cursor, tracking **both** `set` and
  `go`, because the router's own `navigate(-1)` travels through `go`. Counting
  only pushes would drift, and back would then claim presses that do nothing.

**Consequences and the two obligations they create.**

1. **The user must always be able to leave.** If no answer arrives within
   `BackCoordinator.TIMEOUT_MS` (400 ms) the app exits anyway. A wedged web app
   must never be able to trap someone in it. A handler that throws is treated as
   declining, for the same reason.
2. **A late answer must not act on the wrong screen.** Each press carries a
   token; a reply whose token is not the outstanding one is discarded. The
   "nothing pending" sentinel is explicitly excluded from being a live token,
   because web content can send `back.handled` unprompted — a unit test caught
   that exact hole.

**Accepted cost.** Predictive back can no longer preview the app closing: the
callback must stay enabled to be offered the press at all, so the system cannot
know in advance that a press will exit. Correct navigation is worth more than
the preview animation.

**Rejected alternative.** Deciding in Kotlin from a mirror of the UI state pushed
across the bridge. That puts two copies of the same state in two languages and
makes every new dismissable surface a two-sided change.

---

## ADR-0015 — Preferences are not encrypted, and credentials will not live in them

**Status.** ~~Accepted (M4)~~ — **SUPERSEDED by ADR-0017 (M5).** The second half
held: credentials still do not get a plaintext home. The first half did not
survive contact with M5, which found that upstream persists a server password
through this exact store. Kept here because the reasoning is still worth reading
next to what replaced it.

**Context.** `Platform.storage` and the draft store are backed by named
`SharedPreferences` files plus content-addressed blobs in `filesDir`. Neither is
encrypted.

**Decision.** Leave them unencrypted, and keep provider credentials out of them.

**Reasoning.** Everything stored through this path today is UI state, layout
preferences, prompt drafts and the selected server URL. Encrypting it would buy
nothing measurable against the threat model (`docs/ARCHITECTURE.md` 2.8) while
adding a key-management failure mode that can lose a user's drafts.

The risk is not the current contents but the **precedent**: preferences are the
obvious place to put an API key later, and that would be the mistake. The class
comment on `PreferenceStore` says so at the point where someone would make it.

**Consequences.**

- Provider credentials get Keystore-backed storage of their own in M10. That is
  a separate mechanism, not a flag on this one.
- Store names arrive from web content, so they are sanitised to a safe filename
  (`[^A-Za-z0-9._-] → _`, prefixed `oc_`). Dots survive on purpose — real store
  names contain them (`default.dat`) — and it is the loss of separators that
  makes traversal impossible.
- Blob ids are validated against `[0-9a-f]{64}` before being joined to a path,
  because they come back through the bridge from web content.

---

## ADR-0016 — Mobile layout reuses upstream's own responsive path

**Status.** Accepted (M4).

**Context.** M4 asks for a credible phone application rather than a desktop page
in a WebView. The obvious reading is to build a mobile layout.

**Inspecting the code first changed the answer.** Upstream already has one:
`createMediaQuery("(max-width: 767px)")` breakpoints, a `layout.mobileSidebar`
off-canvas drawer with a backdrop, a `mobileTitlebarPosition` setting with a
bottom option, and a `hover-reveal` utility that already escapes hover-only
reveals via `@media (hover: none)`. `newLayoutDesignsDefault` is `true`, so that
path is live.

**Decision.** Use it. Do not write a second mobile layout.

Three things were needed to make it actually apply on Android:

| Need | What was done | Divergence |
|---|---|---|
| The breakpoint must fire | `width=device-width` in the Android `index.html` — without it a WebView reports ~980 CSS px and the mobile path never activates | none |
| One-handed reach | Default `mobileTitlebarPosition` to `"bottom"` on Android | D6, 3 lines |
| Hover-only controls | `@media (hover: none)` override in `packages/android/src/styles.css` for the sites that open-code the pattern instead of using upstream's `hover-reveal` | none |

**Consequences.**

- The mobile UX improves when upstream's does, with no merge cost.
- The hover override depends on upstream's Tailwind class names. If a rename
  silences it, the symptom is a control that stops appearing on touch. That is
  written down in the stylesheet next to the selectors.
- **Not verified on a device.** The shared UI cannot be built in this
  environment (see `docs/CURRENT_STATUS.md`), so these are reasoned from the
  code, not observed. D6 in particular turns on a layout upstream still gates
  behind a non-prod channel in its own settings UI. It changes a default only,
  and the user can flip it in Settings.

**Rejected alternative.** Mobile-specific Android components wrapping the shared
screens. It would have produced something demonstrable sooner and a permanent
second layout to maintain, against `.claude/rules/architecture.md` A1.

---

## ADR-0017 — The preference store is encrypted; ADR-0015 is superseded

**Status.** Accepted (M5). **Supersedes ADR-0015.**

**Why the earlier decision was reversed.** ADR-0015 left `Platform.storage`
unencrypted, on the stated grounds that nothing in it was secret — it held UI
state, layout preferences and a server URL — and that credentials would get
storage of their own in M10.

M5 falsified the premise rather than the reasoning. Upstream's `ServerProvider`
persists a whole `ServerConnection.Http` into its `server.v3` store, and that
object contains `http.password` (`packages/app/src/context/server.tsx`, `add()`).
That store *is* `Platform.storage`. So the moment the app can talk to a remote
server, a real credential is written through the path ADR-0015 said would never
carry one.

**Decision.** Encrypt every value in the preference-backed stores with AES-256-GCM
under a key held in the Android Keystore.

**Everything, rather than the values believed to be secret.** Classifying values
is a judgement that has to be re-made every time upstream persists something new,
and when it is made wrongly it fails silently — the value is simply in the clear
and nothing says so. Encrypting the store removes the judgement. It also covers
prompt drafts, which is a feature and not an accident: an unsent prompt is often
the most sensitive thing the app is holding.

**Consequences.**

- Key *names* stay in the clear. They are structural (`server.v3`, `settings.v3`)
  and leaving them readable keeps `storage.keys()` meaningful.
- A value that cannot be decrypted reads as **absent**, never as an error. That
  is what makes the M4→M5 upgrade survivable: existing installs hold plaintext,
  which fails to decrypt, and the app starts with defaults instead of crashing.
- A value that cannot be *encrypted* fails loudly. There is no plaintext
  fallback, because a fallback is how a credential ends up in the clear.
- Blob files (draft attachments in `filesDir`) are **not** encrypted. They are
  app-private, `allowBackup="false"` is set, and encrypting large binaries is a
  different performance question. Deferred to M10 with the rest of the storage
  hardening, and stated here so it is not mistaken for coverage.

**Rejected alternative: `EncryptedSharedPreferences`.** The obvious choice, and
it is deprecated — `androidx.security:security-crypto` ships it annotated
`@Deprecated` even in the stable 1.1.0, which was verified by decompiling the
artifact rather than assumed. Founding the credential path that M10 inherits on
an already-dead API is a liability, and the platform primitive it wrapped is
available directly at minSdk 26 with no dependency at all.

**Rejected alternative: a separate secure store for credentials only.** It keeps
ADR-0015 intact but requires stripping the password out of what upstream
persists and re-injecting it on read — real divergence in a hot path, and one
more thing to re-apply on every upstream bump.

---

## ADR-0018 — Cleartext HTTP is permitted in debug builds only

**Status.** Accepted (M5), but **its stated purpose was wrong** — corrected on a
device the same milestone. See ADR-0021.

The decision itself stands: debug builds permit cleartext, release does not, and
no TLS handling is weakened. What was wrong is the reason given for it. This was
introduced so a self-hosted LAN server could be reached over plain HTTP, and it
does not achieve that: the app's page is served from an `https://` origin, and
Chromium blocks plaintext requests from a secure context as **mixed content**
before any socket is opened. Android's network security config never gets a say.

The config still matters for loopback, which is what M7 uses, and the release
policy is still worth pinning. But it does not enable a LAN server, and this ADR
claimed it did.

**Context.** From M5 the app talks to an OpenCode server the user runs
themselves, typically on a laptop on the same LAN at something like
`http://192.168.1.20:4096`. The network security policy from M2 permits cleartext
to loopback and nothing else, so that connection is refused before it is made.

Android's network security config matches on **hostnames, not address ranges**,
so "permit cleartext to RFC1918 only" cannot be expressed, and the host is not
known until the user types it.

**Decision.** A build-type source set: `src/debug/res/xml/network_security_config.xml`
permits cleartext; `src/main/` continues to deny it everywhere except loopback.
A debug source set replaces the file wholesale, so the relaxation cannot reach a
release APK.

**This is not a weakening of TLS.** Certificate validation for `https://` URLs is
untouched — no custom trust anchors, no `debug-overrides`, no hostname verifier.
What changes is whether the plaintext scheme is permitted at all, and only in the
build that exists for testing. The M5 constraint was "do not weaken TLS handling
to make a test server work", and no TLS handling is weakened.

**Consequences.**

- `NetworkSecurityConfigTest` pins the shipping policy: base-config denies
  cleartext, the only permitted domains are `127.0.0.1` and `localhost`, and
  neither config installs a trust anchor.
- That test reads XML off disk, which Gradle cannot see as a dependency. The
  files are declared as test inputs in `build.gradle.kts`; without that the task
  stays `UP-TO-DATE` exactly when the policy changes. Verified by mutating the
  config and watching the test fail.
- M7's on-device server is unaffected either way — loopback is permitted in both.

---

## ADR-0019 — The Android WebView origin is added to the server's CORS allowlist

**Status.** Accepted (M5). Divergence **D7**.

**Context.** The app serves its UI from `https://appassets.androidplatform.net`
(ADR-0008). Every request it makes to a server carries an `Authorization` header,
which is not CORS-safelisted, so **every request is preflighted**. The server's
allowlist (`packages/server/src/cors.ts`) permits `http://localhost:`,
`http://127.0.0.1:`, `oc://renderer`, the Tauri origins and `*.opencode.ai` — not
ours. Every request from the app to a remote server is therefore refused.

`opencode serve` exposes **no `--cors` flag** (`packages/cli/src/commands/handlers/serve.ts`
calls `createRoutes(password)` with no options), so this cannot be worked around
by configuration.

**Decision.** Add the origin to the allowlist as a one-line divergence.

Upstream already special-cases Electron and Tauri for exactly this situation — a
shell hosting the app locally and talking to a server elsewhere — so this is a
mechanical addition in an established pattern, and upstreamable. The domain is
reserved by Android for `WebViewAssetLoader` and never resolves on the public
internet, so allowlisting it does not widen exposure to the web. The entry is an
exact match rather than a prefix, so a lookalike host cannot satisfy it.

**Consequence, and it is a real limitation.** The patch is in the **server**, so
M5 requires a server built from this repository. A user running a released
`opencode` binary has an unpatched allowlist and the app cannot reach it. This is
recorded as the headline caveat in `docs/CURRENT_STATUS.md` rather than left for
someone to discover on a phone.

**Rejected alternative: a native fetch proxy.** `PlatformBase.fetch` is a
sanctioned hook, and routing requests through Kotlin would bypass CORS entirely
and work against any stock server. It was rejected for M5 because it means
streaming SSE response bodies across the bridge with backpressure and abort
handling — a large, risky mechanism — and because it would defeat one of this
milestone's stated purposes, which is to find out whether streaming works *in the
WebView* (Q9). It stays available as a fallback if the upstream patch is not
accepted.

**Rejected alternative: serving the UI from an already-allowed origin.**
`http://localhost` has no port, so it does not match the allowlist's
`http://localhost:` prefix, and moving off an `https://` origin would cost the
secure-context APIs the shared UI relies on.

---

## ADR-0020 — The app has a first-run server setup screen

**Status.** Accepted (M5). Found by running on a device.

**Context.** The shared UI **cannot render without a server**.
`LayoutProvider`'s `init` reads `serverSdk().scope`, and with an empty server
list there is no server context to read: the app throws
`TypeError: Cannot read properties of undefined (reading 'scope')` before it
draws anything.

Upstream never meets this. Web is served *by* the server it talks to and passes
`servers={[server]}`; desktop always has a sidecar. **Android in remote mode is
the first case with genuinely no server until the user provides one**, and M5's
first attempt — a non-empty sentinel key with an empty server list — opened
`ServerProvider`'s gate only to crash immediately behind it.

**Decision.** `packages/android/src/setup.tsx` stands in front of `AppInterface`
until a server is configured, and `AppInterface` is rendered only once there is
one.

It is deliberately the smallest thing that can produce a working connection:
address, optional username, optional password. Upstream's own dialog manages
everything from the second server onwards, and this is not a place to grow a
second one. Desktop does the same thing with `DesktopFirstLaunchOnboarding`, so
the shape is upstream's own.

**Consequences.**

- The entry is **probed before it is saved** (`GET /global/health`), so a wrong
  address is reported while it can still be corrected rather than becoming a
  server that can never be reached. A 401 is reported as a credentials problem
  specifically, because that is the failure a user can actually act on.
- The scheme is checked separately and first, because a mixed-content block and
  an unreachable host are indistinguishable from `fetch` — both are an opaque
  `TypeError` — and the advice for them is completely different (ADR-0021).
- The probe is a reachability check at setup time, not a data path. Everything
  after it goes through upstream's `ServerConnection` and client factories, so
  `.claude/rules/architecture.md` A3 still holds.

---

## ADR-0021 — Remote servers must be HTTPS or loopback

**Status.** Accepted (M5). **Discovered on a device**; supersedes the reasoning
in ADR-0018.

**What happened.** With a real OpenCode server running on the LAN and the phone
able to reach it — `adb shell curl http://192.168.1.156:4096/global/health`
returned 200 — the app reported "Could not reach". The WebView console said why:

```
Mixed Content: The page at 'https://appassets.androidplatform.net/index.html'
was loaded over HTTPS, but requested an insecure resource
'http://192.168.1.156:4096/api/health'. This request has been blocked
```

**The constraint.** The app serves its UI from an `https://` origin (ADR-0008,
chosen so fonts, storage and fetch behave). A secure context may not issue
plaintext requests. This is a browser rule enforced before any network call, so
**no Android configuration affects it** — which is precisely what ADR-0018 got
wrong.

`http://127.0.0.1` and `http://localhost` are exempt: the specification treats
them as *potentially trustworthy* origins.

**Therefore:**

| Server | Works? |
|---|---|
| `https://…` (any host) | ✅ |
| `http://127.0.0.1:<port>` — **the M7 on-device server** | ✅ |
| `http://<LAN address>` | ❌ blocked, unfixably, from this origin |

**Consequences.**

- **M7 is unaffected.** The project's actual goal — a server on the device at
  `http://127.0.0.1:<port>` — sits in the exempt case. This finding is good news
  for the end state and bad news only for the M5 checkpoint.
- **M5's remote mode requires https, or a tunnel** that makes the server appear
  on the phone's loopback. The verification in this milestone used
  `adb reverse tcp:4096 tcp:4096`, which is exactly the M7 topology and is why
  the whole path could be exercised at all.
- `isReachableFromSecureContext` refuses a plain-http non-loopback address at
  the point of entry, with an explanation, rather than letting the user save a
  server that can never work.
- **Rejected: serving the UI from `http://`.** It would drop the secure context
  the shared UI relies on and contradicts ADR-0008 and `.claude/rules/android.md` N6.
- **Deferred: a native `platform.fetch`.** Routing requests through Kotlin would
  bypass both mixed content and CORS and make any stock server reachable. It is
  the escape hatch if remote-over-LAN turns out to matter, and it is a large
  mechanism (streaming bodies across the bridge) that the end goal does not need.

---

## ADR-0022 — The on-device runtime is a Node process, not Bun

**Status.** Accepted (M6), **proven end to end on hardware**. Resolves **Q8**.

**Context.** The project's goal needs the OpenCode server running on the phone.
Upstream's own runtime is Bun, which publishes no Android/Bionic build, so the
question was whether *any* viable runtime exists.

**What the spike measured** (full evidence in `docs/LOCAL_RUNTIME_SPIKE.md`):

- Upstream already has a Node build target, and it produces a bundle whose only
  native dependency is `@lydell/node-pty`. tree-sitter and the image library
  resolve to **WASM**, SQLite resolves to the **`node:sqlite` built-in**, and
  `@parcel/watcher` is not referenced at all. No `.node` binding appears
  anywhere in the output.
- That bundle **runs on plain Node** with the PTY import shimmed: server up in
  1.4 s, `/global/health` healthy.
- **Node 26.4.0 for Android aarch64 executes on the test device** — a OnePlus 15,
  Android 16, unrooted — and **the OpenCode server runs on it**: ready in 3.1 s,
  `/global/health` healthy, `/api/session` answering. That is the result the
  milestone existed to obtain, and it is a measurement rather than a plan.
- The app may execute a binary shipped as a jniLib, and **may not** execute one
  in `filesDir` — W^X measured, not assumed, by an instrumented test running as
  the app's own uid.

**Decision.** Run the OpenCode Node build in a **separate on-device Node
process**, started by the app the way `packages/desktop/src/main/sidecar.ts`
starts its own — which is the pattern ADR-0005 already committed to, with a
different binary. The runtime and its libraries ship in the APK as `lib*.so` and
are executed from `nativeLibraryDir`, because W^X leaves no alternative.

It also lands where M5 finished: `http://127.0.0.1:<port>` is the one origin
Chromium does not block from the app's `https://` page (ADR-0021), and upstream's
CORS allowlist already permits it.

**Consequences.**

- **Size.** 97.3 MB of runtime uncompressed (Node 49.7 MB + 10 libraries), of
  which `libicudata.so.78` alone is 33.1 MB, plus ~37 MB of app bundle. A Node
  built `--with-intl=small-icu` removes most of the ICU data, which is one reason
  to build Node rather than redistribute Termux's artifact.
- **A build step is unavoidable.** Android extracts only `*.so`, so libraries
  named `libicuuc.so.78` must be renamed and their `DT_NEEDED`/`SONAME` patched.
- **`useLegacyPackaging = true`** is required, or `nativeLibraryDir` is empty and
  there is nothing to execute.
- **Terminals are lost until PTY is solved.** No Android arm64 build of
  `@lydell/node-pty` exists, and the bundle imports it *statically* — so the
  server cannot even load without something at that specifier. M8's problem, now
  precisely defined.

**Rejected.** A bundled Linux rootfs under proot (hundreds of megabytes, syscall
emulation, and a hidden Termux, against the spirit of `.claude/rules/android.md`
N3); `nodejs-mobile` (Node 18, below the `node:sqlite` floor); and any JS engine
without Node APIs, since the bundle needs `child_process`, `fs`, `dgram`, `dns`
and more.

**The obvious alternative was tested and does not work.** `bun-linux-aarch64-musl`
looked like it might sidestep everything, since musl builds are commonly static
and a static binary needs no system libc. It is not static: its `PT_INTERP` is
`/lib/ld-musl-aarch64.so.1`, a loader Android does not ship, and the phone
refuses it with the ENOENT-on-exec that a missing interpreter produces. Bundling
a musl loader to run a runtime never built for Bionic is a far worse bet than a
Node build that already works.

**One environment consequence.** Reusing someone else's build brings its compiled-in
paths: OpenSSL's config, `child_process`'s default shell, and `HOME` all point
into Termux's prefix and must be overridden (`docs/LOCAL_RUNTIME_SPIKE.md` §3a).
In the app these become `filesDir`-relative, and they are a further argument for
compiling Node rather than redistributing an artifact.

---

## ADR-0023 — How the runtime is packaged and started

**Status.** Accepted (M7), running on a device.

**Context.** ADR-0022 chose an on-device Node process. This records the packaging
and lifecycle decisions that followed, each forced by something measured.

**Decisions.**

**The runtime ships in the APK, not downloaded.** W^X (measured in M6) forbids
executing a file the app can write, so the binary is a `lib*.so` in `jniLibs` and
runs from `nativeLibraryDir`. `useLegacyPackaging = true` is required, or the
libraries are mapped from the APK and that directory is empty.

**Versioned libraries are renamed and their references patched.** Android extracts
only `lib*.so`, so `libicuuc.so.78` becomes `libicuuc78.so` and every `DT_NEEDED`
and `DT_SONAME` pointing at it is rewritten (`scripts/runtime/elfpatch.py`). Every
rename is shorter than the original, so the strings are patched in place - and the
patcher refuses rather than guesses when another string points into the bytes it
would overwrite.

**The server bundle lives in assets, not jniLibs.** It is data that Node reads,
not code the app executes, so W^X does not apply. It is copied to `filesDir` on
first launch and re-copied only when the APK changes, keyed on a digest of the
asset listing plus the version name - a 37 MB copy on every start would be a
visible delay for nothing.

**The runtime is prepared by a script, not committed.** 135 MB of third-party
build output does not belong in git. `scripts/runtime/prepare-android-runtime.py`
assembles it, and a build without it is legitimate: the Gradle `reportRuntime`
task says which kind of APK is being produced, `runtime.await` fails honestly, and
the UI falls back to asking for a remote server. Failing the build would break CI
and anyone cloning the repository, to no benefit.

**Everything the runtime needs is set explicitly.** `LD_LIBRARY_PATH` for the
renamed libraries, `OPENCODE_SERVER_PASSWORD`/`USERNAME` for auth, `HOME`,
`TMPDIR` and the `XDG_*` directories inside app-private storage, and `SHELL`
pointed at Android's. Each of these was a separate failure during M6 and each is
an artifact of reusing a build compiled for a different prefix.

**Loopback and auth, always.** The server binds `127.0.0.1` with a password
generated per launch, held in memory, never persisted - the desktop sidecar's rule
(ADR-0006). Other apps share loopback, so the password is not optional. Verified
on the device: an unauthenticated request gets **401**.

**Port is left to upstream.** `--port=0` means "you choose", and upstream tries
4096 then falls back to any free port. Predictable, but survives a collision.

**Consequences.**

- **Runtime work runs under a supervisor.** A failed start propagating out of
  `async` would cancel `lifecycleScope` and take the bridge and the UI with it. A
  unit test caught this before a device could.
- **A start failure is not cached.** A transient failure must not require an app
  restart before the runtime will try again.
- **Start is idempotent and race-safe.** The renderer asks on load and again on
  reload; a second start would orphan a process holding the port and a second copy
  of the database.
- **The UI cannot tell local from remote.** The handle becomes a `sidecar`-shaped
  `ServerConnection`, exactly as desktop builds for its own server, so the client
  factories and the whole request path are unchanged (A3).
- **Terminals remain unavailable.** The PTY shim satisfies the bundle's static
  import and throws if a terminal is actually opened. M8.
- **This is not yet lifecycle-safe.** The runtime is owned by the Activity, so
  Android may kill it when the app is backgrounded. A foreground service is M9,
  and until then a long agent turn is not protected.

---

## ADR-0024 — Projects are app-private directories; SAF moves files, it is not the workspace

**Status.** Accepted (M8).

**The constraint.** The Storage Access Framework hands back `content://` URIs.
The OpenCode server is a Node process, and **Node cannot open one**. A folder the
user picks through SAF is therefore unusable as a working directory: not
inconvenient, unusable. Anything the runtime works in has to be a real POSIX path.

**Decision.** Projects live in app-private storage - `filesDir/projects/<slug>` -
and SAF is used for *movement*: importing an existing folder in, and later
exporting changes back out. The working copy is always a real directory.

`platform.openDirectoryPickerDialog` therefore returns a **path**, not a URI: the
host opens the picker, copies the tree into a project, and hands back the
project's path. Upstream's "add project" flow gets what it expects and nothing
downstream has to know Android was involved.

**This is also the answer to the permissions question.** No
`MANAGE_EXTERNAL_STORAGE`, no `READ_EXTERNAL_STORAGE`, nothing broad at all - the
user grants access to one tree, at the moment they import it. `.claude/rules/android.md`
N4 asked for a written justification before requesting a broad permission; none is
requested, so none is needed.

**Consequences.**

- **Import is a copy, not a mount.** Edits happen to the app's copy. Getting them
  back out is an explicit export, which is a real limitation and a deliberate one:
  the alternative is a permission this project has said it will not ask for.
- **Import is bounded** - 20,000 files, 512 MB total, 32 MB per file, and
  `node_modules`/`.git`/`build` and similar are skipped outright. A phone is not a
  workstation, and an accidental import of a photo library should stop rather than
  grind. What was skipped is **reported**, never silently dropped: an agent
  reasoning about a tree that is quietly missing files is worse than one told the
  tree is incomplete.
- **Display names are separate from directory names.** The name the user typed is
  kept in `.opencode-name`; the directory is a slug. Names come from people and
  from imported folders, so slugging is a boundary - a name containing separators
  or `..` must not be able to place a project outside the store, which is tested
  directly and end to end.

---

## ADR-0025 — Git is bundled; terminals are not

**Status.** Accepted (M8). Git verified on a device.

**Git.** OpenCode shells out to a real `git` binary
(`ChildProcess.make("git", …)` throughout `packages/core/src/git.ts`), so Git
support means shipping one. It is bundled exactly as Node is: `lib*.so` in
jniLibs, versioned libraries renamed and references patched, run from
`nativeLibraryDir`.

Only **two** of git's 181 helpers ship. 146 of them are hardlinks to the same
`git` binary, which already contains every builtin - status, diff, branch, commit,
add, log - and the rest are perl and shell scripts for workflows a phone will not
run. `git-remote-http` is the one real addition, and it is also `git-remote-https`.
Git costs about 8 MB on top of Node.

**Helpers are reached through symlinks.** Android extracts only `lib*.so`, and git
looks for `git-remote-https` by that exact name. The app creates a directory of
symlinks in app-private storage pointing back into `nativeLibraryDir`, and puts it
on `PATH` and `GIT_EXEC_PATH`. Executing through such a symlink is permitted - the
kernel checks the target, which lives in an exec-permitted directory - and that was
verified on a device before anything was built on it.

**Two more compiled-in prefixes had to be overridden**, the same class of problem
as OpenSSL's config in M6: `GIT_CONFIG_NOSYSTEM` and `GIT_ATTR_NOSYSTEM`, because
this build looks for both under Termux's prefix and warns on every command
otherwise. And git refuses to commit without an identity, which Android has no
passwd entry to supply - so a default `~/.gitconfig` is written **once, only when
absent**, so anything the user sets later stands.

**Terminals are not bundled, and shell commands do not need them.** The bash tool
uses `ChildProcess`, not a PTY - measured, not assumed - so running shell commands
works today. `@lydell/node-pty` is needed only by the interactive terminal panel,
has no Android arm64 build, and building it requires the NDK plus Node headers for
this exact version. The PTY shim satisfies the bundle's static import and throws
if a terminal is actually opened, which is honest rather than silent.

**Deferred rather than attempted** because the cost is a cross-compiled native
module tied to a Node version this project does not yet build itself. It should be
revisited alongside ADR-0022's open item - compiling Node with the NDK - since the
two share the whole toolchain.

---

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
| ~~Q8~~ | ~~`EmbeddedProcessRuntime` vs `EmbeddedInProcessRuntime`~~ | **Resolved — ADR-0022: a separate Node process from `lib*.so`** |
| ~~Q9~~ | ~~SSE over `fetch` in Android WebView~~ | **Resolved — yes, verified on a device in M5** |

---

## ADR-0026 — The runtime is owned by the process, and a dead one is restarted

**Status:** accepted (M9)

### Context

The runtime was created by `SessionActivity`. Android destroys and recreates an
Activity for reasons the user never sees — a locale change, a theme change,
"don't keep activities" — and every `LocalRuntimeController` mints its **own**
per-launch password. A second controller therefore holds a credential the running
server does not accept, and the first runtime's watchdog dies with the Activity's
scope, leaving a server nobody is watching.

Measured on a OnePlus 15 (Android 16), recreation did **not** in fact produce two
servers: the renderer's handle survived, so nothing asked again. That is luck, not
design — the invariant held because of which of two things happened first.

### Decision

1. `OpenCodeApplication` owns the runtime. One per process, from first use to
   process end. The Activity asks for an address; it does not own the thing that
   provides one.
2. A cached start is reusable only while it is still running or while the process
   it produced is still alive (`RuntimeState.alive`). A completed start describes
   a process that *was* alive, not one that is.
3. When the host reports `failed`, the renderer asks for the runtime again.
   `degraded` — alive but not answering — is reported and left alone, because
   restarting a server that is merely busy turns a pause into a lost session.
4. The runtime process shuts down on **stdin EOF**. Android usually kills the
   process group with the app, but "usually" is not a guarantee across OEMs, and
   an orphan holds a port and owns a database nobody can talk to.

### Consequences

- `EmbeddedProcessRuntime` must never close `process.outputStream`. Closing it
  would look like tidying up and would kill the runtime.
- If the server is ever moved to its own `android:process`, `OpenCodeApplication`
  runs there too and must not start a second runtime.

### Why point 2 is stated so precisely

It was a real defect, found by running it rather than by reading it. `kill -9` on
the runtime: the watchdog noticed, the renderer asked to restart, and the
controller handed back the completed start — the address of the process that had
just died — without starting anything. The UI then sat pointing at nothing.
`LocalRuntimeControllerTest.aRuntimeThatDiedIsRestartedRatherThanHandedBackDead`
fails if the predicate is loosened again.

---

## ADR-0027 — The foreground service runs only while a turn is in flight

**Status:** accepted (M9)

### Context

A long agent turn must survive backgrounding, which on modern Android means a
foreground service (`.claude/rules/android.md` N7). But N9 is equally binding:
holding a service — and the process it keeps alive — through idle time is a
defect, not caution.

### Decision

`RuntimeService` is started when the server reports a session mid-turn and stopped
when it does not. The signal is the server's own `/session/status`, not the UI:
the server is the authority on whether work is in flight, it needs no coupling to
upstream's internals, and there is no second signal to keep in sync. The check
rides on the health poll that already runs every five seconds, so it adds no
wakeups.

`START_NOT_STICKY`: if Android kills the process mid-turn the turn is already
lost, and restarting a service with no UI and no work would only burn battery.

An unreadable or unexpected `/session/status` response reads as **not busy** —
holding the process awake because a request failed is the wrong way to be wrong.

### Consequences

Backgrounding while idle holds nothing; measured on the device as **0**
`RuntimeService` instances in `dumpsys activity services`. A runtime that dies
mid-turn clears `busy` before it reports `failed`, or the service would be held
for the life of the app.

---

## ADR-0028 — The app document is served with a Content-Security-Policy

**Status:** accepted (M10)

### Context

The bridge is reachable by any script running at the app origin, and it can read
the Keystore-backed store, the clipboard and draft blobs. The UI renders model
output, file contents and diffs — text the user did not write and the model does
not control either. One injection at the app origin is therefore not a defaced
page; it is credential disclosure.

Upstream ships no CSP: the web build is served from a static host with a
`_headers` file that sets content types only.

### Decision

`WebViewHost`'s asset handler attaches a CSP response header to the document.
Not a `<meta>` tag: a meta tag is content, and content is what an injection
controls.

Inline script hashes are computed from the packaged `index.html` at runtime.
Upstream's theme preload must run before the bundle to avoid a flash of the wrong
colour scheme, so it cannot move to a file; naming it by hash permits exactly
that script and no other. Deriving the hash from the asset means an upstream
change cannot silently produce a policy that blocks the app's own code.

Two directives are deliberately weaker than the rest, and are recorded as
residual risks in `docs/SECURITY.md` §6 rather than hidden:

- `style-src 'unsafe-inline'` — the preload injects a `<style>` whose content
  varies with the theme, so it cannot be hashed. Injected CSS cannot reach the
  bridge.
- `connect-src https:` — remote-server mode (ADR-0021) points the UI at a host
  the user types, unknown at build time. This should become `'self'` plus
  loopback when remote mode retires.

`img-src` deliberately excludes remote hosts: a remote image URL in model output
is the cheapest exfiltration channel there is, and the cost of refusing it is a
broken image rather than a broken app.

### Consequences

Verified on the device by attempting the attacks rather than reading the header:
an injected inline script did not execute, and both `base-uri` takeover and
remote-image exfiltration were refused. The app renders and the runtime reaches
`ready` with the policy in force.

---

## ADR-0029 — The extracted runtime is keyed to the installed package

**Status:** accepted (M10)

### Context

`RuntimeAssets` skips re-extraction when a marker matches. The marker was a
digest of the asset *listing* plus the version *name*. File contents are not in a
listing, and `versionName` is a constant during development.

So a changed bundle whose filenames had not changed was never re-extracted. The
device kept executing old JavaScript while reporting the new version — found by
comparing the device against the APK, which showed a launcher two milestones old.

### Decision

The marker includes the installed package's `lastUpdateTime` and version code,
both set by the package manager on every install and upgrade, including a debug
reinstall of an identical version.

Digesting the asset *contents* would state the property more directly, but it
means reading 37 MB before the server can start, on every launch, to answer a
question the package manager has already answered.

If the package cannot be read the fallback is *unique* rather than stable, so the
failure mode is an unnecessary copy rather than a silently stale runtime.

### Consequences

This is a security property, not a caching detail: a fix shipped inside the
bundle would otherwise silently not apply. `RuntimeAssetsTest` pins it, including
the exact case that failed — same version name, new install.

It also invalidated an M9 measurement. The stdin-EOF watchdog added in M9 was
never staged into the APK, so it had never run on the device; the M9 orphan
result was Android's process-group kill. See `docs/LIFECYCLE.md` §4.

---

## ADR-0030 — Notification tags are claimed, not trusted

**Status:** accepted (M10)

### Context

`MainActivity` is exported, because it is the launcher. It read
`EXTRA_TAG` from its Intent and relayed it to the renderer as a
`notification.clicked` event, which fires whatever callback the UI registered for
that tag. Any app on the device could send that extra.

### Decision

`Notifications` remembers the tags this process actually posted, bounded to the
most recent 64, in memory only. `MainActivity` relays a tag only if it can claim
it, and claiming consumes it.

### Consequences

The impact was low — an attacker must guess a tag and can only trigger the app's
own callback — but the fix costs nothing and removes the question. Consuming on
use also means a replayed Intent is not a second tap, which was a real bug
independent of the security framing.

