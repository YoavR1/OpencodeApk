# Architecture

Two parts: **what upstream actually is** (verified by reading the code) and **what
we are building on top of it** (the target design).

Every upstream claim below was verified against `anomalyco/opencode` at commit
`4e81a0b` on 2026-08-18. Paths and line numbers are from that commit. Re-verify
after an upstream bump — see `docs/UPSTREAM_SYNC.md`.

---

# Part 1 — Upstream architecture (VERIFIED)

## 1.1 Repository shape

- Bun workspace monorepo. `packageManager: "bun@1.3.14"`.
- Workspaces: `packages/*`, `packages/console/*`, `packages/stats/*`,
  `packages/sdk/js`, `packages/slack`.
- Build orchestration: **Turborepo** (`turbo.json`); `typecheck` runs via
  `bun turbo typecheck`.
- Lint: **oxlint**. Typecheck: `tsgo` (`@typescript/native-preview`) per package.
- Infrastructure: SST (`sst.config.ts`) — not relevant to Android.

### The root `test` trap

```json
"test": "echo 'do not run tests from root' && exit 1"
```

Deliberate. Never invoke it. Tests are per-package.

## 1.2 Packages that matter to us

| Package | Role | Android relevance |
|---|---|---|
| `packages/core` | Agent engine, providers, DB, tools | **Must run on device** (M7) |
| `packages/server` | HTTP API handlers, auth, CORS | **Must run on device** |
| `packages/opencode` | Server assembly + `Server.listen()` + CLI entry | **Must run on device** |
| `packages/app` | Shared SolidJS application | **Reused as the Android UI** |
| `packages/session-ui` | Session/chat UI components (SolidJS) | Reused via `app` |
| `packages/ui` | Design-system components (SolidJS) | Reused via `app` |
| `packages/client` | Generated typed API client | Reused |
| `packages/sdk/js` | Published JS SDK (`@opencode-ai/sdk`) | Reused |
| `packages/protocol`, `packages/schema` | Effect-based schema/protocol | Reused |
| `packages/desktop` | **Electron** shell | **Reference only. Never runs on Android.** |
| `packages/cli` | Terminal CLI + `serve` command | Reference for server startup |
| `packages/tui` | OpenTUI terminal UI | Not used |

## 1.3 The server

`packages/opencode/src/server/server.ts:73`

```ts
export async function listen(opts: ListenOptions): Promise<Listener>

type ListenOptions = CorsOptions & {
  port: number
  hostname: string
  mdns?: boolean
  mdnsDomain?: string
}

export type Listener = { hostname: string; port: number; url: URL; stop(close?): Promise<void> }
```

Key properties:

- Built on `effect` + `@effect/platform-node`'s `NodeHttpServer`, over
  **`node:http`'s `createServer`**. This is a *Node-compatible* HTTP stack, not a
  Bun-only one — materially important for M6/M7.
- CORS origins are **caller-supplied**. `packages/server/src/cors.ts:13-16` also
  allows `http://localhost:*`, `http://127.0.0.1:*`, and the three `tauri://`
  origin forms by default.
- Auth is HTTP Basic, driven by `OPENCODE_SERVER_USERNAME` /
  `OPENCODE_SERVER_PASSWORD` (`packages/server/src/middleware/authorization.ts`).
- `packages/cli/src/commands/commands.ts:46` — the `serve` command's `hostname`
  flag **defaults to `127.0.0.1`**. Loopback-by-default is already upstream policy.

### A Node build target already exists

`packages/opencode/script/build-node.ts`:

```ts
await Bun.build({
  target: "node",
  entrypoints: ["./src/node.ts"],
  outdir: "./dist/node",
  format: "esm",
  external: ["jsonc-parser", "@lydell/node-pty"],
  ...
})
```

and `packages/opencode/src/node.ts` exports exactly the surface a host needs:

```ts
export { Config }    from "@/config/config"
export { Server }    from "./server/server"
export { bootstrap } from "./cli/bootstrap"
export { Database }  from "@opencode-ai/core/database/database"
```

