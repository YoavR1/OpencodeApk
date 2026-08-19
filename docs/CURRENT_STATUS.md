# Current Status

> **This file is the authoritative state of the project.** Every session reads it
> first and updates it before finishing. If it disagrees with the repository, the
> repository is right — fix this file.

| | |
|---|---|
| **Last updated** | 2026-08-20 |
| **Session** | M10 security and credential storage |
| **Branch** | `claude/opencode-android-bootstrap-o58939` |
| **Current milestone** | **M10 — threat review complete; 3 findings fixed; provider-key encryption deferred with a written reason** |
| **Next milestone** | **M11 — polish and release** |
| **Next prompt** | **`prompts/11_*.md`** |
| **Device** | OnePlus 15 (CPH2747), Android 16 / API 36, arm64-v8a, WebView 150.0.7871.184 |

> **M5 is a checkpoint, not the product** (ADR-0003). The goal is an app that
> needs no external server. This is not project completion.

---

## M10: a threat review, and three things it found

Full review in **`docs/SECURITY.md`**. Summary of what changed.

### 1. The WebView had no Content-Security-Policy

The bridge can read the Keystore-backed store, the clipboard and draft blobs, and
the UI renders model output, file contents and diffs — text nobody in the loop
controls. One injection at the app origin is credential disclosure, not a defaced
page.

A CSP is now attached as a **response header** by the asset handler (a `<meta>`
tag is content, and content is what an injection controls). Verified on the
device by running the attacks rather than reading the header:

```json
{ "scriptRan": false,
  "violations": [ "script-src-elem blocked inline",
                  "base-uri blocked https://example.com/",
                  "img-src blocked https://example.com/pixel.png?stolen=secret" ] }
```

ADR-0028. Two directives are deliberately weaker (`style-src 'unsafe-inline'`,
`connect-src https:`) and are written down as residual risks rather than hidden.

### 2. The device was running a runtime two milestones old

`RuntimeAssets` skipped re-extraction on a marker digested from the asset
*listing* plus the version *name*. File contents are not in a listing, and
`versionName` is a constant during development — so a changed bundle with
unchanged filenames was **never re-extracted**:

```
$ adb shell run-as ai.opencode.android grep -c 'parent-gone' files/runtime/launch.mjs
0        # the M9 launcher had never reached the device
```

That is a security property, not a caching detail: a fix shipped inside the
bundle would silently not apply. The marker now includes the installed package's
`lastUpdateTime` and version code. After the fix the same command returns `2`.
ADR-0029.

**This invalidated an M9 claim** — see the correction below.

### 3. An exported Activity trusted an Intent extra

`MainActivity` is exported because it is the launcher. It relayed `EXTRA_TAG`
from any Intent to the renderer as a `notification.clicked` event, firing a
callback the UI had registered. Any app could send it. Tags are now checked
against the ones this process actually posted, and consumed on use — so a
replayed Intent is not a second tap either. ADR-0030.

### Verified on the device

| Property | Evidence |
|---|---|
| Local server binds loopback only | the app's uid owns one LISTEN socket: `0100007F:4096`; nothing on `00000000` |
| Auth cannot be bypassed | no credentials → **401**; wrong password → **401** |
| Nothing is readable outside the app uid | no file under `files/` or `shared_prefs/` |
| Stored values are opaque | ciphertext in `shared_prefs/*.xml` |
| No secret in logcat | 0 matches in the app's own lines |
| Nothing is downloaded at runtime | every binary and every line of server JS ships in the signed APK |

### CI now gates the APK, not just the source

Seven new checks in `scripts/ci/verify-apk.sh`; three mutation-tested by
deliberately breaking the property:

- exported components ≤ 2 — a third → `[FAIL] unexpected exported components: 3`
- `allowBackup="false"` — flipped → `[FAIL] allowBackup is not false`
- release denies cleartext — shipped the debug config → `[FAIL] release permits
  cleartext by default`

`aapt2` is now found inside the SDK when it is not on `PATH`. Without that these
checks degraded to warnings, which reads like a pass and is not one.

### Not fixed, deliberately

**Provider API keys are plaintext on disk.** Upstream writes them:

```ts
// packages/opencode/src/auth/index.ts
yield* fsys.writeJson(file, { ...data, [norm]: info }, 0o600)
```

Encrypting them means forking upstream's auth path, on the hot path of every
provider call — against `.claude/rules/architecture.md` A1. What protects them
today is the app sandbox, `allowBackup="false"`, and a non-debuggable release.
What does not is a rooted device. `docs/SECURITY.md` §3 carries the full
reasoning and a concrete proposal (`OPENCODE_AUTH_CONTENT`) with its own
trade-off stated. The session database is deferred with it.

---

## Correction to M9: the orphan result was Android's doing, not the watchdog's

M9 said the runtime "shuts down on stdin EOF" and cited the no-orphan
measurements as support. Two things were wrong:

1. The watchdog **was never in the APK**. `apps/android/runtime/launch.mjs` is
   staged into assets by `scripts/runtime/prepare-android-runtime.py`, which was
   not re-run — and the stale-marker bug above meant it would not have been
   re-extracted even if it had been.
2. Re-tested properly with a **control run**: killing only the app process, with
   the watchdog *removed*, the runtime died anyway. On this device Android kills
   the child itself.

What is true, measured after staging it: run with stdin already closed, the
launcher exits after **4 seconds**, versus running indefinitely when the host
holds the pipe open. So the watchdog works — it is defence-in-depth for OEMs
that behave differently, and its value on *this* hardware is unproven because
the platform gets there first. `docs/LIFECYCLE.md` §4 is corrected.

---

## M9: the app survives what Android does to it

