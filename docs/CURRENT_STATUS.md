# Current Status

> **This file is the authoritative state of the project.** Every session reads it
> first and updates it before finishing. If it disagrees with the repository, the
> repository is right — fix this file.

| | |
|---|---|
| **Last updated** | 2026-08-18 |
| **Session** | M1 architecture audit |
| **Branch** | `claude/opencode-android-bootstrap-o58939` |
| **Current milestone** | **M1 — complete** |
| **Next milestone** | **M2 — first Android APK shell** |
| **Next prompt** | **`prompts/02_ANDROID_SHELL.md`** |

---

## Where the project actually is

Control layer plus a **decided architecture**. There is still no application code,
no Android project, and no upstream code in this repository — M1 was a decision
milestone, not an implementation one.

Every architectural question that blocked M2 is now resolved and recorded.

## What this session did

Read the upstream source in depth (not filenames) and converted the reading into
five accepted decisions: the integration mechanism, the Android shell, the project
layout, the toolchain, and the local-runtime boundary.

---

## M0 CI — VERIFIED

The M0 workflow ran and behaved exactly as designed
(run [32117081451](https://github.com/YoavR1/OpencodeApk/actions/runs/32117081451)):

```
detect phase                    completed  success
repo hygiene                    completed  success
android build (pre-M2 phase)    completed  success
android build                   completed  skipped
opencode workspace checks       completed  skipped
```

The phase-state design works: green, with a summary stating nothing was built.

---

## M1 findings — VERIFIED by reading upstream at `4e81a0b`

### 1. The shared UI has almost no Electron coupling

Measured across `packages/app/src`, `packages/session-ui/src`, `packages/ui/src`:

| Probe | Hits |
|---|---|
| `window.api` / `from "electron"` / `ipcRenderer` | **1** |
| `from "node:*"` | **0** |

The single hit is `packages/app/src/app.tsx:404` —
`void window.api?.setTitlebar?.({ mode, scheme })` — fully optional-chained and a
silent no-op on Android. **The `Platform` object is the entire Electron surface.**

### 2. The shared UI cannot be consumed from npm — this decided ADR-0002

| Package | Registry |
|---|---|
| `@opencode-ai/app` | **NOT PUBLISHED** |
| `@opencode-ai/session-ui` | **NOT PUBLISHED** |
| `@opencode-ai/ui` | published `1.18.18` |
| `@opencode-ai/sdk` | published `1.18.18` |
| `@opencode-ai/client` | published `0.0.0` (placeholder) |

`packages/app` also exports raw TypeScript and builds via `@opencode-ai/app/vite`.
The Android UI **must** be built inside the upstream Bun workspace.

### 3. Divergence is measurably small

All 28 `platform.platform` branch sites were audited; all are in `packages/app`,
none in `session-ui` or `ui`.

| # | File | Edits | Milestone |
|---|---|---|---|
| D1–D2 | `packages/app/src/context/platform.tsx` | 2 — widen `PlatformName`, add union arm | M3 |
| D3 | `packages/app/src/utils/persist.ts` | 2 — identity check → capability check | M4 |
| D4–D5 | `README.md`, `.gitignore` | trivial root merges | M2 |

**2 upstream source files, 3 edits.** Everything else Android needs is additive.

D3 is not cosmetic: `platform.platform === "desktop" && !!platform.storage` at
`persist.ts:547,579` would silently ignore Android's native storage and fall back
to WebView `localStorage`.

### 4. The desktop boot chain is a directly portable blueprint

```
bind :0 on 127.0.0.1 → read port → close
password = randomUUID()
utilityProcess.fork(sidecar.js) → postMessage {start, hostname, port, password, userDataPath}
  sidecar: env OPENCODE_SERVER_USERNAME/PASSWORD/XDG_STATE_HOME
           Server.listen({ port, hostname, username, password, cors: ["oc://renderer"] })
poll GET /api/health (fallback /global/health) with Basic auth, 100ms, 30s timeout
→ renderer receives ServerReadyData { url, username, password }
→ ServerConnection { type: "sidecar", variant: "base", http: {...} }
```

`ServerConnection.local()` (`context/server.tsx:241`) is **true by definition** for
`sidecar`/`variant:"base"`, so Android's on-device server is treated as local with
no upstream change.

### 5. Two streaming transports, and the important one is lucky

| Transport | Mechanism | Auth |
|---|---|---|
| **SSE** | `fetch` + `response.body.getReader()` — **not** `EventSource` (`client.ts:196`) | normal headers, so `Authorization: Basic` works |
| **WebSocket** | terminal/PTY only (`terminal.tsx:620`) | URL-borne ticket, since browsers cannot set WS headers |

Because SSE is fetch-based, Basic auth on the event stream needs no upstream
change. **Unverified on a device** — Q9, must be proven in M5.

### 6. Upstream already runs the artifact we want for Android

`packages/desktop/electron.vite.config.ts` maps `virtual:opencode-server` to
`../opencode/dist/node/node.js` and copies its `.wasm` files. The desktop main
process **already loads the Node build output** that ADR-0007 proposes embedding
on Android. That strengthens the hypothesis without proving it.

### 7. A hard Android constraint that reshapes M6/M7 — W^X

Since **Android 10 (API 29)**, an app targeting API 29+ **cannot `exec()` a file in
its writable data directory**. Downloading or unpacking a runtime at first launch
and executing it is **not viable**. An exec-based runtime must ship inside the APK
as `lib<name>.so` under `jniLibs/<abi>/` and run from
`context.applicationInfo.nativeLibraryDir`.

This settles Q4 (assets and runtime are **bundled**, not downloaded), makes APK
size a first-class M6 measurement, and creates a real argument for a JNI
in-process runtime, which avoids `exec` entirely (Q8).

### 8. The desktop was previously Tauri

`packages/desktop/src/main/migrate.ts` migrates Tauri-era stores; `src-tauri/`
survives in `script/raw-changelog.ts`; the `tauri://` CORS entries are residue.
Upstream moved to Electron. No Tauri Android investment exists upstream to reuse.

---

## Decisions made this session

| ADR | Decision | Status |
|---|---|---|
| 0002 | **Vendor upstream history into this repository**; Android code is additive | **Accepted** (was Provisional) |
| 0007 | Node-targeted runtime leading candidate — **plus the W^X constraint** | Provisional, decided M6 |
| 0009 | **Android shell: native Kotlin + WebView**; Tauri, Capacitor, CEF rejected | Accepted |
| 0010 | **`packages/android/`** (renderer) + **`apps/android/`** (Gradle) | Accepted |
| 0011 | JDK 21, `compileSdk` 36, `minSdk` 26, `arm64-v8a` | Accepted |

Q1–Q4 are resolved. Q5–Q9 remain open, each assigned to a milestone.

## Documentation updated

- `docs/ARCHITECTURE.md` — rewritten. Part 1 now carries the verified boot chain,
  Electron-leakage measurement, branch-site audit, transports, CORS, and
  publication status. Part 2 is the decided Android design with concrete modules,
  the `BridgePort` message contract, and the `OpencodeRuntime` boundary. Part 3 is
  the new **platform capability matrix**.
- `docs/DECISIONS.md` — ADR-0002 resolved; ADR-0009/0010/0011 added; ADR-0007
  extended with W^X; open questions refreshed.
- `docs/UPSTREAM_SYNC.md` — divergence ledger measured (D1–D5); nine new
  upstream-fact dependencies (U13–U21).

---

## Files expected to change in M2 and M3

### M2 — first Android APK shell

**First task: the vendoring merge** (ADR-0002).

```
git remote add upstream https://github.com/anomalyco/opencode
git fetch upstream dev
git merge upstream/dev --allow-unrelated-histories
```

| Path | Action |
|---|---|
| `packages/**`, `script/`, `sdks/`, `infra/`, `bun.lock`, `turbo.json`, root `package.json`, upstream `.github/workflows/*` | **added by merge**, unmodified |
| `README.md` | **conflict** — keep ours (D4) |
| `.gitignore` | **conflict** — upstream's plus our Android/secrets section (D5) |
| `apps/android/settings.gradle.kts`, `gradle/libs.versions.toml`, `gradlew`, `gradle/wrapper/**` | new |
| `apps/android/app/build.gradle.kts` | new |
| `apps/android/app/src/main/AndroidManifest.xml` | new |
| `apps/android/app/src/main/res/xml/network_security_config.xml` | new — cleartext to `127.0.0.1` only |
| `apps/android/app/src/main/kotlin/ai/opencode/android/MainActivity.kt` | new |
| `apps/android/app/src/main/kotlin/ai/opencode/android/web/WebViewHost.kt` | new — `WebViewAssetLoader` wiring |
| `apps/android/app/src/main/assets/web/index.html` | new — placeholder, replaced in M3 |
| `apps/android/app/src/test/**`, `apps/android/app/src/androidTest/**` | new — one test each |
| `docs/CURRENT_STATUS.md`, `docs/TEST_MATRIX.md` | updated |

CI flips automatically: `detect` finds `gradlew`, the `android` job runs, and
`android build (pre-M2 phase)` stops running.

### M3 — shared OpenCode UI

| Path | Action |
|---|---|
| `packages/app/src/context/platform.tsx` | **modify** — D1, D2 |
| `packages/android/package.json` | new — `@opencode-ai/android` |
| `packages/android/vite.config.ts` | new — uses `@opencode-ai/app/vite` |
| `packages/android/index.html` | new |
| `packages/android/src/index.tsx` | new — mirrors `packages/desktop/src/renderer/index.tsx` |
| `packages/android/src/platform.ts` | new — `platform`, `version`, `openExternal`, `restart`, `notify` |
| `packages/android/src/bridge.ts` | new — typed `BridgePort` client |
| `apps/android/app/build.gradle.kts` | modify — Gradle task copying vite output into assets |
| `apps/android/.../web/BridgePort.kt` | new — Kotlin half of the bridge |
| `docs/UPSTREAM_SYNC.md` | modify — move D1/D2 to **Applied** |

Upstream's root `package.json` is **not** touched: `packages/android/` is already
covered by the `packages/*` glob.

---

## Not run — BLOCKED, with reasons

| Check | Why not |
|---|---|
| `bun install`, `bun run lint`, `bun run typecheck` | No `package.json` here yet; upstream is vendored at the start of M2. |
| Building `packages/app` to static assets | Same. This is M2/M3 work and the first real test of ADR-0002. |
| `./gradlew assembleDebug` | No Gradle project, and no Android SDK in a cloud session. |
| Any device or emulator run | No device; host is `x86_64`, target is `arm64-v8a`. |

## Assumptions not yet verified — ASSUMED

1. `packages/app` builds to relocatable static assets under a non-root base path.
   **First thing M3 must prove.**
2. SSE over `fetch` + `ReadableStream` works reliably in Android WebView (Q9).
3. A Node-compatible runtime can host `dist/node` on Android ARM64 (ADR-0007) —
   still a hypothesis for M6, now additionally constrained by W^X.
4. D1–D3 are the complete divergence set. Confident for M3; M4's storage work is
   the next place this could grow.

## What is NOT done

- **No Android implementation.** No Gradle project, no Kotlin, no APK.
- **No upstream code vendored.** ADR-0002 is decided; the merge is M2's first task.
- **No tests exist.** `docs/TEST_MATRIX.md` is still a plan.
- **Nothing has run on a phone.**
- **No compile-only proof was built.** The M1 decisions rest on source reading and
  two external facts (W^X, Tauri Android status), not on a built artifact. The
  first real validation of ADR-0002 and ADR-0010 is the M2 vendoring merge plus
  the M3 asset build.

## Recommended next session

Paste **`prompts/02_ANDROID_SHELL.md`**.

Its first task is the vendoring merge, which is also the first genuine test of
ADR-0002. If the merge or the subsequent `bun install` behaves unexpectedly, that
is important information — record it and reassess rather than working around it.