**This is the single most important finding for the Android end goal.** Upstream
already produces a Node-targeted, ESM server bundle with native modules marked
external. It is the natural payload for an on-device runtime.

### Native / runtime-specific dependencies (the M6 problem set)

| Dependency | Kind | Note |
|---|---|---|
| `@lydell/node-pty` | Native (N-API) | Terminal support; already `external` in the node build |
| `bun-pty` | Bun-native | Bun-only PTY path |
| `@parcel/watcher` | Native (N-API) | File watching; needs an Android ARM64 fallback |
| `tree-sitter-bash`, `tree-sitter-powershell` | Native | Syntax parsing |
| `web-tree-sitter` | **WASM** | Portable fallback — good sign |
| `@effect/sql-sqlite-bun` | Bun-only | Bun SQLite driver |
| `@opencode-ai/effect-sqlite-node` | Node | **A Node SQLite path already exists in-tree** |

Both a Bun and a Node SQLite adapter exist as first-class workspace packages
(`packages/effect-sqlite-node`, and `@effect/sql-sqlite-bun` in `packages/core`).
That the Node path is maintained is what makes a Node-based Android runtime
plausible. **This is a hypothesis for M6 to test, not a settled conclusion.**

## 1.4 The shared app and its two extension points

### Extension point 1 — the `Platform` boundary

`packages/app/src/context/platform.tsx`

```ts
type PlatformName = "web" | "desktop"                    // ~line 20

type PlatformBase = {
  version?: string
  openExternal(url: string): void                        // required
  restart(): Promise<void>                               // required
  notify(title, description?, onClick?): Promise<void>   // required
  storage?(name?: string): SyncStorage | AsyncStorage
  draftStore?: DraftStore
  fetch?: typeof fetch
  getDefaultServer?(): Promise<ServerConnection.Key | null>
  setDefaultServer?(url: ServerConnection.Key | null): Promise<void> | void
  ... (~25 more, all optional; several desktop-only)
}

export type Platform = PlatformBase & (                  // ~line 126
  | { platform: "web"; os?: never }
  | { platform: "desktop"; os?: DesktopOS; openDirectoryPickerDialog(...) }
)
```

**Only three methods are required**: `openExternal`, `restart`, `notify`.
Everything else is optional, and shared UI guards each with a capability check.

### Extension point 2 — the `ServerConnection` abstraction

`packages/app/src/context/server.tsx:181`

```ts
export namespace ServerConnection {
  export type HttpBase = { url: string; username?: string; password?: string }
  export type Http    = { type: "http"; http: HttpBase; authToken?: boolean } & Base
  export type Sidecar = { type: "sidecar"; http: HttpBase }
                      & ({ variant: "base" } | { variant: "wsl"; distro: string }) & Base
  export type Ssh     = { type: "ssh"; host: string; http: HttpBase } & Base
  export type Any = Http | (Sidecar | Ssh)

  export const builtin = (conn) => conn.type === "sidecar" && conn.variant === "base"
  export const local   = (conn?) => !!conn && (builtin(conn)
                       || (conn.type === "http" && isLocalHost(conn.http.url) === "local"))
}
```

`ServerConnection.local()` (line 241) is what shared UI uses to decide whether a
server is "local". A `{ type: "sidecar", variant: "base" }` connection is local
**by definition** — so Android's on-device server gets correct treatment with no
upstream change.

Clients are built in `packages/app/src/utils/server.ts` — `createSdkForServer`,
`createApiForServer` — which attach `Authorization: Basic base64(username:password)`.

## 1.5 How the desktop actually boots (VERIFIED, M1)

The full chain, which is the blueprint for Android:

```
electron.vite.config.ts
  main    : src/main/index.ts + src/main/sidecar.ts
            plugin maps  virtual:opencode-server  ->  ../opencode/dist/node/node.js
            plugin copies *.wasm from dist/node into out/main/chunks/
  preload : src/preload/index.ts  -> contextBridge  window.api  (58 methods)
  renderer: root src/renderer, plugins [ @opencode-ai/app/vite ]
            publicDir ../../../app/public
```