**VERIFIED on a OnePlus 15 (Android 16), 2026-08-19.** Eight of the ten
checklist items in `docs/LIFECYCLE.md` pass on hardware; the full table with
evidence is in §8 of that file.

The three things that changed:

1. **The runtime moved from the Activity to the Application** (ADR-0026). Each
   `LocalRuntimeController` mints its own per-launch password, so an
   Activity-owned runtime means a recreation produces a controller whose
   credential the running server rejects — and an old runtime nobody is watching.
   Measured: recreation did *not* actually leave two servers, because the
   renderer's handle survived and nothing asked again. That is luck, and the
   invariant should not depend on which of two things happens first.

2. **The runtime shuts down on stdin EOF.** Android usually kills the process
   group with the app — and on this device it does, after both `am force-stop`
   and `am kill` — but "usually" is not a guarantee across OEMs, and an orphan
   holds a port and owns a database nobody can talk to.

3. **The foreground service runs only while a turn is in flight** (ADR-0027),
   driven by the server's own `/session/status` on the health poll that already
   runs. Twenty minutes backgrounded and idle: **0** `RuntimeService` instances.

### The defect this milestone found

`kill -9` on the runtime, with a session open:

```
runtime before kill: 27543
opencode] runtime failed: the runtime stopped unexpectedly (exit 137) - restarting
runtime after kill:
```

The watchdog noticed and the renderer asked for a restart — and nothing started.
`LocalRuntimeController` reused its cached start because it had *completed
successfully*, which describes a process that was alive when it finished, not one
that is alive now. It handed back the address of the process that had just died.

Fixed, and re-measured on the same path:

```
runtime before kill: 27543
runtime after kill:  28793
app pid:             23952        <- unchanged; only the runtime restarted
serving again:       401          <- listening, and still demanding the password
runtime state attr : ready
```

`LocalRuntimeControllerTest.aRuntimeThatDiedIsRestartedRatherThanHandedBackDead`
fails if that predicate is ever loosened again — confirmed by reverting the fix.

### Data integrity under SIGKILL

The case worth testing is a kill *mid-session*, because the runtime keeps SQLite
in WAL mode and holds a lock directory with a heartbeat. Killed with a 249 KB
database and a 259 KB WAL open:

- the restart reopened it with no error or lock complaint in `opencode.log`
- the stale lock directory was gone
- the session rehydrated in the UI **by name** ("Big Pickle"), with its project

### What is NOT verified

Checklist items 5 and 6 — the notification appearing during a turn and clearing
afterwards. Both need a real agent turn, which needs a provider credential. The
*policy* is verified (item 4: nothing is held while idle); the *path* is not.

---

## M8: real projects, and Git

**Git runs on the phone.** Bundled the same way as Node, verified end to end in a
real project on the device:

```
git init -b main; git add README.md; git commit -m "first commit"
--- status ---   ## main
--- log ---      757658b first commit
--- diff ---     README.md | 1 +
--- branch ---   * main
```

**21 instrumented tests pass**, including the whole local workflow above.

**The central constraint, and what it forced.** SAF returns `content://` URIs and
Node cannot open one — so a folder picked through SAF is *unusable* as a working
directory, not merely awkward. Projects are therefore app-private directories with
real POSIX paths, and SAF is used to move files in and out (**ADR-0024**).
`openDirectoryPickerDialog` now returns a **path**, not a URI, which is a
deliberate change to what M4 shipped.

That is also the answer to permissions: **nothing broad is requested**. The user
grants one tree, at the moment they import it.

**Two measurements shrank this milestone, as in M6:**

- The bash tool uses `ChildProcess`, **not a PTY** — so shell commands work today.
  `node-pty` is needed only by the interactive terminal panel.
- 146 of git's 181 helpers are hardlinks to one binary. Shipping `git` plus
  `git-remote-http` costs ~8 MB and covers every builtin.

**Terminals are deferred, with a reason** (ADR-0025): `node-pty` has no Android
build and cross-compiling it needs the NDK plus headers for a Node this project
does not yet build itself. It belongs with ADR-0022's open item — compiling Node —
since they share the toolchain. The shim throws if a terminal is opened rather
than failing silently.

**Import is bounded and honest.** 20,000 files, 512 MB, 32 MB per file, with
`node_modules`/`.git`/`build` skipped. What was skipped is reported: an agent
reasoning about a tree that is quietly missing files is worse than one told the
tree is incomplete.

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
| Foreground service observed during a turn | Lifecycle items 5 and 6. Needs a provider credential; the idle policy (item 4) is verified. |
| **Provider API keys encrypted at rest** | Upstream writes `auth.json` in plaintext at mode 0600; encrypting it forks upstream's auth path. `docs/SECURITY.md` §3. |
| **Session database at rest** | Unencrypted in the app sandbox. Deferred with `auth.json` — the same decision. |
| An adversarial app probing the exported Activity / loopback port | The properties were established from the merged manifest and socket measurements, not by installing a hostile app. ASSUMED, not VERIFIED. |
| Signing and R8 | M11. The release APK is currently unsigned and unshrunk. |
| Basic auth against a live server | The test server ran unsecured, so no credential entered the source. `createSdkForServer` is upstream code and unchanged. |

## Recommended next session

**A real agent turn.** It is now the single largest unverified claim in the
project, and it blocks the last two lifecycle checklist items as well as the
end-to-end streaming evidence still outstanding from M5. It needs one provider
credential entered on the device — the user's budget, which is why no session has
assumed it. Everything up to the model call is verified.

After that, M10 per `docs/IMPLEMENTATION_PLAN.md`.

*(Superseded plan for M7, kept for context: this is where the*
runtime gets packaged into the APK as `lib*.so`, started from a foreground
service, and pointed at by the same `ServerConnection` the app already uses.
