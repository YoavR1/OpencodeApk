# Test Matrix

What is tested, at which layer, and where the evidence lives. Update this file
whenever the set of tests changes (`.claude/rules/quality.md` Q6).

**Legend:** ✅ passing · ⚠️ partial · ❌ failing · ⬜ not implemented · 🚫 blocked

---

## Current state (M5)

| Layer | Status |
|---|---|
| Android JVM unit tests | ✅ **141 tests**, run in CI by `testDebugUnitTest` |
| Android instrumented tests | ✅ **21 tests, RUN AND PASSING on a OnePlus 15** (M5–M8) — locally, not in CI |
| Renderer tests | ✅ **98 tests** across 7 files, run in CI by `bun test --cwd packages/android` |
| Device verification | ✅ **the app runs, connects and streams** — see `docs/CURRENT_STATUS.md` |
| Cross-language contract | ✅ TS ↔ Kotlin bridge method lists compared mechanically |
| Upstream divergence guard | ✅ **new in M5** — every entry in `UPSTREAM_SYNC.md` fails a test if a merge drops it |
| Shared UI build | ✅ upstream vendored; `build-shared-ui.sh` gates the APK |
| Integration tests | ⬜ |
| Manual device verification | ⬜ awaiting a device report |

**M5 update: a device closed most of this gap.** The app was run on a OnePlus 15
against a live server, and four defects were found that every test here had
passed. The unit suites were not wrong, they were aimed at the wrong layer:
threading between the WebView and its host, `onPageFinished` firing twice, an
empty server list, and a browser security rule are none of them reachable from a
JVM or a bun test. Where a regression test was possible it was added; where it
was not, the finding is recorded in `docs/DECISIONS.md`.

**What no test here still covers: the UI itself, and the network.** Every renderer test
in this file exercises adapter, protocol or configuration logic. Nothing renders
a component and nothing makes a request, because the shared UI cannot be built in
this environment (`bun install` is blocked by the proxy — see
`docs/CURRENT_STATUS.md`). So the M4 layout claims and the whole of M5's
end-to-end path are reasoned from source, not observed. CI builds the bundle; a
device with a real server confirms the rest.

**On the instrumented tests.** They are written and committed but no CI job runs
them, so they are ⚠️ rather than ✅. An emulator job is deliberately deferred:
`reactivecircus/android-emulator-runner` adds several minutes and a flakiness
surface to every push, and M2 has four tests to justify it. M3 adds the "shared
UI mounts" assertion, which is the point where a device-backed job earns its
cost. Until then, "the APK launches" is verified by a device report, not by CI.

---

## Layer 1 — Android JVM unit tests (`src/test/`)

Fast, no device. Run by `./gradlew test`.