**The desktop main process loads the Node build output** (`dist/node/node.js`) —
upstream already runs the very artifact ADR-0007 proposes for Android. It also
copies `.wasm` files alongside it, confirming WASM assets are part of that build.

`src/main/index.ts` (~line 365):

```ts
const port = <bind :0 on 127.0.0.1, read address.port, close>
const hostname = "127.0.0.1"
const url = `http://${hostname}:${port}`
const password = randomUUID()
const { listener, health } = await spawnLocalServer(hostname, port, password, {
  userDataPath: app.getPath("userData"), onStdout, onStderr, onExit,
})
Deferred.succeed(serverReady, { url, username: "opencode", password })
await health.wait  // 30s timeout
```

`src/main/server.ts` — `utilityProcess.fork(sidecar.js)`, then
`postMessage({ type: "start", hostname, port, password, userDataPath })`.

`src/main/sidecar.ts` — sets env, then:

```ts
Object.assign(process.env, {
  OPENCODE_SERVER_USERNAME: "opencode",
  OPENCODE_SERVER_PASSWORD: password,
  XDG_STATE_HOME: process.env.XDG_STATE_HOME ?? userDataPath,
})
const { Server } = await import("virtual:opencode-server")
listener = await Server.listen({ port, hostname, username: "opencode", password,
                                cors: ["oc://renderer"] })
```

Health check (`server.ts:186`): `GET /api/health`, falling back to
`/global/health`, with `Authorization: Basic base64("opencode:"+password)`,
polled every 100ms.

The ready payload crossing into the renderer is exactly:

```ts
type ServerReadyData = { url: string; username: string | null; password: string | null }
```

### The renderer composition

`src/renderer/index.tsx` (452 lines) is a thin shell over `@opencode-ai/app`:

```tsx
<PlatformProvider value={platform}>
  <AppBaseProviders locale={...} onNativeTranslations={...}>
    <AppInterface defaultServer={key} servers={servers()} router={router}
                  startup={...} serverScoped={...}>
      <Inner />
    </AppInterface>
  </AppBaseProviders>
</PlatformProvider>
```

and it turns `ServerReadyData` into a connection:

```ts
{ displayName: t("desktop.server.local"), type: "sidecar", variant: "base",
  http: { url: data.url, username: data.username, password: data.password } }
```

**Android reproduces this file's shape almost exactly.** That is the whole job of
the Android renderer package.

### The renderer origin

`src/main/windows.ts:35` registers a privileged custom scheme:

```ts
protocol.registerSchemesAsPrivileged([{ scheme: rendererProtocol,
  privileges: { secure: true, standard: true, supportFetchAPI: true, stream: true } }])
```

served by `protocol.handle` with path-traversal rejection, and `webPreferences`
of `{ contextIsolation: true, nodeIntegration: false, sandbox: true }`.

`secure + standard + supportFetchAPI + stream` is precisely what
`WebViewAssetLoader` provides on Android over `https://`. This is the direct
justification for ADR-0008.

## 1.6 Electron leakage into shared UI — VERIFIED near-zero

Measured across `packages/app/src`, `packages/session-ui/src`, `packages/ui/src`:

| Probe | Hits |
|---|---|
| `window.api`, `from "electron"`, `ipcRenderer` | **1** (plus 1 unrelated test string) |
| `from "node:*"` | **0** |

The single hit is `packages/app/src/app.tsx:404`:

```ts
void window.api?.setTitlebar?.({ mode, scheme })
```

Fully optional-chained — a silent no-op when `window.api` is absent. **No Electron
API is reachable from the shared app.** The `Platform` object is the entire
surface. This is the strongest single piece of evidence that the port is viable
with near-zero divergence.

## 1.7 Platform-name branching — the real divergence driver

28 sites branch on `platform.platform`, **all inside `packages/app/src`** (none in
`session-ui` or `ui`). Representative:

```
components/titlebar.tsx:78-81      macos / windows / linux / web
components/session/open-in-app.tsx:172   === "desktop" && !!platform.openPath && server.isLocal()
components/help-button.tsx:22      === "desktop" && os === "windows"
app.tsx:332                        === "desktop" && platform.exportDebugLogs
context/settings.tsx:315           !== "web"
utils/persist.ts:532,533,547,579   storage scoping
```

