# Implementation Plan — M0 to M11

One milestone per session (or several sessions). Do not skip ahead. Each milestone
lists **exit criteria**; a milestone is complete only when every criterion is met
*and* backed by evidence (`.claude/rules/quality.md`).

Each milestone has a paste-ready prompt in `prompts/`.

| M | Name | Prompt | Status |
|---|---|---|---|
| M0 | Baseline / bootstrap | `prompts/00_BOOTSTRAP.md` | **complete** |
| M1 | Architecture audit | `prompts/01_ARCHITECTURE_AUDIT.md` | **complete** |
| M2 | First Android APK shell | `prompts/02_ANDROID_SHELL.md` | **complete** — CI builds and verifies the APK |
| M3 | Shared OpenCode UI | `prompts/03_SHARED_UI.md` | **complete — UI verified rendering on a device in M5** |
| M4 | Android platform adapter / mobile UX | `prompts/04_MOBILE_PLATFORM_ADAPTER.md` | **complete — mobile layout, IME and notifications verified on a device in M5** |
| M5 | Remote server integration (checkpoint) | `prompts/05_REMOTE_SERVER_MODE.md` | **working on hardware; LAN-over-HTTP impossible (ADR-0021)** |
| M6 | Local runtime feasibility spike | `prompts/06_LOCAL_RUNTIME_SPIKE.md` | **runtime proven on device; one combination step outstanding** |
| M7 | Local runtime integration | `prompts/07_LOCAL_RUNTIME_INTEGRATION.md` | not started |
| M8 | Files / terminal / Git | `prompts/08_TERMINAL_FILES_GIT.md` | not started |
| M9 | Lifecycle / resilience | `prompts/09_ANDROID_LIFECYCLE.md` | not started |
| M10 | Security / storage | `prompts/10_SECURITY_STORAGE.md` | not started |
| M11 | Polish / release | `prompts/11_POLISH_RELEASE.md` | not started |

`docs/CURRENT_STATUS.md` is authoritative for status. This table is a summary.

---

## M0 — Baseline / bootstrap

**Goal:** a durable project-control layer so future sessions need no chat history.

Deliverables: `CLAUDE.md`, `.claude/**`, `docs/**`, `prompts/**`, `scripts/**`,
`.github/workflows/android-ci.yml`; upstream architecture verified by reading the
real code; baseline checks run and recorded.

**Exit criteria**
- [x] Control layer committed.
- [x] Upstream architecture verified against real source, with paths cited.
- [x] Repository nature determined (new wrapper repo, not a fork).
- [x] Baseline checks run; real results in `docs/CURRENT_STATUS.md`.
- [x] No Android implementation started.

---

## M1 — Architecture audit

**Goal:** convert the M0 reading into decided, evidence-backed integration choices.

**Tasks**
1. Decide the **upstream integration mechanism** (ADR-0002): git submodule vs.
   vendored subtree vs. npm-consumed packages vs. fork. Criteria: how many upstream
   files must be patched, whether the shared UI can be built without patching, and
   the cost of an upstream bump.
2. Enumerate exactly which upstream files must change for `platform: "android"`.
   Expect `packages/app/src/context/platform.tsx` — confirm whether anything else
   is needed.
3. Determine how to build `packages/app` into static assets suitable for an APK,
   including base path and asset URL handling.
4. Enumerate `packages/core` + `packages/server` runtime requirements: Node/Bun
   APIs used, native modules, filesystem assumptions, subprocess use.
5. Pin the upstream commit in `docs/UPSTREAM_SYNC.md` and prove the pin is
   buildable (or record precisely why it is not, in this environment).
6. Choose `minSdk`, `compileSdk`, JDK, and AGP/Gradle versions (ADR).

**Exit criteria**
- [x] ADR-0002 decided and recorded with the evidence behind it — *vendor upstream
      history; decided by the npm-publication measurement.*
- [x] Definitive list of upstream files requiring modification, in `UPSTREAM_SYNC.md`
      — *D1–D5: 2 source files, 3 edits, 2 trivial root merges.*
- [~] A reproducible command producing shared-UI static assets — **deferred to M3**.
      Upstream is not vendored yet, so no build could be run. The *mechanism* is
      established (own vite root + `@opencode-ai/app/vite`, exactly as
      `packages/desktop`); proving it is M3's first job.
- [x] Runtime-requirement inventory written into `ARCHITECTURE.md` — *including the
      W^X constraint, which materially changes the M6/M7 option space.*