| Area | Test | Milestone | Status |
|---|---|---|---|
| Build sanity | Gradle project assembles and real tests run | M2 | ✅ |
| Asset origin | `WebOrigin.ORIGIN` is https on the reserved domain | M2 | ✅ |
| Asset origin | `INDEX_URL` resolves to the bundled entry point | M2 | ✅ |
| Asset origin | `url()` tolerates a leading slash; nested paths work | M2 | ✅ |
| Asset origin | App origin recognised bare, with path, query, fragment | M2 | ✅ |
| Asset origin | `http://` is rejected | M2 | ✅ |
| Asset origin | **Lookalike hosts rejected** (`…net.evil.com`, `not…net`) | M2 | ✅ |
| Asset origin | `null`, empty, `file://`, unrelated https rejected | M2 | ✅ |
| Log redaction | `password=` / `password:` / case variants | M2 | ✅ |
| Log redaction | token, secret, apikey, api_key | M2 | ✅ |
| Log redaction | **whole `Authorization:` header value** | M2 | ✅ |
| Log redaction | Basic credentials embedded in a URL | M2 | ✅ |
| Log redaction | surrounding context preserved | M2 | ✅ |
| Log redaction | `passwordless` not falsely redacted | M2 | ✅ |
| Asset origin | **App is served from the origin root**, so the shared CSS's `/assets/…` font URLs resolve | M3 | ✅ |
| Bridge contract | Well-formed request parses; params default to empty | M4 | ✅ |
| Bridge contract | **9 malformed shapes refused** (no id, negative id, non-numeric id, no method, empty method, non-object, bad JSON, …) | M4 | ✅ |
| Bridge contract | Wrong-typed `params` does not crash the parser | M4 | ✅ |
| Bridge contract | Unknown methods parse but are not in `METHODS` | M4 | ✅ |
| Bridge contract | `success`/`failure`/`event` envelope shapes; null result sent explicitly | M4 | ✅ |
| Bridge contract | Events carry no `id`, so no promise resolves on one | M4 | ✅ |
| Preferences | Round-trip, missing key, remove, clear, sorted keys | M4 | ✅ |
| Preferences | Named stores do not share keys | M4 | ✅ |
| Preferences | **Path traversal in a store name cannot escape the directory** | M4 | ✅ |
| Preferences | A name of only dots is still a plain file; overlong names truncate | M4 | ✅ |
| Drafts | Text round-trip; **survives a reopen** (stands in for process death) | M4 | ✅ |
| Drafts | Blob round-trip with type; identical bytes share one id | M4 | ✅ |
| Drafts | Ids are lowercase sha256 hex; missing type falls back | M4 | ✅ |
| Drafts | **8 non-hash blob ids refused before touching disk** | M4 | ✅ |
| Back | Press is offered to the renderer, not decided locally | M4 | ✅ |
| Back | **A renderer that never answers still lets the user leave** | M4 | ✅ |
| Back | A timeout after an answer does not exit | M4 | ✅ |
| Back | **Stale and superseded replies are discarded** | M4 | ✅ |
| Back | **An unsolicited `back.handled` cannot exit the app** | M4 | ✅ |
| Back | No renderer ⇒ exit immediately rather than waiting out the timeout | M4 | ✅ |
| Runtime | Starting twice starts one server; concurrent callers join one start | M7 | ✅ |
| Runtime | **A failed start does not cancel the Activity's scope** (supervisor) | M7 | ✅ |
| Runtime | A failure is not cached — the next call retries | M7 | ✅ |
| Runtime | Each launch gets its own password; the handle is loopback | M7 | ✅ |
| Runtime | The config binds loopback and allows the WebView origin | M7 | ✅ |
| Runtime | State names are stable, distinct, and never carry the password | M7 | ✅ |
| Projects | Names become readable slugs; overlong ones truncate | M8 | ✅ |
| Projects | **No slug can contain a separator or traverse** | M8 | ✅ |
| Projects | **A traversing name still resolves inside the store** | M8 | ✅ |
| Projects | A name that slugifies to nothing still gets a directory | M8 | ✅ |
| Projects | Unicode names produce a usable slug and keep the display name | M8 | ✅ |
| Projects | Create, find, list, delete; delete of a missing project is not an error | M8 | ✅ |
| Projects | Two projects may share a display name, not a directory | M8 | ✅ |
| Projects | **Projects survive a new store over the same directory** | M8 | ✅ |
| Projects | A stray file at the store root is not a project | M8 | ✅ |
| Crypto | Value round-trips; empty, unicode and 200 KB values | M5 | ✅ |
| Crypto | **The stored form does not contain the plaintext** | M5 | ✅ |
| Crypto | A fresh IV per operation, so equal values differ on disk | M5 | ✅ |
| Crypto | **Tampered ciphertext, tampered IV and truncated records are refused** | M5 | ✅ |
| Crypto | An unknown scheme version is refused rather than misread | M5 | ✅ |
| Crypto | A nonsense IV length is rejected before reaching the cipher | M5 | ✅ |
| Crypto | **Plaintext written before M5 reads as absent, not a crash** | M5 | ✅ |
| Crypto | Another key cannot read the value; the same key can | M5 | ✅ |
| Storage | **Values are unreadable in the raw SharedPreferences file** | M5 | ✅ |
| Storage | Key names stay readable so listing still works | M5 | ✅ |
| Network policy | **The release config denies cleartext**, and only debug permits it | M5 | ✅ |
| Network policy | Cleartext is permitted to loopback and nothing else | M5 | ✅ |
| Network policy | **Neither config installs a trust anchor** (TLS verification intact) | M5 | ✅ |
| CSP | Scripts: no `unsafe-inline`, no `unsafe-eval`, hashes only | M10 | ✅ |
| CSP | `wasm-unsafe-eval` does not readmit `eval` | M10 | ✅ |
| CSP | **Remote images are refused** (the cheap exfiltration channel) | M10 | ✅ |
| CSP | Loopback reachable on any port; no arbitrary cleartext | M10 | ✅ |
| CSP | `object-src`/`frame-src`/`base-uri`/`form-action`/`frame-ancestors` closed | M10 | ✅ |
| CSP | Inline scripts hashed exactly; external ones are not hashed | M10 | ✅ |
| CSP | **The real packaged `index.html` still yields a hash** | M10 | ✅ |
| Notification tags | A tag this app never posted is refused | M10 | ✅ |
| Notification tags | A posted tag is accepted exactly once (no replay) | M10 | ✅ |
| Notification tags | Bounded memory; oldest dropped | M10 | ✅ |
| Runtime updates | **A new install re-extracts even with an identical version name** | M10 | ✅ |
| Runtime updates | An unchanged install does not re-extract | M10 | ✅ |
| Runtime updates | An unrecognised marker is replaced, not trusted | M10 | ✅ |
| Runtime lifecycle | **A runtime that died is restarted, not handed back dead** | M9 | ✅ |
| Runtime lifecycle | A degraded (alive but quiet) runtime is left alone | M9 | ✅ |
| Runtime lifecycle | `alive` separates a process that exists from one that does not | M9 | ✅ |
| Server connection | Local `ServerConnection` value construction | M7 | ⬜ |
| Server connection | Basic-auth header construction | M5 | ⬜ |
| Credential store | Store/retrieve/delete round-trip | M10 | ⬜ |
| Credential store | Secrets never appear in log output | M10 | ⬜ |
| Service lifecycle | Start/stop state machine | M7 | ⬜ |