Most are `=== "desktop" && <capability check>`, which degrade correctly for an
unknown platform. **The exception that matters** is `utils/persist.ts`:

```ts
const isDesktop = platform.platform === "desktop" && !!platform.storage   // :547, :579
```

If Android reported `platform: "web"`, its native `storage` implementation would
be **silently ignored** and persistence would fall back to `localStorage`. This is
why Android must be a real platform name rather than masquerading as `"web"`, and
why the fix in `persist.ts` should be a *capability* check (`!!platform.storage`)
rather than a platform-name check — a change that is also an upstream improvement.

## 1.8 Streaming transports — VERIFIED

Two, and only two:

| Transport | Where | Auth | Android WebView notes |
|---|---|---|---|
| **SSE** (`text/event-stream`) | `packages/client/src/generated/client.ts:196` — consumed via `fetch` + `response.body.getReader()`, **not** `EventSource` | Normal request headers, so `Authorization: Basic` works | Requires `fetch` streaming (`ReadableStream` response bodies), available in modern Chromium WebView. **Must be verified on a real device in M5.** |
| **WebSocket** | `packages/app/src/components/terminal.tsx:620`; server `packages/server/src/handlers/pty.ts:165` via `ctx.request.upgrade` | Cannot send headers from browser JS, so upstream passes a **ticket** plus `username`/`password`/`authToken` in the URL (`terminalWebSocketURL`), matched by `hasPtyConnectTicketURL` in `middleware/authorization.ts` | Terminal only. Not required before M8. |

Because SSE is fetch-based rather than `EventSource`-based, Basic auth on the
event stream works without any upstream change. That is a significant piece of
luck for this port.

## 1.9 CORS

`packages/server/src/cors.ts` allows by default: `http://localhost:*`,
`http://127.0.0.1:*`, `oc://renderer`, the three `tauri://` origin forms, and
`*.opencode.ai`. Anything else must arrive through `opts.cors` — which
`Server.listen()` accepts. The Android WebView origin will be passed that way.

## 1.10 Publication status of shared packages — VERIFIED, decisive

Queried against the npm registry:

| Package | Registry status |
|---|---|
| `@opencode-ai/app` | **NOT PUBLISHED** |
| `@opencode-ai/session-ui` | **NOT PUBLISHED** |
| `@opencode-ai/ui` | published, `1.18.18` |
| `@opencode-ai/sdk` | published, `1.18.18` |
| `@opencode-ai/client` | published, but `0.0.0` (placeholder) |

Additionally, `packages/app/package.json` exports **raw TypeScript**
(`"." : "./src/index.ts"`) and its build depends on `@opencode-ai/app/vite`, which
pulls in `vite-plugin-solid` and `@tailwindcss/vite`.

**Consequence:** the shared application cannot be consumed from npm. Any Android
UI must be built *inside the upstream Bun workspace*, exactly as `packages/desktop`
is. This single fact eliminates one ADR-0002 option outright and reshapes the rest.

## 1.11 Historical note: the desktop was previously Tauri

`packages/desktop/src/main/migrate.ts` migrates Tauri-era stores into the Electron
store; `script/raw-changelog.ts:137` still maps `packages/desktop/src-tauri/`; a
`tauri-linux` container image remains in `packages/containers`. The `tauri://`
CORS entries are residue of that era.

Upstream evaluated Tauri for desktop and moved to Electron. The reasons are likely
desktop-specific (updater, signing, WSL, tray) and are **not** direct evidence
about Android — but it does mean upstream carries no Tauri Android investment we
could reuse.

---

# Part 2 — Target Android architecture (DECIDED IN M1)

Status: **designed and decided; not yet implemented.** Implementation starts at M2.

## 2.1 Shell decision — native Android WebView

**Decided: a native Android application (Kotlin) hosting the shared UI in a
`WebView` served by `androidx.webkit.WebViewAssetLoader`.** Rationale and rejected
alternatives are in ADR-0009.