- [x] Android toolchain versions decided — *ADR-0011.*
- [x] `CURRENT_STATUS.md` updated.

Additionally delivered beyond the original criteria: the Android shell decision
(ADR-0009), the project layout (ADR-0010), the platform capability matrix
(`ARCHITECTURE.md` Part 3), the `BridgePort` message contract, and the
`OpencodeRuntime` boundary.

---

## M2 — First Android APK shell

**Goal:** a real, installable APK. Minimal content; real build.

**Tasks**

0. ~~Vendor upstream~~ — **moved to M3 (ADR-0012).** M2 has no dependency on
   upstream, and merging here would make M2's green-CI criterion depend on
   upstream's lint and typecheck.

1. Create the Android Gradle project at `apps/android/` (ADR-0010) with a
   **committed Gradle wrapper**.
2. `MainActivity` hosting a `WebView` with a placeholder page.
3. Version catalog (`gradle/libs.versions.toml`); Kotlin; AGP per M1 ADR.
4. `WebViewAssetLoader` wired so assets are served on an `https://` app origin.
5. Network security config permitting cleartext to `127.0.0.1` only.
6. One JVM unit test and one instrumented test, so both harnesses are proven.
7. `.gitignore` for Android build output, `local.properties`, keystores.
8. Confirm `android-ci.yml` flips from "pre-M2 phase state" to a real build and
   uploads `app-debug.apk`.

**Exit criteria (exact)**

*Vendoring* — **moved to M3 (ADR-0012).**

*Android build*
- [ ] `apps/android/gradlew` is committed and executable.
- [ ] `scripts/ci/build-android.sh` exits **0** in CI (no longer 3).
- [ ] `./gradlew lintDebug` passes with no new baseline suppressions.
- [ ] `./gradlew testDebugUnitTest` passes and runs **at least one real JVM test**.
- [ ] `./gradlew assembleDebug` produces `app-debug.apk`.
- [ ] The APK is uploaded as the `opencode-android-debug` CI artifact.
- [ ] `scripts/ci/verify-apk.sh` exits **0**: APK non-empty, dex present, manifest
      present, application id `ai.opencode.android`, a launchable activity, and
      **no forbidden permission**.
- [ ] The CI `android` job runs and `android build (pre-M2 phase)` no longer runs.

*Correctness of the shell*
- [ ] `WebViewAssetLoader` serves the placeholder over an `https://` app origin —
      asserted by an instrumented test, not by inspection.
- [ ] `network_security_config.xml` permits cleartext to `127.0.0.1` only;
      `usesCleartextTraffic` is not set globally.
- [ ] `allowFileAccessFromFileURLs` and `allowUniversalAccessFromFileURLs` are
      both `false`.
- [ ] `minSdk` 26 / `compileSdk` 36 / JDK 21 per ADR-0011.

*Device*
- [ ] The APK installs and launches on a real phone. Needs a device report from
      the user — mark **BLOCKED** if absent rather than assuming.

- [ ] `CURRENT_STATUS.md` and `docs/TEST_MATRIX.md` updated.

---

## M3 — Shared OpenCode UI

**Goal:** upstream's real SolidJS app rendering inside the Android WebView.

**Tasks**
1. Build `packages/app` to static assets; package them into the APK.
2. Widen `PlatformName`/`Platform` to include `"android"` (record in `UPSTREAM_SYNC.md`).
3. Provide a minimal `Platform` implementation: `version`, `openExternal`,
   `restart`, `notify`, `storage`.
4. Decide asset packaging: bundled at build time vs. downloaded (bundled preferred —
   offline, no external dependency).
5. Confirm the app boots, routes, and renders with no server connected.

**Exit criteria**
- [ ] Real upstream UI renders in the WebView (screenshot evidence).
- [ ] No forked copy of upstream UI source in this repo.
- [ ] Upstream divergence for this milestone is one union widening, or a documented
      justification if more.
- [ ] Instrumented test asserts the UI mounted.
- [ ] `CURRENT_STATUS.md` updated.

---

## M4 — Android platform adapter / mobile UX

**Goal:** a complete, tested `Platform` implementation and a phone-appropriate UI.

**Tasks**
1. Full Android `Platform`: `notify` via Android notifications, `openExternal` via
   `Intent`, `storage` bridged to Android, `draftStore`, `getDefaultServer` /
   `setDefaultServer`.