## Layer 2 — Android instrumented tests (`src/androidTest/`)

Require a device or emulator. Run by `./gradlew connectedAndroidTest`.
**25 tests**, all passing on a OnePlus 15 (Android 16).

| Area | Test | Milestone | Status |
|---|---|---|---|
| Keystore | The key is really in `AndroidKeyStore` | M5 | ✅ **passed on device** |
| Keystore | **The key material cannot be exported** | M5 | ✅ **passed on device** |
| Keystore | A value survives a new cipher instance; another alias cannot read it | M5 | ✅ **passed on device** |
| Runtime | The app can launch a shell command | M6 | ✅ **passed on device** |
| Runtime | **An executable shipped as a jniLib runs from `nativeLibraryDir`** | M6 | ✅ **passed on device** |
| Runtime | **An executable in `filesDir` cannot be run — W^X** | M6 | ✅ **passed on device** |
| Security | Runtime binaries are not writable (the other half of W^X) | M10 | ✅ |
| Security | `nativeLibraryDir` does not accept new files | M10 | ✅ |
| Security | App storage is not readable or writable by other apps | M10 | ✅ mode `rwxrwx--x` — others traverse only |
| Security | Files the app writes are not world-accessible | M10 | ✅ |
| Runtime | The app can bind a loopback port | M6 | ✅ **passed on device** |
| Runtime | The app can read and write its own storage | M6 | ✅ **passed on device** |
| Runtime | The app is not running as root | M6 | ✅ **passed on device** |
| Runtime | **The runtime starts, serves and stops** | M7 | ✅ **passed on device** |
| Runtime | **An unauthenticated request to the local server is refused (401)** | M7 | ✅ **passed on device** |
| Runtime | The server is on loopback, and gone after stop | M7 | ✅ **passed on device** |
| Runtime | Starting twice reuses the same server | M7 | ✅ **passed on device** |
| Git | The bundled binary runs (`git --version`) | M8 | ✅ **passed on device** |
| Git | **init, add, commit, status, log, diff, branch** in a real project | M8 | ✅ **passed on device** |
| Git | Remote plumbing works and `git-remote-https` is linked | M8 | ✅ **passed on device** |
| Projects | The project path is real and inside the sandbox | M8 | ✅ **passed on device** |
| App launch | Activity starts without crashing | M2 | ✅ **passed on device** |
| App identity | `packageName` is `ai.opencode.android` | M2 | ✅ **passed on device** |
| WebView | Security settings locked down (file access off both forms) | M2 | ✅ **passed on device** |
| WebView | Loads from the **https** asset origin, not `file://` | M2 | ✅ **passed on device** |
| Shared UI | Upstream UI mounts and renders | M3 | ✅ **verified on device** (M5) |
| Platform adapter | `notify` produces a real notification | M4 | ⬜ |
| Platform adapter | `openExternal` fires the right `Intent` | M4 | ⬜ |
| Navigation | Android back button behaves correctly | M4 | ⬜ policy unit-tested; end-to-end needs a device |
| Input | Soft keyboard insets do not occlude input | M4 | ✅ **verified on device** (M5) |
| Layout | Mobile breakpoint activates at a phone viewport | M4 | ✅ **verified on device** (M5) |
| Layout | Hover-revealed controls are visible on touch | M4 | ⬜ |
| Remote server | Session listing against a real server | M5 | ✅ **verified on device** — `/api/session` 200 |
| Remote server | Session creation, prompt send, streamed reply | M5 | ⬜ **device + server only** |
| Remote server | SSE over `fetch` survives WebView (**Q9**) | M5 | ✅ **ANSWERED: yes** — open `text/event-stream` observed |
| Remote server | Wrong credentials, unreachable host, offline, mid-turn drop | M5 | ⬜ |
| Remote server | Reconnect after the server returns | M5 | ⬜ |
| Local server | Server starts and answers a health check | M7 | ⬜ |
| Local server | Binds `127.0.0.1` only — external bind refused | M7/M10 | ⬜ |
| Local server | Unauthenticated request is rejected | M10 | ⬜ |
| Lifecycle | Agent turn survives backgrounding | M9 | ⬜ |
| Lifecycle | State recovers after simulated process death | M9 | ⬜ |
| Lifecycle | Rotation does not restart the server | M9 | ⬜ |
| Lifecycle | Network transition does not lose session data | M9 | ⬜ |
| Storage | Data persists across app restart | M7 | ⬜ |