## 2.2 Module and package layout — decided

```
/                                   repo root == upstream workspace root (after M2 vendoring)
│
├── CLAUDE.md  docs/  prompts/  scripts/  .claude/       our control layer (no upstream collision)
├── .github/workflows/android-ci.yml                     ours; upstream's 23 workflows untouched
│
├── packages/                                            upstream, unmodified except platform.tsx
│   ├── app/  session-ui/  ui/  client/  sdk/  core/  server/  opencode/  desktop/ …
│   │
│   └── android/                          NEW  @opencode-ai/android   (renderer package)
│       ├── package.json                       inside the packages/* glob -> no root edit needed
│       ├── vite.config.ts                     uses @opencode-ai/app/vite, mirrors desktop
│       ├── index.html
│       └── src/
│           ├── index.tsx                      Android renderer entry (mirrors desktop renderer)
│           ├── platform.ts                    Android `Platform` implementation
│           └── bridge.ts                      typed JS half of the native bridge
│
└── apps/                                 NEW  (deliberately outside the bun workspace globs)
    └── android/                               Gradle project
        ├── settings.gradle.kts
        ├── gradle/libs.versions.toml
        ├── gradlew  gradle/wrapper/
        └── app/src/
            ├── main/AndroidManifest.xml
            ├── main/res/xml/network_security_config.xml
            ├── main/kotlin/ai/opencode/android/
            │   ├── MainActivity.kt
            │   ├── web/         WebViewHost, AssetLoader wiring, BridgePort
            │   ├── runtime/     OpencodeRuntime interface + implementations + Service
            │   └── security/    Keystore-backed credential store
            ├── main/assets/web/                vite output, generated, git-ignored
            ├── test/                           JVM unit tests
            └── androidTest/                    instrumented tests
```

Two deliberate choices:

- **`packages/android/`** — upstream's `workspaces.packages` already contains the
  glob `packages/*`, so a package placed there joins the Bun workspace with
  **zero edits to upstream's root `package.json`**, and resolves `@opencode-ai/app`,
  the shared catalog, patches, and overrides exactly as `packages/desktop` does.
- **`apps/android/`** — `apps/*` matches **no** upstream workspace glob, so Bun
  ignores the Gradle project entirely. Keeping Gradle out of the JS workspace
  avoids `bun install` trying to interpret it.

## 2.3 Layering

```
┌──────────────────────────────────────────────────────────────┐
│ apps/android  (Kotlin)                                       │
│   MainActivity ── WebViewHost ── WebViewAssetLoader          │
│   OpencodeService (foreground, android:process=":opencode")  │
│   OpencodeRuntime (interface)  ── credential store (Keystore)│
└───────────────────────────┬──────────────────────────────────┘
                            │ BridgePort: typed messages over WebMessagePort
┌───────────────────────────┴──────────────────────────────────┐
│ packages/android  (TypeScript / SolidJS)                     │
│   bridge.ts  ── platform.ts  (implements upstream `Platform`)│
│   index.tsx  ── PlatformProvider / AppBaseProviders /        │
│                 AppInterface                                 │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────┴──────────────────────────────────┐
│ packages/app · session-ui · ui   (upstream, unmodified)      │
└───────────────────────────┬──────────────────────────────────┘
                            │ ServerConnection + generated SDK (Basic auth)
┌───────────────────────────┴──────────────────────────────────┐
│ OpenCode server — remote (M5) or on-device 127.0.0.1 (M7)    │
└──────────────────────────────────────────────────────────────┘
```

## 2.4 The native bridge — `BridgePort`

The desktop exposes 58 methods on `window.api`. Android needs far fewer, because
most are desktop-only. The bridge is a single typed request/response channel over
`WebMessagePort` (preferred over `addJavascriptInterface`, which exposes a
reflective surface).

