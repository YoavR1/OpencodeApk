# M2 — First Android APK Shell

## Before you do anything

1. Read `CLAUDE.md`.
2. Read `docs/CURRENT_STATUS.md` — this is the authoritative state of the project.
3. Read `.claude/rules/architecture.md`, `.claude/rules/android.md`, and
   `.claude/rules/quality.md`.
4. Read this milestone's exit criteria in `docs/IMPLEMENTATION_PLAN.md`.
5. Verify the repository actually matches what `docs/CURRENT_STATUS.md` claims.
   If it does not, correct the status file first and tell me — do not build on a
   false premise.

Do not skip ahead. If you find work belonging to a later milestone, write it into
`docs/IMPLEMENTATION_PLAN.md` rather than doing it.

## Goal

A **real, installable APK**. The content is a placeholder; the build is not.

This is the first milestone that produces something you can put on a phone.

## Tasks

1. **Create the Android Gradle project** at the location decided in M1
   (`apps/android/` suggested), with a **committed Gradle wrapper**
   (`gradlew`, `gradlew.bat`, `gradle/wrapper/**`). The wrapper is what flips
   `.github/workflows/android-ci.yml` out of its pre-M2 phase state.

2. Use a **version catalog** (`gradle/libs.versions.toml`). Kotlin. AGP, Gradle,
   `minSdk`, and `compileSdk` exactly as decided in M1.

3. **`MainActivity` hosting a `WebView`** showing a placeholder page bundled in
   assets. Not a remote URL.

4. **`WebViewAssetLoader`** so assets are served on an `https://` app origin
   rather than `file://` (ADR-0008). Keep `setAllowFileAccessFromFileURLs` and
   `setAllowUniversalAccessFromFileURLs` **false**.

5. **Network security config** permitting cleartext to `127.0.0.1` only. Do not
   set `usesCleartextTraffic="true"` globally.

6. **One JVM unit test and one instrumented test**, so both harnesses are proven
   to work now rather than discovered broken in M9.

7. **`.gitignore`** already covers Android build output, `local.properties`, and
   keystores — verify it is sufficient for the layout you chose.

8. **Confirm CI flips over.** After pushing, the `android` job should run the real
   build and the `android-phase-state` job should no longer run. Check the Actions
   run and quote the result.

## Manifest discipline

Request the minimum. Every permission gets a comment justifying it. Forbidden
without an ADR: `MANAGE_EXTERNAL_STORAGE`, and anything else in the deny list in
`scripts/ci/verify-apk.sh`.

## Constraints

- No Electron. No Compose/React Native/Flutter rewrite of the OpenCode UI.
- `arm64-v8a` is the ABI that matters. An x86_64-only result is emulator-only.
- The Android SDK is **not** available in a cloud session. `./gradlew assembleDebug`
  will not run locally — CI is the build authority. Do not install the SDK to get
  local green output.

## Exit criteria

See M2 in `docs/IMPLEMENTATION_PLAN.md`. In short: `assembleDebug`, `lint`, and
`test` pass **in CI**; the debug APK is uploaded as an artifact;
`scripts/ci/verify-apk.sh` passes; the APK installs and launches.

Installation and launch need device evidence. If I have not given you a device
report, mark that criterion **BLOCKED** and tell me to install the artifact and
report back (`docs/PHONE_WORKFLOW.md`).

## Finish the session with

1. `docs/CURRENT_STATUS.md` updated: what changed, the **real** commands you ran
   and their **real** output, every claim tagged **VERIFIED** / **ASSUMED** /
   **BLOCKED**, and what is still missing.
2. New decisions appended to `docs/DECISIONS.md`.
3. Any upstream file you modified logged in `docs/UPSTREAM_SYNC.md`.
4. Any new tests registered in `docs/TEST_MATRIX.md`.
5. Commits pushed to the session branch.
6. A plain statement of what is **not** done and what the next session should run.

Never fabricate command output. If a command could not be run, say so with the
actual error and mark the claim BLOCKED.