## Layer 3 — Renderer and shared UI (TypeScript)

Run per-package. **Never** via the root `test` script.

### Our renderer — `bun test --cwd packages/android`

| Area | Test | Milestone | Status |
|---|---|---|---|
| Identity | reports `platform: "android"` | M3 | ✅ |
| Identity | implements the three members `Platform` requires | M3 | ✅ |
| Capabilities | **every `UNSUPPORTED` member is `undefined`**, not a stub | M3 | ✅ |
| Capabilities | `openPath` / `openDirectoryPickerDialog` are absent, not present-and-undefined | M3 | ✅ |
| Capabilities | `DEGRADED` members exist and each names a milestone | M3 | ✅ |
| Capabilities | **every `SUPPORTED` capability is actually present** | M4 | ✅ |
| Capabilities | `draftStore` is the store the composition root injected | M4 | ✅ |
| Capabilities | no capability appears in two categories | M3 | ✅ |
| Capabilities | `refuseUnsupported` throws a typed, explanatory error | M3 | ✅ |
| Capabilities | every `UNSUPPORTED` reason is `never` or a milestone | M3 | ✅ |
| `openExternal` | passes `http`, `https`, `mailto` to the host | M3 | ✅ |
| `openExternal` | **refuses `javascript:`, `intent:`, `file:`, `content:`** | M3 | ✅ |
| `openExternal` | ignores malformed URLs without throwing | M3 | ✅ |
| `version` | from the host, `undefined` outside it | M3 | ✅ |
| Picker | **Returns a real path, not a SAF uri** (ADR-0024) | M8 | ✅ |
| Picker | An incomplete import still yields a usable project | M8 | ✅ |
| Bridge | Request/response round-trip over a real `MessageChannel` | M4 | ✅ |
| Bridge | Errors reject with a typed `BridgeError` | M4 | ✅ |
| Bridge | Events dispatch to subscribers; unsubscribe works | M4 | ✅ |
| Bridge | No host ⇒ `available` is false and calls reject rather than hang | M4 | ✅ |
| Contract | **TS method union == Kotlin `METHODS`, checked in both directions** | M4 | ✅ |
| Contract | Neither side declares a method twice | M4 | ✅ |
| Contract | Guards against parsing nothing and comparing two empty lists | M4 | ✅ |
| Back | Innermost handler runs first; declining passes the press on | M4 | ✅ |
| Back | **A handler that throws does not trap the user** | M4 | ✅ |
| Back | Unregistering removes only that handler; twice is harmless | M4 | ✅ |
| Back | History cursor: push, replace, clamped `go`, forward-entry discard | M4 | ✅ |
| Back | **Restoring a route at startup does not make back available** | M4 | ✅ |
| Back | **`navigate(-1)` keeps the cursor in step** (it travels via `go`, not `set`) | M4 | ✅ |
| Back | Topmost dialog is the one dismissed; none ⇒ press not consumed | M4 | ✅ |
| Back | A document that cannot build the event declines rather than claiming the press | M4 | ✅ |
| Focus | Text inputs, textareas and `contenteditable` take a keyboard | M4 | ✅ |
| Focus | Checkboxes, buttons, ranges and `contenteditable=false` do not | M4 | ✅ |
| Focus | Focused field is scrolled with `block: "nearest"` | M4 | ✅ |
| Focus | An element that cannot scroll does not throw | M4 | ✅ |
| Startup server | **The key is never empty** — an empty one gates off the whole app | M5 | ✅ |
| Server entry | `normalizeServerUrl`: bare host gets a scheme, trailing slashes dropped, junk refused | M5 | ✅ |
| Server entry | **A LAN http address is refused as unreachable from a secure context** | M5 | ✅ |
| Server entry | https and loopback http are accepted (ADR-0021) | M5 | ✅ |
| Startup server | A stored key is used; blank/missing falls back to the sentinel | M5 | ✅ |
| Startup server | The sentinel cannot be mistaken for a real server key | M5 | ✅ |
| Divergence | **D1/D2** — the `Platform` union still knows about Android | M5 | ✅ |
| Divergence | **D3** — storage is chosen by capability at both call sites | M5 | ✅ |
| Divergence | **D6** — the Android titlebar default survives | M5 | ✅ |
| Divergence | **D7** — the server still accepts the WebView origin, exactly | M5 | ✅ |
| Divergence | The allowlisted origin matches the one Kotlin serves from | M5 | ✅ |

