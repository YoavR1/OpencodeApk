# Rule: Architecture

Binding constraints on how OpencodeApk is structured. Violating one of these is a
design change and requires an ADR entry in `docs/DECISIONS.md`.

---

## A1. Upstream is a dependency, not a starting point to edit

`anomalyco/opencode` is consumed. It is not forked-and-diverged.

- **Reuse** an upstream package before writing an equivalent.
- If you must change an upstream file, the change should be **small, mechanical,
  and upstreamable in principle** (e.g. widening a union type), and it must be
  recorded in `docs/UPSTREAM_SYNC.md` with the file path and the reason.
- Copying an upstream source file into this repo to modify it is the last resort,
  not the first. It permanently forks that file.

## A2. The platform boundary already exists — use it

Upstream defines the boundary at `packages/app/src/context/platform.tsx`:

```ts
type PlatformName = "web" | "desktop"          // line ~20

export type Platform = PlatformBase & (        // line ~126
  | { platform: "web"; os?: never }
  | { platform: "desktop"; os?: DesktopOS; openDirectoryPickerDialog(...): ... }
)
```

`PlatformBase` is a wide surface of **optional** capability methods
(`openExternal`, `restart`, `notify`, `storage`, `draftStore`, `getDefaultServer`,
`setDefaultServer`, `fetch`, …). Optionality is the extension mechanism: an
Android platform implements what Android can do and omits the rest.

**Rule:** all Android-specific behaviour is supplied as a `Platform`
implementation. Shared UI must never branch on "am I on Android" outside this
boundary. If shared UI needs an Android-only capability, add an *optional* method
to `PlatformBase` rather than an inline platform check.

## A3. One connection abstraction

Upstream defines it at `packages/app/src/context/server.tsx` (~line 181):

```ts
export namespace ServerConnection {
  export type HttpBase = { url: string; username?: string; password?: string }
  export type Http    = { type: "http";    http: HttpBase; authToken?: boolean } & Base
  export type Sidecar = { type: "sidecar"; http: HttpBase } & ({ variant: "base" } | { variant: "wsl"; distro: string }) & Base
  export type Ssh     = { type: "ssh"; host: string; http: HttpBase } & Base
  export type Any = Http | (Sidecar | Ssh)
}
```

Both project modes map onto this **single** type:

| Mode | Maps to |
|---|---|
| Local on-device server (the end goal) | a `sidecar`-shaped connection |
| Remote server (M5 checkpoint only) | `type: "http"` |

Clients are constructed from it by `packages/app/src/utils/server.ts`
(`createSdkForServer`, `createApiForServer`), which attaches HTTP Basic auth from
`username`/`password`.

**Rule:** do not introduce a second client, a second base-URL resolver, or an
Android-only fetch path. Everything goes through `ServerConnection`.

## A4. The desktop sidecar is the reference design — the runtime is not

`packages/desktop/src/main/sidecar.ts` is the pattern to port:

1. Generate a per-launch password.
2. Start the server in a **separate process** on `127.0.0.1` with a chosen port.
3. Pass `{ username: "opencode", password, cors: [<renderer origin>] }` to
   `Server.listen()`.
4. Point the webview client at the resulting URL with Basic auth.
5. Stop the process on shutdown.

Android reproduces steps 1–5 with an Android process/service and a `WebView`.
It does **not** reproduce Electron.

## A5. Never Electron on Android

`packages/desktop` is out of scope as a runtime. Do not add Android targets to
`electron-builder`. Do not attempt to run `electron-vite` output on a device.
Read `packages/desktop` for *design*, port nothing of its runtime.

## A6. Layering

```
  Android app  (Kotlin: Activity, Service, WebView, secure storage)
        |  narrow, typed bridge
  Platform adapter  (implements upstream `Platform`)
        |
  Shared OpenCode UI  (@opencode-ai/app, session-ui, ui — SolidJS, unmodified)
        |  ServerConnection + generated SDK
  OpenCode server  (@opencode-ai/server + core, HTTP on 127.0.0.1)
```

Dependencies point downward only. The shared UI must not import Android code.

## A7. Divergence budget

`docs/UPSTREAM_SYNC.md` holds the divergence ledger. If the number of modified
upstream files grows without a matching ADR, stop feature work and reduce it.