2. Narrow, typed JS↔Kotlin bridge (prefer `WebMessagePort`), with input validation.
3. Directory picking via SAF (`ACTION_OPEN_DOCUMENT_TREE`).
4. Mobile UX: touch targets, soft-keyboard insets, safe areas, back-button
   handling, orientation.
5. Adapter contract tests on both sides of the bridge.

**Exit criteria**
- [x] Every implemented `Platform` method has a test.
- [x] Bridge surface documented in `ARCHITECTURE.md` and minimal — 2.4, ADR-0013.
- [ ] Android back button and soft keyboard behave correctly (instrumented tests).
      *Back policy is unit-tested on both sides (ADR-0014) and the keyboard path is
      wired; neither has run on a device, and no emulator job exists.*
- [x] No Android branching inside shared UI — the three upstream edits (D1/D2, D3,
      D6) are a union widening, a capability check, and one default.
- [x] `CURRENT_STATUS.md` updated.

---

## M5 — Remote server integration (CHECKPOINT ONLY)

**Goal:** prove the full UI↔server path works end to end, using a remote server,
before attempting the on-device runtime.

> **This is not the product.** Never report M5 as project completion.

**Tasks**
1. Server connection UI: URL, username, password.
2. Construct `ServerConnection.Http` and drive the existing client factories.
3. Credentials via the M10 storage interface (Keystore-backed even now; do not
   defer to plaintext).
4. Verify streaming/event transport (SSE/WebSocket) through Android WebView.
5. Handle offline, auth failure, and reconnection.
6. Complete a real agent turn against a real server.

**Exit criteria**
- [ ] A real agent turn completes end to end against a remote server.
      *Everything up to the model call is verified on a OnePlus 15. The turn
      itself needs a provider credential and was not run.*
- [x] Streaming/event updates arrive in the UI (evidence). **Q9 answered:** an
      open `text/event-stream` to `/global/event` was observed in the WebView.
- [x] Connection failures produce clear, non-crashing UI states — observed
      against an unreachable server, and at server entry (ADR-0021).
- [x] `CURRENT_STATUS.md` records this as a **checkpoint, not the goal**.

**Found on hardware** (all fixed): the bridge replied off the WebView thread;
`onPageFinished` fires twice and `connect()` destroyed its own channel; the shared
UI cannot render with zero servers; and a plain-http LAN server is blocked by
mixed content regardless of Android configuration (ADR-0021).

**Found while doing M5** (all fixed): the server gate never opened, so M3/M4
rendered nothing; `INTERNET` was never declared; and the server's CORS allowlist
refused the app's origin.

---

## M6 — Local runtime feasibility spike

**Goal:** determine, with evidence, how to run the OpenCode server on Android
ARM64. **No production code.** The deliverable is a report plus artifacts.

**Tasks**
1. Evaluate candidates A–D from `ARCHITECTURE.md` §2.4.
2. Attempt to produce `packages/opencode/dist/node` and inventory its actual
   runtime requirements.
3. Enumerate every native dependency and its Android ARM64 status:
   `@lydell/node-pty`, `@parcel/watcher`, `tree-sitter-*`, SQLite driver.
   Identify WASM or pure-JS fallbacks (`web-tree-sitter` is already WASM).
4. Test the chosen runtime candidate on an ARM64 Android target (emulator or
   device) — even just booting the runtime and serving `/health` is decisive
   evidence.
5. Measure startup time, RSS, and APK size impact.
6. Write `docs/LOCAL_RUNTIME_REPORT.md` with a recommendation and the evidence.

**Exit criteria**
- [x] A written report with real logs/artifacts — `docs/LOCAL_RUNTIME_SPIKE.md`.
      *(The prompt names it LOCAL_RUNTIME_SPIKE.md; this plan previously said
      LOCAL_RUNTIME_REPORT.md. The prompt won.)*
- [x] A clear recommendation — ADR-0022, an on-device Node process.
- [x] Native-dependency matrix with per-item Android arm64 status.
- [x] ADR recording the runtime decision.
- [x] Explicit list of features lost or degraded — terminals, and only terminals.
- [ ] The OpenCode server running on the Android Node build **on the device**.
      Staged; interrupted when the phone disconnected. One command to finish.

**If no candidate is viable:** say so plainly, record it, and escalate to the user
before proceeding. Do not silently redefine the goal as remote-only.

---

## M7 — Local runtime integration

**Goal:** the OpenCode server runs on the device. This is the milestone that makes
the project real.