### Upstream's own tests

Not run by this project's CI. They belong to upstream's own workflows, which came
with the vendoring merge and are unmodified.

| Package | Command | Status |
|---|---|---|
| `packages/app` | `bun test --cwd packages/app` | ⬜ upstream's responsibility |
| `packages/session-ui` | `bun test --cwd packages/session-ui` | ⬜ upstream's responsibility |
| `packages/ui` | `bun test --cwd packages/ui` | ⬜ upstream's responsibility |

## Layer 4 — Integration tests

| Scenario | Milestone | Status |
|---|---|---|
| Connect to a remote server, complete an agent turn | M5 | ⬜ |
| Streaming/event transport works through WebView | M5 | ⬜ |
| Auth failure produces a clear UI state | M5 | ⬜ |
| Offline produces a clear UI state | M5 | ⬜ |
| Connect to the on-device server, complete an agent turn | M7 | ⬜ |
| **Full turn with no external server** | M7 | ⬜ |
| Browse and edit files in a SAF-granted folder | M8 | ⬜ |
| Git status / diff / commit against a real repo | M8 | ⬜ |

## Layer 5 — Build and artifact verification

| Check | Script | Milestone | Status |
|---|---|---|---|
| Shell scripts parse and are executable | `android-ci.yml` hygiene job | M0 | ✅ |
| Control files present; no secrets committed | `android-ci.yml` hygiene job | M0 | ✅ |
| Workspace lint (oxlint, whole repo) | `scripts/ci/check-opencode.sh` | M3 | ✅ |
| Typecheck, scoped to `@opencode-ai/android` + `@opencode-ai/app` | `scripts/ci/check-opencode.sh` | M3 | ✅ |
| Shared UI builds; fonts and chunks resolve | `scripts/ci/build-shared-ui.sh` | M3 | ✅ |
| **APK contains `assets/web/index.html`** | `scripts/ci/verify-apk.sh` | M3 | ✅ |
| Build fails loudly when the shared UI is missing | Gradle `verifySharedUi` | M3 | ✅ |
| `lintDebug` passes | `scripts/ci/build-android.sh` | M2 | ✅ |
| `testDebugUnitTest` passes | `scripts/ci/build-android.sh` | M2 | ✅ |
| `assembleDebug` succeeds | `scripts/ci/build-android.sh` | M2 | ✅ |
| Debug APK uploaded as `opencode-android-debug` | `android-ci.yml` | M2 | ✅ 3,483,186 bytes, run 32122205783 |
| APK is non-empty (≥100 KB) | `scripts/ci/verify-apk.sh` | M2 | ✅ |
| Application id is `ai.opencode.android` | `scripts/ci/verify-apk.sh` | M2 | ✅ |
| APK declares a launchable activity | `scripts/ci/verify-apk.sh` | M2 | ✅ |
| APK requests no forbidden permission | `scripts/ci/verify-apk.sh` | M2 | ✅ |
| **At most 2 exported components** | `scripts/ci/verify-apk.sh` | M10 | ✅ mutation-tested (a third → FAIL) |
| **`allowBackup` is false** | `scripts/ci/verify-apk.sh` | M10 | ✅ mutation-tested (true → FAIL) |
| Release APK is not debuggable | `scripts/ci/verify-apk.sh` | M10 | ✅ |
| No blanket `usesCleartextTraffic` | `scripts/ci/verify-apk.sh` | M10 | ✅ |
| **Release denies cleartext by default** | `scripts/ci/verify-apk.sh` | M10 | ✅ mutation-tested (debug config → FAIL) |
| The cleartext exception is loopback-only | `scripts/ci/verify-apk.sh` | M10 | ✅ |
| The packaged launcher does not bind `0.0.0.0` | `scripts/ci/verify-apk.sh` | M10 | ✅ |
| **Release UI was built for a shipping channel** | `verifyReleaseChannel` (Gradle) | M11 | ✅ mutation-tested (dev bundle → FAIL) |
| **Every bundled native library is attributed** | `verifyAttribution` (Gradle) | M11 | ✅ mutation-tested (unmapped lib → FAIL) |
| `NOTICE.txt` is present and current | `collect-licenses.py --check` | M11 | ✅ mutation-tested (missing → exit 1) |
| APK contains `arm64-v8a` | `scripts/ci/verify-apk.sh` | M7 | ⬜ no native libs yet |
| Release APK signs and builds | CI | M11 | ⬜ |