```ts
// packages/android/src/bridge.ts
type BridgeRequest =
  | { id: number; method: "runtime.await";        params?: never }
  | { id: number; method: "runtime.restart";      params?: never }
  | { id: number; method: "store.get";            params: { name: string; key: string } }
  | { id: number; method: "store.set";            params: { name: string; key: string; value: string } }
  | { id: number; method: "store.delete";         params: { name: string; key: string } }
  | { id: number; method: "store.clear";          params: { name: string } }
  | { id: number; method: "store.keys";           params: { name: string } }
  | { id: number; method: "notify";               params: { title: string; body?: string; tag: string } }
  | { id: number; method: "openExternal";         params: { url: string } }
  | { id: number; method: "pickDirectory";        params: { multiple?: boolean } }
  | { id: number; method: "defaultServer.get";    params?: never }
  | { id: number; method: "defaultServer.set";    params: { url: string | null } }

type BridgeResponse =
  | { id: number; ok: true;  result: unknown }
  | { id: number; ok: false; error: { code: string; message: string } }

type BridgeEvent =
  | { event: "notification.clicked"; tag: string }
  | { event: "runtime.state";        state: "starting" | "ready" | "failed"; message?: string }
```

`runtime.await` returns upstream's exact `ServerReadyData` shape:
`{ url, username, password }`. Every inbound message is validated on the Kotlin
side against this contract; anything unrecognised is rejected, never coerced.

## 2.5 The local-runtime boundary — defined without choosing the runtime

This is the seam that keeps M5 and M7 on one code path, and keeps M6 free to pick
any implementation.

```kotlin
// apps/android/app/src/main/kotlin/ai/opencode/android/runtime/OpencodeRuntime.kt

data class RuntimeConfig(
    val hostname: String = "127.0.0.1",
    val port: Int = 0,                     // 0 = ephemeral; resolved during start
    val username: String = "opencode",
    val password: String,                  // per-launch, generated, never persisted
    val stateDir: File,                    // becomes XDG_STATE_HOME
    val corsOrigins: List<String>,         // the WebView asset origin
)

/** Mirrors upstream `ServerReadyData` exactly. */
data class RuntimeHandle(val url: String, val username: String?, val password: String?)

sealed interface RuntimeState {
    data object Stopped : RuntimeState
    data object Starting : RuntimeState
    data class Ready(val handle: RuntimeHandle) : RuntimeState
    data class Failed(val cause: Throwable) : RuntimeState
}

interface OpencodeRuntime {
    val state: StateFlow<RuntimeState>
    suspend fun start(config: RuntimeConfig): RuntimeHandle
    suspend fun stop()
}
```

Health checking is shared by all implementations and copied from desktop
(`packages/desktop/src/main/server.ts:186`): poll `GET /api/health`, falling back
to `/global/health`, with `Authorization: Basic base64("opencode:" + password)`,
every 100 ms, with a bounded timeout.

Three implementations are anticipated, and **the UI cannot tell them apart**:

| Implementation | Milestone | What it does |
|---|---|---|
| `RemoteRuntime` | M5 | Starts nothing. Returns a user-configured `RuntimeHandle`. The connection is built as `ServerConnection.Http`. |
| `EmbeddedProcessRuntime` | M7 candidate | `ProcessBuilder` on a runtime binary shipped as `lib*.so`, closest analogue to Electron's `utilityProcess.fork`. |
| `EmbeddedInProcessRuntime` | M7 candidate | Runtime loaded via JNI inside a separate Android process (`android:process=":opencode"`), giving process isolation through Android rather than through `exec`. |

M6 chooses between the two embedded variants **on evidence**. Both satisfy the
same interface, so M7 integration work does not depend on which wins.

### Hard Android constraint discovered in M1: W^X

Since **Android 10 (API 29)**, an app targeting API 29+ **cannot `exec()` a file
located in its writable data directory** — this is a W^X (write-xor-execute)
enforcement. Downloading or unpacking a runtime at first launch and executing it
is therefore **not viable**.

The supported approach is to ship the executable inside the APK as a native
library — named `lib<something>.so` under `jniLibs/<abi>/` — and execute it from
`context.applicationInfo.nativeLibraryDir`, which is read-only and exec-permitted.
Never hard-code that path; always read `nativeLibraryDir`.

