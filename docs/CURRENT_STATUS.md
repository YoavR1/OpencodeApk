# Current Status

> **This file is the authoritative state of the project.** Every session reads it
> first and updates it before finishing. If it disagrees with the repository, the
> repository is right — fix this file.

| | |
|---|---|
| **Last updated** | 2026-08-19 |
| **Session** | M7 local runtime integration |
| **Branch** | `claude/opencode-android-bootstrap-o58939` |
| **Current milestone** | **M7 — the app starts its own server. No PC, no external server.** |
| **Next milestone** | **M8 — files, terminal, Git** |
| **Next prompt** | **`prompts/08_TERMINAL_FILES_GIT.md`** |
| **Device** | OnePlus 15 (CPH2747), Android 16 / API 36, arm64-v8a, WebView 150.0.7871.184 |

> **M5 is a checkpoint, not the product** (ADR-0003). The goal is an app that
> needs no external server. This is not project completion.

---

## M7: the app is standalone

Install the APK, open it, and the OpenCode UI comes up against a server the app
started itself. No PC, no `adb reverse`, no external server, no Termux.

Verified on the OnePlus 15 from a clean install (`pm clear`):

| Claim | Evidence |
|---|---|
| The app starts its own server | `ps`: `libnode.so` pid 2282, **parent 27279 = the app**, uid `u0_a599` |
| It is reachable and the UI uses it | the shared UI renders connected, hitting `http://127.0.0.1:4096` |
| Auth is enforced on loopback | unauthenticated `curl` → **HTTP 401** |
| State survives | `files/opencode/` persists across restarts |
| Start/serve/stop | instrumented smoke test, **17 tests pass on the device** |
| APK | **63.7 MB** compressed (97.3 MB native + 37.5 MB assets uncompressed) |

**Two defects caught before the device saw them**, both by unit tests:

- A failed start propagated out of `async` and would have cancelled
  `lifecycleScope` - taking the bridge and the UI down with a recoverable
  runtime failure. Runtime work now runs under a supervisor.
- A cached failure would have required an app restart before the runtime would
  try again. It now retries.

**What is not done.** The runtime is owned by the Activity, so Android may kill it
when the app is backgrounded — a foreground service is M9, and until then a long
agent turn is not protected. Terminals are still unavailable (M8). And no agent
turn has been run end to end, because that needs a provider credential.

Packaging, environment and lifecycle decisions are recorded in **ADR-0023**.

---

## M6: the project's goal is reachable

The milestone that decides whether a standalone app is possible. **It is.**

- **The OpenCode server runs on the phone.** Node 26.4.0 for Android aarch64 on
  the OnePlus 15, unrooted, hosting upstream's own Node build: ready in 3.1 s,
  `/global/health` → healthy, `/api/session` → a real response. All five spike
  questions answered by execution.
- **Upstream's Node build works and needs almost nothing native.** Built it, ran
  it, served `/global/health` in 1.4 s. tree-sitter and imaging resolve to WASM,
  SQLite to the `node:sqlite` built-in, `@parcel/watcher` is not referenced, and
  no `.node` binding appears anywhere. **The only native blocker is PTY.**
- **W^X measured, not assumed.** An instrumented test running as the app's own
  uid confirms the app may execute a binary shipped as a jniLib and may **not**
  execute one in `filesDir`. 15/15 pass on the device.
- **Sizes measured from the ELF:** 97.3 MB of runtime (Node + 10 libraries, of
  which `libicudata.so.78` alone is 33.1 MB) plus ~37 MB of app bundle.

Recommendation: an on-device Node process started like the desktop sidecar
(**ADR-0022**). It lands exactly where M5 finished — `http://127.0.0.1:<port>` is
the one origin mixed content does not block (ADR-0021).

**Bun was tested and does not work.** `bun-linux-aarch64-musl` is not statically
linked — its `PT_INTERP` is `/lib/ld-musl-aarch64.so.1`, which Android does not
ship, and the phone refuses it. So upstream's own runtime is out and Node is in.

**What M7 inherits.** 97.3 MB of runtime plus ~37 MB of bundle; two build steps
(rename `libicuuc.so.78`-style names with `DT_NEEDED` patched, and prefer a Node
built `--with-intl=small-icu` to drop 33 MB of ICU data); `useLegacyPackaging`;
environment plumbing for `OPENSSL_CONF`, the shell path and `HOME`; and terminals
unavailable until `@lydell/node-pty` is built for Android.

Full evidence: `docs/LOCAL_RUNTIME_SPIKE.md`.

---

## M5: the app works

For the first time, the real OpenCode UI ran on a real phone, connected to a real
server, driven through its own interface:

1. Cold start → first-run setup screen.
2. Typed `127.0.0.1:4096`, tapped **Connect**.
3. Health probe passed, server saved, **the shared OpenCode UI loaded connected** —
   Projects, session search, Settings, Help.
4. Force-stop and relaunch → straight back into the connected UI.

Everything below was observed on that device, not inferred.

---

## Four defects, all found only by running it

Every one of these passed CI. None was reachable by any test this project had.

### 1. The bridge replied on the wrong thread

`BridgePort.send` called `WebMessagePort.postMessage` from whatever thread the
handler finished on. Bridge handlers deliberately do disk work on `Dispatchers.IO`
and replied from there; WebView APIs must run on the WebView's thread. The post
threw, `runCatching` swallowed it, and the renderer waited out its 15-second
timeout with nothing to say why.

`host.info` was the one method that worked, because it alone replies straight from
the main dispatcher — which is what made the pattern legible at all.

**Fixed** by marshalling inside `send`, so no handler has to remember.

### 2. `connect()` destroyed the channel it had just built

