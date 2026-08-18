# Test Matrix

What is tested, at which layer, and where the evidence lives. Update this file
whenever the set of tests changes (`.claude/rules/quality.md` Q6).

**Legend:** ✅ passing · ⚠️ partial · ❌ failing · ⬜ not implemented · 🚫 blocked

---

## Current state (M3)

| Layer | Status |
|---|---|
| Android JVM unit tests | ✅ 15 tests, run in CI by `testDebugUnitTest` |
| Android instrumented tests | ⚠️ 4 written, **not run** — no emulator job yet |
| Renderer adapter tests | ✅ run in CI by `bun test --cwd packages/android` |
| Shared UI build | ✅ upstream vendored; `build-shared-ui.sh` gates the APK |
| Integration tests | ⬜ |
| Manual device verification | ⬜ awaiting a device report |

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
| Platform bridge | Message serialisation/deserialisation | M4 | ⬜ |
| Platform bridge | Rejects malformed and out-of-contract messages | M4 | ⬜ |
| Server connection | Local `ServerConnection` value construction | M7 | ⬜ |
| Server connection | Basic-auth header construction | M5 | ⬜ |
| Credential store | Store/retrieve/delete round-trip | M10 | ⬜ |
| Credential store | Secrets never appear in log output | M10 | ⬜ |
| Service lifecycle | Start/stop state machine | M7 | ⬜ |

## Layer 2 — Android instrumented tests (`src/androidTest/`)

Require a device or emulator. Run by `./gradlew connectedAndroidTest`.

| Area | Test | Milestone | Status |
|---|---|---|---|
| App launch | Activity starts without crashing | M2 | ⚠️ written, no emulator job |
| App identity | `packageName` is `ai.opencode.android` | M2 | ⚠️ written, no emulator job |
| WebView | Security settings locked down (file access off both forms) | M2 | ⚠️ written, no emulator job |
| WebView | Placeholder loads from the **https** asset origin, not `file://` | M2 | ⚠️ written, no emulator job |
| Shared UI | Upstream UI mounts and renders | M3 | ⬜ |
| Platform adapter | `notify` produces a real notification | M4 | ⬜ |
| Platform adapter | `openExternal` fires the right `Intent` | M4 | ⬜ |
| Navigation | Android back button behaves correctly | M4 | ⬜ |
| Input | Soft keyboard insets do not occlude input | M4 | ⬜ |
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
| Capabilities | no capability appears in two categories | M3 | ✅ |
| Capabilities | `refuseUnsupported` throws a typed, explanatory error | M3 | ✅ |
| Capabilities | every `UNSUPPORTED` reason is `never` or a milestone | M3 | ✅ |
| `openExternal` | passes `http`, `https`, `mailto` to the host | M3 | ✅ |
| `openExternal` | **refuses `javascript:`, `intent:`, `file:`, `content:`** | M3 | ✅ |
| `openExternal` | ignores malformed URLs without throwing | M3 | ✅ |
| `version` | from the host, `undefined` outside it | M3 | ✅ |

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
| Battery drain is acceptable | M9 | ⬜ |

---

## Rules

1. A milestone's exit criteria are met only when its tests here are ✅.
2. Never mark ✅ without observed output.
3. 🚫 requires a written reason.
4. Never delete a failing test to get green (`.claude/rules/quality.md` Q4).
