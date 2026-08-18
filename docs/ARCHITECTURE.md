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
  openExternal(url: string): void
  restart(): Promise<void>
  notify(title: string, description?: string, onClick?: () => void): Promise<void>
  storage?(name?: string): SyncStorage | AsyncStorage
  draftStore?: DraftStore
  fetch?: typeof fetch
  getDefaultServer?(): Promise<ServerConnection.Key | null>
  setDefaultServer?(url: ServerConnection.Key | null): Promise<void> | void
  openPath?(...): Promise<void>          // desktop only
  revealPath?(...): Promise<boolean>     // desktop only
  updater?: UpdaterPlatform              // desktop only
  wslServers?: WslServersPlatform        // desktop only
  ... (many more, all optional)
}

export type Platform = PlatformBase & (                  // ~line 126
  | { platform: "web"; os?: never }
  | { platform: "desktop"; os?: DesktopOS; openDirectoryPickerDialog(...) }
)
```

Provided via `PlatformProvider`, consumed via `usePlatform()`. Exported publicly
from `packages/app/src/index.ts`.

The design is already correct for us: **most capabilities are optional**, so a new
platform implements what it can. `packages/app/src/entry.tsx` constructs the
`"web"` platform — a worked example of a minimal implementation.

**The one required upstream change** is widening `PlatformName` and the `Platform`
union to admit `"android"`. That is a small, mechanical, upstreamable edit. It is
the first entry in the divergence ledger.

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

  export const key = (conn: Any): Key => ...
  export const builtin = (conn: Any) => conn.type === "sidecar" && conn.variant === "base"
}
```

Clients are built from it in `packages/app/src/utils/server.ts`:

```ts
createSdkForServer({ server: ServerConnection.HttpBase, ... })  // -> @opencode-ai/sdk client
createApiForServer({ server, fetch? })                          // -> @opencode-ai/client
// both attach: Authorization: Basic base64(username:password)
```

**This is the "one connection abstraction" the charter requires, and it already
exists upstream.** Local and remote are the same type with different contents.

## 1.5 The desktop sidecar pattern (the model to port)

`packages/desktop/src/main/sidecar.ts` runs the server in a separate process:

```ts
const { Server } = await import("virtual:opencode-server")
listener = await Server.listen({
  port: command.port,
  hostname: command.hostname,
  username: "opencode",
  password: command.password,
  cors: ["oc://renderer"],
})
```

with environment prepared beforehand:

```ts
Object.assign(process.env, {
  OPENCODE_SERVER_USERNAME: "opencode",
  OPENCODE_SERVER_PASSWORD: password,
  XDG_STATE_HOME: process.env.XDG_STATE_HOME ?? userDataPath,
})
```

The renderer runs on the custom origin `oc://renderer`, which is why that origin
is the CORS allowlist entry. It talks to the loopback server with Basic auth.

**Generalised pattern:**

1. Host generates a per-launch password.
2. Host starts the server out-of-process on loopback.
3. Host passes the webview's origin as the CORS allowlist.
4. Webview client connects with Basic auth to the returned URL.
5. Host stops the process on shutdown.

Android reproduces this with a Service and a WebView. **Nothing of Electron
itself is ported.**

---

# Part 2 — Target Android architecture (TO BUILD)

Status: **design, not yet implemented.** Details are settled per milestone and
recorded in `docs/DECISIONS.md`.

## 2.1 Layering

```
┌────────────────────────────────────────────────────────────┐
│ Android app  (Kotlin)                                      │
│   MainActivity · WebView host                              │
│   OpencodeService (foreground service)  ── owns runtime    │
│   SecureCredentialStore (Keystore)                         │
│   SafDocumentBridge (project folders)                      │
└──────────────────────────┬─────────────────────────────────┘
                           │  narrow typed bridge (WebMessagePort)
┌──────────────────────────┴─────────────────────────────────┐
│ Android Platform adapter  (TypeScript)                     │
│   implements upstream `Platform` with platform: "android"  │
└──────────────────────────┬─────────────────────────────────┘
                           │
┌──────────────────────────┴─────────────────────────────────┐
│ Shared OpenCode UI  (unmodified upstream)                  │
│   @opencode-ai/app · session-ui · ui   (SolidJS)           │
└──────────────────────────┬─────────────────────────────────┘
                           │  ServerConnection + generated SDK (Basic auth)
┌──────────────────────────┴─────────────────────────────────┐
│ OpenCode server                                            │
│   M5:  remote, over the network      (type: "http")        │
│   M7:  on-device, 127.0.0.1          (sidecar-shaped)      │
└────────────────────────────────────────────────────────────┘
```

Dependencies point downward only. Shared UI never imports Android code.

## 2.2 Connection modes — one abstraction, two fillings

| Mode | Milestone | `ServerConnection` value |
|---|---|---|
| Remote (checkpoint) | M5 | `{ type: "http", http: { url: "https://…", username, password } }` |
| Local on-device (goal) | M7 | sidecar-shaped: `{ http: { url: "http://127.0.0.1:<port>", username: "opencode", password: <per-launch> } }` |

Switching modes changes a value. It must not change a code path.

## 2.3 WebView and origin

- Shared UI is built (`vite build`, `packages/app`) and shipped in APK assets.
- Served through `WebViewAssetLoader` on an `https://` app origin rather than
  `file://`, so fetch/CORS/storage behave normally.
- That exact origin is passed as the `cors` option to `Server.listen()`.
- Cleartext to `127.0.0.1` is permitted narrowly via a network security config —
  never globally.

## 2.4 On-device runtime — open question, decided in M6

The end goal requires the OpenCode server on the device. Candidate approaches,
**all unproven until M6 produces evidence**:

| # | Approach | Basis | Main risk |
|---|---|---|---|
| A | Node-compatible runtime embedded in the APK, running `dist/node` | `build-node.ts` already targets Node; `effect-sqlite-node` exists | Android ARM64 Node builds; native module gaps |
| B | Bun embedded as an ARM64 Android binary | Upstream is Bun-first | Bun does not target Android officially |
| C | Port the server to a JS engine already on Android | No extra binary | Node/Bun API surface in `core` is large |
| D | JVM/native reimplementation of the server | Native | Violates "reuse upstream"; enormous divergence |

Current leaning is **A**, on the strength of the existing Node build target and
the Node SQLite adapter. This is a **hypothesis**. M6 exists to test it, and to
enumerate what breaks: PTY (`@lydell/node-pty`), file watching
(`@parcel/watcher`), and native tree-sitter grammars — with `web-tree-sitter`
(WASM) as the known-portable fallback for the last.

M6 must produce a written feasibility report with artifacts before M7 begins.

## 2.5 Lifecycle model

- The server runs inside a **foreground service** so an in-flight agent turn is
  not killed when the app is backgrounded.
- The Activity/WebView is treated as disposable: it can be destroyed and recreated
  at any time and must reconnect and rehydrate from the server.
- Server state lives in app-private storage and survives process death; a restarted
  server reattaches to the same data directory.
- Configuration changes must not restart the server.

## 2.6 Security posture

- Loopback binding by default; auth on even on loopback.
- Per-launch server password, in memory, never persisted, never logged.
- Provider credentials in Keystore-backed storage.
- The JS bridge surface is minimal, typed, and validates every input.
- No root. No broad storage permission. Project access via SAF.