Consequences that bind M6 and M7:

1. The runtime **must be bundled in the APK**, which settles Q4 in favour of
   bundling and makes APK size a first-class measurement in M6.
2. `android:extractNativeLibs` and page-alignment interact with this; M6 must
   record what it actually needed.
3. `EmbeddedInProcessRuntime` (JNI) sidesteps `exec` entirely and is not subject
   to this restriction — a genuine argument in its favour that M6 must weigh
   against the isolation benefits of a real child process.

## 2.6 Connection modes — one abstraction, three fillings

| Mode | Milestone | `ServerConnection` value | `ServerConnection.local()` |
|---|---|---|---|
| Remote | M5 | `{ type: "http", http: { url, username, password } }` | false (unless the URL is loopback) |
| On-device | M7 | `{ type: "sidecar", variant: "base", http: { url: "http://127.0.0.1:<port>", username: "opencode", password } }` | **true**, via `builtin()` |

Switching modes changes a value produced by `RuntimeHandle`. It changes no code path.

## 2.7 WebView configuration

- Assets served by `WebViewAssetLoader` on `https://appassets.androidplatform.net/`
  (or a project-specific domain), giving a secure, standard, fetch- and
  stream-capable origin — the Android equivalent of the desktop's privileged
  `oc://renderer` scheme.
- That exact origin is passed to `Server.listen({ cors: [...] })`.
- `javaScriptEnabled = true`; `allowFileAccessFromFileURLs = false`;
  `allowUniversalAccessFromFileURLs = false`; `allowFileAccess = false`.
- Cleartext permitted **only** to `127.0.0.1`, via
  `res/xml/network_security_config.xml`. Never `usesCleartextTraffic="true"`.
- SSE arrives over `fetch` + `ReadableStream`; M5 must verify this end to end on a
  real device, since it is the one transport assumption the whole UI depends on.

## 2.8 Security posture

Unchanged from M0 and now grounded in the verified desktop behaviour: loopback
bind, per-launch `randomUUID()`-equivalent password held in memory only, Basic
auth enforced even on loopback (other Android apps can reach loopback ports),
Keystore-backed provider credentials, minimal validated bridge surface, SAF for
project folders, no root, no broad storage permission.

---

# Part 3 — Platform capability matrix

Every member of upstream's `PlatformBase` / `Platform`, its desktop implementation,
and the Android disposition. Derived from `packages/app/src/context/platform.tsx`
and `packages/desktop/src/renderer/index.tsx`.

**Disposition key**
- **REUSE** — works with no Android-specific code; the web/default path is correct.
- **ADAPT** — needs an Android implementation behind the bridge.
- **OMIT** — desktop-only; leave undefined. Shared UI already guards it.
- **DEFER** — adapt later; named milestone.

