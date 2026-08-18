# Test Matrix

What is tested, at which layer, and where the evidence lives. Update this file
whenever the set of tests changes (`.claude/rules/quality.md` Q6).

**Legend:** ✅ passing · ⚠️ partial · ❌ failing · ⬜ not implemented · 🚫 blocked

---

## Current state (M0)

No tests exist. There is no application code yet.

| Layer | Status |
|---|---|
| Android JVM unit tests | ⬜ no Gradle project until M2 |
| Android instrumented tests | ⬜ no Gradle project until M2 |
| Shared UI tests | ⬜ upstream not integrated until M1/M3 |
| Integration tests | ⬜ |
| Manual device verification | ⬜ |

---

## Layer 1 — Android JVM unit tests (`src/test/`)

Fast, no device. Run by `./gradlew test`.

| Area | Test | Milestone | Status |
|---|---|---|---|
| Build sanity | Gradle project assembles and a trivial test runs | M2 | ⬜ |
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
| App launch | Activity starts without crashing | M2 | ⬜ |
| WebView | Asset loader serves the app origin | M2 | ⬜ |
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

## Layer 3 — Shared UI tests (upstream)

Upstream's own tests, run per-package. **Never** via the root `test` script.

| Package | Command | Status |
|---|---|---|
| `packages/app` | `bun test --cwd packages/app` | ⬜ not integrated |
| `packages/session-ui` | `bun test --cwd packages/session-ui` | ⬜ not integrated |
| `packages/ui` | `bun test --cwd packages/ui` | ⬜ not integrated |
| `packages/client` | `bun test --cwd packages/client` | ⬜ not integrated |

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
| Workspace lint/typecheck | `scripts/ci/check-opencode.sh` | M1 | ⬜ |
| `assembleDebug` succeeds | `scripts/ci/build-android.sh` | M2 | ⬜ |
| Debug APK uploaded as artifact | `.github/workflows/android-ci.yml` | M2 | ⬜ |
| APK contains `arm64-v8a` | `scripts/ci/verify-apk.sh` | M7 | ⬜ |
| APK requests no forbidden permission | `scripts/ci/verify-apk.sh` | M2 | ⬜ |
| Release APK signs and builds | CI | M11 | ⬜ |

## Layer 6 — Manual device verification

Some things only a human with a phone can confirm. Record results in
`docs/CURRENT_STATUS.md` with the device model and Android version.

| Check | Milestone |
|---|---|
| APK installs on a real phone | M2 |
| UI is usable one-handed | M4 |
| A real agent turn completes on-device | M7 |
| App survives an overnight background period | M9 |
| Battery drain is acceptable | M9 |

---

## Rules

1. A milestone's exit criteria are met only when its tests here are ✅.
2. Never mark ✅ without observed output.
3. 🚫 requires a written reason.
4. Never delete a failing test to get green (`.claude/rules/quality.md` Q4).