**Tasks**
1. Package the runtime + server bundle into the APK for `arm64-v8a`.
2. `OpencodeService` (foreground service) owning the server process lifecycle.
3. Port the desktop sidecar pattern: per-launch password, loopback bind, CORS
   allowlist set to the WebView origin, URL handed to the UI.
4. Build the local `ServerConnection` value and hand it to the same client factories.
5. App-private data directory (`XDG_STATE_HOME` equivalent) surviving restarts.
6. Startup, readiness, health check, and failure handling.
7. Complete a full agent turn with no external server.

**Exit criteria**
- [ ] Server starts on-device on `arm64-v8a` (log evidence).
- [ ] Binds `127.0.0.1` with auth enabled (verified).
- [ ] The UI connects through the **same** `ServerConnection` path as M5.
- [ ] A full agent turn completes with **no external server**.
- [ ] Server survives app backgrounding.
- [ ] Data persists across app restart.
- [ ] `CURRENT_STATUS.md` updated.

---

## M8 — Files, terminal, Git

**Goal:** the developer workflow features, adapted to a phone.

**Tasks**
1. File browsing/editing over SAF-granted project folders.
2. Terminal: assess PTY viability on Android from the M6 matrix; if
   `@lydell/node-pty` is unavailable, decide between a pipe-based fallback and
   disabling the feature — with an ADR either way.
3. Git operations, including credential handling.
4. Mobile-appropriate diff and file-tree UI.

**Exit criteria**
- [ ] Files can be browsed and edited from a real project folder.
- [ ] Terminal works, or its absence is a recorded, justified decision.
- [ ] Git status/diff/commit work against a real repository.
- [ ] Tests cover each feature.
- [ ] `CURRENT_STATUS.md` updated.

---

## M9 — Lifecycle / resilience

**Goal:** treat Android lifecycle and process death as normal operation.

**Tasks**
1. Agent turns survive backgrounding (foreground service verified under real
   backgrounding, not just in the foreground).
2. Process-death recovery: relaunch, reconnect, rehydrate session state.
3. Configuration changes do not restart the server.
4. Low-memory behaviour; graceful degradation.
5. Network transitions (Wi-Fi ↔ cellular ↔ offline).
6. Battery: no needless wakelocks; bounded work.

**Exit criteria**
- [ ] Instrumented test: turn survives backgrounding.
- [ ] Instrumented test: state recovers after simulated process death.
- [ ] Rotation does not restart the server (test).
- [ ] Network transitions handled without data loss (test).
- [ ] `CURRENT_STATUS.md` updated.

---

## M10 — Security / storage

**Goal:** production-grade credential and data handling.

**Tasks**
1. Provider credentials in Keystore-backed storage; migrate anything earlier.
2. Audit every permission in the manifest; remove anything unjustified.
3. Confirm loopback-only binding and that auth cannot be disabled.
4. Audit the JS bridge surface for injection and privilege escalation.
5. Ensure no credential or server password reaches logs.
6. Decide and document the data-at-rest posture for the server's SQLite database.

**Exit criteria**
- [ ] Credentials Keystore-backed (test).
- [ ] Every permission justified in writing.
- [ ] Loopback-only binding verified by test.
- [ ] Bridge surface reviewed and documented.
- [ ] Log audit shows no secret leakage.
- [ ] Security review recorded in `DECISIONS.md`.

---

## M11 — Polish / release

**Goal:** a real, shippable app.

**Tasks**
1. App icon, splash, theming, dark mode.
2. Onboarding: provider setup, project selection.
3. Error states, empty states, loading states.
4. Performance pass: startup time, memory, scroll performance.
5. Release build: R8/ProGuard rules, signing config (keys never committed).
6. `README.md` for end users; installation instructions.
7. Accessibility pass.

**Exit criteria**
- [ ] Signed release APK builds in CI (signing material from CI secrets).
- [ ] Onboarding works from a clean install.
- [ ] Full `docs/TEST_MATRIX.md` passes.
- [ ] Performance targets recorded and met.
- [ ] User-facing README complete.
- [ ] `CURRENT_STATUS.md` reflects release state.

---

## Cross-cutting rules

- Every milestone updates `docs/CURRENT_STATUS.md`.
- Every architectural choice becomes an ADR in `docs/DECISIONS.md`.
- Every upstream file modified is logged in `docs/UPSTREAM_SYNC.md`.
- Every new test is registered in `docs/TEST_MATRIX.md`.
- No milestone is complete without evidence.