`onPageFinished` fires **twice** on a cold start. Each call tore down the live
channel and posted a fresh port that the renderer — which had stopped listening
after the first handshake — never picked up. The page kept posting into a port
whose host end had been closed, and simply went quiet.

Measured directly: 6 requests sent, 4 answered, then silence. A page *reload* fires
`onPageFinished` once and looked perfectly healthy, which is why only a cold start
showed it.

**Fixed** three ways: `connect()` is idempotent per document, `onPageStarted`
invalidates the channel when a genuinely new document loads, and the renderer now
**adopts a later handshake** instead of ignoring it — failing anything in flight
immediately rather than letting it hang.

### 3. The shared UI cannot render with zero servers

`LayoutProvider`'s init reads `serverSdk().scope`. With an empty server list there
is no server context, and the app threw before drawing anything. Upstream never
meets this: web is served by its own server, desktop always has a sidecar.

**Fixed** with a first-run setup screen (ADR-0020).

### 4. Mixed content, not cleartext — ADR-0018 was wrong about why it existed

With the server on the LAN and the phone able to reach it (`adb shell curl … → 200`),
the app still reported "Could not reach". The reason:

```
Mixed Content: The page at 'https://appassets.androidplatform.net/index.html'
was loaded over HTTPS, but requested an insecure resource
'http://192.168.1.156:4096/api/health'. This request has been blocked
```

The app's origin is `https://`, and a secure context may not make plaintext
requests. **No Android setting affects this** — so ADR-0018's debug-cleartext
config, added precisely to enable a LAN server, never could have.

`http://127.0.0.1` is exempt as a *potentially trustworthy* origin. **M7's
on-device server is therefore unaffected**; only M5's remote-over-HTTP case is
impossible. Recorded as ADR-0021, and the setup screen now refuses such an address
at entry with an explanation.

---

## VERIFIED on the device

| Claim | Evidence |
|---|---|
| **SSE streaming works in Android WebView** — **Q9 ANSWERED** | `OPEN 200 text/event-stream http://127.0.0.1:4096/global/event`, still open after 15s, captured over CDP |
| Session listing | `GET /api/session?limit=5000&order=desc` → 200 |
| The rest of the API path | `/global/health`, `/api/health`, `/global/config`, `/provider`, `/path`, `/session/status`, `/project` all 200 |
| The shared UI renders on a phone | Screenshots; mobile layout, correct fonts |
| **Stored data is encrypted at rest** (ADR-0017) | `shared_prefs/oc_android.dat.xml` holds `AQwMAz+qZt8…` — base64 of `01 0c 0c …`: version byte 1, IV length 12, exactly the ADR-0017 record format. Not the JSON that went in. |
| The Keystore key is real and non-exportable | **9 instrumented tests passed on the device** — the first time any have ever run |
| Soft-keyboard insets (M4) | The form adjusted correctly with the IME open |
| Notification permission flow (M4) | System prompt appeared on first launch |
| `ConnectionError` retry state | "Could not reach … Retrying automatically…" against an unreachable server |

```
bun test --cwd packages/android                     97 pass, 0 fail
./gradlew lintDebug testDebugUnitTest assembleDebug  BUILD SUCCESSFUL
./gradlew connectedDebugAndroidTest                  9 tests on CPH2747, BUILD SUCCESSFUL
```

---

## Corrections to earlier milestones

**M3/M4 never rendered.** Confirmed. The blank screen was real, and the M5
session-start correction was right about the server gate — but the gate turned out
to be the first of four blockers, not the only one.

**The M5 pre-device claims were optimistic.** The code was written and tested, and
it did not work. Nothing was marked VERIFIED that was not, but "code complete"
meant four defects away from functioning. That distinction is already in
`.claude/rules/quality.md` Q1; this is the milestone that paid for it.

---

## Notes for anyone debugging on hardware

- **OnePlus/Oplus ROMs suppress third-party app logs from logcat.** Not one
  `SafeLog` line appeared, including at startup. `chromium` output does get
  through because it is a system component, which is why the WebView console became
  the only usable channel. Do not read app-log silence as evidence of anything.
- **The WebView DevTools protocol is the real tool.**
  `WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)` is already on, so
  `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` gives full CDP:
  evaluate JS, capture exceptions, list network requests. Every finding above came
  from there.
- **`adb reverse tcp:4096 tcp:4096`** puts a dev-machine server on the phone's
  loopback. It is both the only way to exercise remote mode (ADR-0021) and a
  faithful rehearsal of M7's topology.

---

## Not done

| Item | Why |
|---|---|
| **A real agent turn** | Needs a provider credential on the server; spending the user's API budget was not something to assume. Everything up to the model call is verified. |
| SSE *delivery* observed end to end | The stream is open and the API works. A server-created session did not appear in the app because sessions are scoped to added projects — not a defect, but not proof of delivery either. |
| Remote LAN server over HTTP | **Impossible** from this origin (ADR-0021). Needs https or a tunnel. |
| CI re-run for this work | Local suites are green; CI has not yet run these commits. |
| Basic auth against a live server | The test server ran unsecured, so no credential entered the source. `createSdkForServer` is upstream code and unchanged. |

## Recommended next session

**M8 — `prompts/08_TERMINAL_FILES_GIT.md`.** The one native gap left is PTY:
`@lydell/node-pty` has no Android arm64 build, and the bundle imports it
statically, so a shim satisfies the import and throws if a terminal is opened.
Building it with the NDK is M8's first job.

*(Superseded plan for M7, kept for context: this is where the*
runtime gets packaged into the APK as `lib*.so`, started from a foreground
service, and pointed at by the same `ServerConnection` the app already uses.