## Layer 6 — Manual device verification

Some things only a human with a phone can confirm. Record results in
`docs/CURRENT_STATUS.md` with the device model and Android version.

| Check | Milestone | Status |
|---|---|---|
| APK installs on a real phone | M2 | ⬜ **awaiting device report** |
| App cold-launches without crashing (ARM64) | M2 | ⬜ **awaiting device report** |
| App shows the https origin, not `file://` | M2 | ⬜ **awaiting device report** |
| **The real OpenCode UI renders** (not a placeholder) | M3 | ⬜ **awaiting device report** |
| Navigation between views works | M3 | ⬜ **awaiting device report** |
| Text renders in the bundled fonts, not a fallback | M3 | ⬜ **awaiting device report** |
| Dark mode follows the system setting | M2 | ⬜ **awaiting device report** |
| Back button exits cleanly from the first page | M2 | ⬜ **awaiting device report** |
| UI is usable one-handed | M4 | ⬜ |
| A real agent turn completes on-device | M7 | ⬜ |
| App survives an overnight background period | M9 | ⬜ |
| Battery drain is acceptable | M9 | 🟡 **partial** — 20 min backgrounded and idle holds **0** foreground services; an overnight measurement has not been taken |
| One runtime per process; parent is the app | M9 | ✅ `30697 25646 libnode.so` |
| Configuration change / *don't keep activities* does not restart the server | M9 | ✅ app and runtime pids unchanged |
| `am force-stop` and `am kill` leave no orphan runtime | M9 | ✅ no `libnode`; port 4096 returns `000` |
| Reopening restores the session and its project | M9 | ✅ session "Big Pickle" in project `tmp`, state `ready` |
| A killed runtime is detected and restarted | M9 | ✅ `27543 → 28793`, app pid unchanged |
| SIGKILL mid-session does not corrupt the database | M9 | ✅ WAL reopened, stale lock cleared, session rehydrated |
| Foreground-service notification appears during a turn and clears after | M9 | ⬜ needs a provider credential |
| **CSP is enforced on-device** (injected script blocked, exfiltration refused) | M10 | ✅ 3 violations recorded, `scriptRan: false` |
| Local server refuses unauthenticated requests | M10 | ✅ 401 with no credentials and with a wrong password |
| Local server binds loopback only | M10 | ✅ the app's uid owns one LISTEN socket, `0100007F:4096` |
| No app file is readable outside the app uid | M10 | ✅ |
| Stored values are opaque on disk | M10 | ✅ ciphertext in `shared_prefs/*.xml` |
| No secret appears in logcat | M10 | 🟡 0 matches, but this OEM suppresses third-party logs |
| The device runs the runtime the APK shipped | M10 | ✅ was stale before the fix; `2` after |
| **Signed release APK installs cleanly on ARM64** | M11 | ✅ v3-signed, `Success`, cold start 234 ms |
| **R8 + resource shrinking does not break the app** | M11 | ✅ 82.5 → 57.7 MB; runtime started, server 401, UI rendered |
| **In-place upgrade keeps data** | M11 | ✅ versionCode 11 → 12, no uninstall, session and project intact |
| Release AAB builds | M11 | ✅ 58.5 MB, both gates ran |
| Release build is not debuggable | M11 | ✅ `run-as` refused on the release APK |
| No DEV badge in a release build | M11 | ✅ was present; fixed and re-photographed |
| Landscape at 1.5× font: content reachable | M11 | ✅ was 555 px clipped to 323 px; now scrolls |
| Landscape at 1.5× font: onboarding card buttons | M11 | ✅ **fixed** — was 316 px of buttons in 262 px; they now wrap |
| **The release APK is bit-for-bit reproducible** | M11 | ✅ two clean builds, identical sha256 (`scripts/ci/check-reproducible.sh`) |
| Every bundled library ships its full licence text | M11 | ✅ 12 texts for 16 libraries; no warnings |
| **The network security config does NOT constrain the server** | M10 | ✅ restrictive policy installed, Node still reached a public host over cleartext (204) |
| OpenCode config is writable by the agent | M10 | ✅ persistence/exfiltration vector, documented |
| Runtime binaries are not writable (W^X) | M10 | ✅ `SandboxPostureTest` |

---

## Rules

1. A milestone's exit criteria are met only when its tests here are ✅.
2. Never mark ✅ without observed output.
3. 🚫 requires a written reason.
4. Never delete a failing test to get green (`.claude/rules/quality.md` Q4).