| Member | Req? | Desktop implementation | Android | Milestone | Notes |
|---|---|---|---|---|---|
| `platform` | ✔ | `"desktop"` | **ADAPT** → `"android"` | M3 | Requires widening `PlatformName` + the `Platform` union. The only mandatory upstream edit. |
| `os` | – | `macos`/`windows`/`linux` from UA | **OMIT** | — | Only meaningful on the `"desktop"` arm. |
| `version` | – | `pkg.version` | **ADAPT** | M3 | From `BuildConfig.VERSION_NAME`. |
| `openExternal` | **✔** | `window.api.openExternal` | **ADAPT** | M3 | `Intent.ACTION_VIEW`. Validate scheme — upstream web allows only `http:`/`https:`/`mailto:`. |
| `restart` | **✔** | kill sidecar, then `relaunch()` | **ADAPT** | M3 | Stop runtime, recreate Activity. |
| `notify` | **✔** | Web `Notification` + focus checks | **ADAPT** | M4 | `NotificationManagerCompat`; tap → `notification.clicked` event. Needs `POST_NOTIFICATIONS` on API 33+. |
| `storage` | – | `window.api.store*` (electron-store) | **ADAPT** | M4 | `AsyncStorage` over the bridge. **Blocked by the `persist.ts` platform check — see below.** |
| `draftStore` | – | `createDraftStore({...window.api.draft*})` | **ADAPT** | M4 | `createDraftStore` is exported from `@opencode-ai/app`; supply get/set/remove/putBlob/getBlob. |
| `fetch` | – | passthrough to global `fetch` | **REUSE** | — | WebView `fetch` is fine; omit to use the default. |
| `getDefaultServer` / `setDefaultServer` | – | `window.api.*DefaultServerUrl` | **ADAPT** | M4 | Persist selected server across launches. |
| `windowID` | – | `window.api.getWindowID()` | **OMIT** | — | Android is single-window. `persist.ts` falls back to `"browser"`. |
| `openDirectoryPickerDialog` | ✔ on desktop arm | native dialog | **ADAPT** | M4 | SAF `ACTION_OPEN_DOCUMENT_TREE` with persisted permissions. Not on the `"android"` arm's required set — decide in M3 whether to mirror desktop's requirement. |
| `openAttachmentPickerDialog` | – | native file picker + token-scoped reads | **DEFER** | M8 | SAF `ACTION_OPEN_DOCUMENT`. |
| `getPathForFile` | – | `WeakMap` + `window.api` | **OMIT** | — | No real filesystem paths under SAF. |
| `saveFilePickerDialog` | – | native save dialog | **DEFER** | M8 | SAF `ACTION_CREATE_DOCUMENT`. |
| `openPath` | – | `window.api.openPath` | **OMIT** | — | Guarded by `!!platform.openPath` at 3 call sites. |
| `openLocalFile` | – | `window.api.openLocalFile` | **OMIT** | — | |
| `revealPath` | – | `window.api.revealPath` | **OMIT** | — | No file manager contract on Android. |
| `checkAppExists` | – | `window.api.checkAppExists` | **OMIT** | — | "Open in editor" is desktop-only. |
| `readClipboardImage` | – | `window.api.readClipboardImage` | **DEFER** | M4 | WebView paste may cover this; measure before building it. |
| `updater` | – | electron-updater | **OMIT** | M11 | Android updates are a distribution concern, not an in-app one. |
| `wslServers` | – | Windows only | **OMIT** | — | |
| `getDisplayBackend` / `setDisplayBackend` | – | Linux Wayland/X11 | **OMIT** | — | |
| `webviewZoom`, `getPinchZoomEnabled`, `setPinchZoomEnabled` | – | Electron zoom | **OMIT** | — | Android WebView handles pinch natively. |
| `windowFullscreen` | – | Electron window state | **OMIT** | — | |
| `runDesktopMenuAction` | – | app menu | **OMIT** | — | No menu bar on Android. |
| `setForceFocus` | – | devtools focus | **OMIT** | — | Debug affordance. |
| `exportDebugLogs` | – | `window.api.exportDebugLogs` | **DEFER** | M9 | Valuable for phone-only debugging — worth adding once logging exists. |
| `recordFatalRendererError` | – | writes to desktop logs | **DEFER** | M9 | Same reason. |

## Summary

| Disposition | Count |
|---|---|
| ADAPT (real Android work) | 10 |
| OMIT (desktop-only, guarded upstream) | 14 |
| DEFER (later milestone) | 5 |
| REUSE (no work) | 1 |

Ten adapters, of which **three are required** by the type (`openExternal`,
`restart`, `notify`) and the rest are capability-gated. M3 needs only
`platform`, `version`, and the three required methods to boot the UI.

## The one shared-UI change worth making

`packages/app/src/utils/persist.ts:547` and `:579`:

```ts
const isDesktop = platform.platform === "desktop" && !!platform.storage
```

The `platform.platform === "desktop"` half makes an otherwise perfectly good
capability check platform-specific. With Android reporting `"android"`, the
Android `storage` adapter would be ignored and persistence would silently fall
back to `localStorage` inside the WebView — losable on cache clear.

Preferred fix (M4), which is also an upstream improvement:

```ts
const isDesktop = !!platform.storage        // capability, not identity
```

Logged as divergence D3 in `docs/UPSTREAM_SYNC.md` and worth proposing upstream.
