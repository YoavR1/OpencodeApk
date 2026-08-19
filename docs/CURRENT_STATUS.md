# Current Status

> **This file is the authoritative state of the project.** Every session reads it
> first and updates it before finishing. If it disagrees with the repository, the
> repository is right — fix this file.

| | |
|---|---|
| **Last updated** | 2026-08-19 |
| **Session** | M5 remote server integration |
| **Branch** | `claude/opencode-android-bootstrap-o58939` |
| **Current milestone** | **M5 — code complete; end-to-end run NOT yet verified** |
| **Next milestone** | **M6 — local runtime feasibility spike** |
| **Next prompt** | **`prompts/06_LOCAL_RUNTIME_SPIKE.md`** — but read the caveat below first |
| **CI** | see the run for this branch's latest commit |

> **M5 is a checkpoint, not the product** (ADR-0003). The goal is an app that
> needs no external server. This milestone exists only so the UI↔server path can
> be proven before the hard on-device work in M6/M7. It is not project completion
> and must never be reported as such.

---

## Correction to what M3 and M4 claimed

**The M3 and M4 APKs almost certainly never rendered the UI at all.**

`ServerProvider` renders its children only when `ready() && !!state.active`
(`packages/app/src/context/server.tsx`, via `createSimpleContext`'s `gate`). The
active key starts as whatever `defaultServer` prop it is given, and the Android
renderer passed `ServerConnection.Key.make("")`. An empty string is falsy, so the
gate never opened: no UI, and therefore no way to reach the dialog that would
have fixed it.

M3 said "the APK contains the real shared SolidJS application". That was true
about *packaging* — the bundle is in the APK and CI proves it — but the runtime
claim it implied was wrong, and M4's mobile-layout work sat behind the same
closed gate. Neither milestone had a device report, which is exactly why the
gap survived two sessions.

Both were marked "device verification outstanding", so nothing was reported as
verified that was not. But the *expected* outcome stated in those files was
wrong, and this is the correction.

Fixed in M5: `packages/android/src/server.ts` resolves the persisted default
server and never yields an empty key. Desktop never hit this because it always
has a sidecar to fall back on; web because it is served *by* the server it talks
to. Android in remote mode is the first case with genuinely no server until the
user adds one.

---

## What M5 built

Very little of M5 is new code, because upstream already had most of it. What it
mostly took was finding the three things that made none of it reachable.

### The three blockers

| # | Blocker | Fix |
|---|---|---|
| 1 | **The server gate never opened** (above) — no UI at all | `server.ts`, a non-empty startup key |
| 2 | **No `INTERNET` permission** — every request would fail on a device with an opaque error | declared, and `verify-apk.sh` now asserts it is in the APK |
| 3 | **CORS refused the app's origin** — every request preflighted and rejected | divergence **D7**, ADR-0019 |

Blocker 3 is the one with a lasting consequence; see the caveat below.

### What upstream already provided, and this project did not rebuild

| Need | Upstream mechanism |
|---|---|
| Add/edit a server: URL, name, username, password | `components/dialog-select-server.tsx` |
| Health/status, live while typing | `utils/server-health.ts` |
| Connect / disconnect | `ServerProvider.add` / `.remove` |
| Persisted server list | `Persist.global("server", ["server.v3"])` |
| "Unreachable" with retry and server switching | `ConnectionGate` → `ConnectionError` |
| Basic auth on every request | `createSdkForServer` / `createApiForServer` |

This project still writes **no HTTP client, no base-URL resolver and no fetch
path**. M7 reuses all of it with a `sidecar`-shaped value instead of an `http`
one.

### Credentials (ADR-0017, supersedes ADR-0015)

M4 decided the preference store need not be encrypted, on the grounds that it
held only UI state. **M5 falsified that premise.** Upstream's `add()` persists a
whole `ServerConnection.Http` — including `http.password` — into its `server.v3`
store, and that store is `Platform.storage`.

Every value in the preference-backed stores is now AES-256-GCM encrypted under a
non-exportable Android Keystore key. Everything, rather than the values believed
to be secret: classifying them is a judgement that must be re-made whenever
upstream persists something new, and it fails silently when made wrongly.

`EncryptedSharedPreferences` was the obvious choice and is **deprecated even in
the stable 1.1.0** — verified by decompiling the artifact, not assumed. The
platform primitive it wrapped is available directly at minSdk 26, so this adds
no dependency at all.

### Cleartext (ADR-0018)

A self-hosted server is typically reached at `http://192.168.x.x:4096`, which the
M2 policy refused. Android's network security config matches hostnames, not
address ranges, so "RFC1918 only" cannot be expressed. Cleartext is now permitted
in **debug builds only**, via a build-type source set; release still denies it
everywhere but loopback.

**No TLS handling was weakened.** No custom trust anchors, no `debug-overrides`,
no hostname verifier, in either build type — asserted by test.

---

## Verification — what actually ran

```
./gradlew lintDebug testDebugUnitTest assembleDebug      BUILD SUCCESSFUL
  lint          0 errors
  unit tests    82 tests, 0 failures
./gradlew assembleDebugAndroidTest                       BUILD SUCCESSFUL

bun test --cwd packages/android                          87 pass, 0 fail
scripts/ci/verify-apk.sh                                 VERIFIED
```

| Layer | M4 | M5 |
|---|---|---|
| Kotlin unit tests | 60 | **82** |
| Renderer tests | 72 | **87** |
| Instrumented (written, not run) | 4 | **9** |

Two of the new suites were **mutation-checked** rather than assumed to work:

- Removing D7 from `cors.ts` fails the divergence guard with the right names.
- Flipping the release network policy to permit cleartext fails
  `NetworkSecurityConfigTest` — but only after the XML was declared a Gradle test
  input. It did **not** fail at first: the task stayed `UP-TO-DATE` because
  Gradle could not see the file the test reads. A test that never re-runs when
  the thing it checks changes is worth nothing, and this one was in that state
  until the mutation exposed it.

### The divergence guard is new and matters

`packages/android/src/divergence.test.ts` asserts every entry in
`docs/UPSTREAM_SYNC.md` is still applied. A merge that drops one produces no
other symptom — the build stays green, the types check, and the app breaks only
on a phone. Run it first after any upstream bump.

---

## BLOCKED — what this environment cannot do

Unchanged from M3/M4: **`bun install` cannot complete here.** The proxy returns
403 for `api.github.com/…/tarball` and `codeload.github.com`, which is how Bun
resolves the workspace's one `github:` dependency (`ghostty-web`). CI is the
authority for anything needing the shared UI bundle.

**What that costs M5 specifically: everything the milestone is actually about.**
There is no device and no OpenCode server here, so none of this is verified:

| Exit criterion | State |
|---|---|
| A real agent turn completes end to end | ❌ **not verified** |
| Streaming/event updates arrive in the UI | ❌ **not verified** (this is **Q9**) |
| Session listing and creation | ❌ **not verified** |
| Connection failures produce clear, non-crashing states | ❌ **not verified** — upstream's `ConnectionGate` handles them by construction, but that is reading, not running |
| Reconnect after the server returns | ❌ **not verified** |

Every M5 test added here covers adapter, protocol or configuration logic. **None
of them makes a network request.** M5's exit criteria are device criteria, and
they are open.

---

## The caveat you need before testing (ADR-0019)

**M5 requires an OpenCode server built from this repository.**

The app's WebView origin is `https://appassets.androidplatform.net`, and every
request carries `Authorization`, which is not CORS-safelisted — so every request
is preflighted. Upstream's allowlist permits `http://localhost:`,
`http://127.0.0.1:`, `oc://renderer`, the Tauri origins and `*.opencode.ai`, but
not ours, and `opencode serve` has **no `--cors` flag** to add one.

Divergence D7 adds the origin, in the same pattern upstream already uses for
Electron. But the patch is in the **server**, so a released `opencode` binary
does not have it and will reject the app.

To test M5, run the server from this checkout:

```
OPENCODE_SERVER_PASSWORD=pick-something \
  bun run --cwd packages/opencode dev serve --hostname 0.0.0.0 --port 4096
```

Then in the app: the URL is `http://<your-machine-ip>:4096`, the username is
**`opencode`** and the password is whatever you set above.

Verified by reading the source, not by running it (no `node_modules` here):

- `serve` takes `--port` and `--hostname` (`packages/opencode/src/cli/network.ts`).
  The default hostname is `127.0.0.1`, which a phone cannot reach, so
  `--hostname 0.0.0.0` is required. That is a deliberate exposure on your LAN for
  the duration of the test — the *app* never binds anything.
- Authentication is an **environment variable, not a flag**:
  `OPENCODE_SERVER_PASSWORD` (`packages/opencode/src/server/auth.ts`). Without it
  the server prints "server is unsecured" and accepts anyone on your LAN.
- The username defaults to `opencode` and is overridable with
  `OPENCODE_SERVER_USERNAME`. This matches what upstream's client sends.
- `Server.listen(opts)` receives only `{port, hostname, mdns}` — **no CORS
  option**, which is what makes D7 necessary rather than a configuration
  problem.

---

## What I need from you

Install `opencode-android-debug` from CI, start a server as above, and report:

1. **Does the UI appear at all?** This is the M3/M4 correction — a blank screen
   before was expected; it should not be blank now.
2. Can you add the server (URL, and username/password if set) and does the
   health indicator go green?
3. Do **sessions list**, and can you create one?
4. Send a prompt: does the reply **stream in**, token by token, or does it arrive
   all at once / not at all? This answers **Q9** and is the single most valuable
   thing you can tell me.
5. Kill the server mid-turn: clear error, or hang/crash? Restart it: does the app
   reconnect?
6. Wrong password: clear message, or a spinner forever?
7. Phone model and Android version.

Question 4 is what M5 exists to answer. If SSE does not survive the WebView, that
changes the design for M7 as well, and better to know now.

---

## Decisions this session

| ADR | Decision |
|---|---|
| **ADR-0017** | The preference store is encrypted — **supersedes ADR-0015** |
| **ADR-0018** | Cleartext HTTP in debug builds only; release and TLS unchanged |
| **ADR-0019** | The Android WebView origin is added to the server's CORS allowlist (**D7**) |

`docs/UPSTREAM_SYNC.md`: **D7 added**. Upstream source divergence is now 4 files,
+26/−10 lines. D3 and D7 are both worth proposing upstream; D6 is deliberately
platform-keyed and is not.

---

## Not done

| Item | Why |
|---|---|
| **Every M5 exit criterion** | No device and no server here. See above. |
| Instrumented tests executed | 9 written; no emulator job. |
| Draft blob files encrypted | App-private and `allowBackup="false"`; deferred to M10 with the rest of storage hardening (ADR-0017). |
| Automatic persistence of the selected server | Upstream's dialog has an explicit "set default". Without one, the app falls back to the first stored server, which covers the common case. |
| A stock `opencode` server working | Needs D7 upstream. ADR-0019 records the fallback if it is not accepted. |

## Recommended next session

**Do not start M6 until the device report is in.** M6 decides whether the
project's actual goal is achievable, and it is the wrong thing to start while it
is still unknown whether the UI renders and whether streaming works in a WebView.

If the report is good, paste **`prompts/06_LOCAL_RUNTIME_SPIKE.md`**.
If streaming fails, that is a design input for M7 and should be fixed first —
ADR-0019 already sketches the fallback (a native `platform.fetch`), which would
also remove the patched-server requirement.
