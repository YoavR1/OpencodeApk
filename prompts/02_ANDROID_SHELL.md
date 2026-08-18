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

## Decisions already made — do not re-litigate these

M1 settled these with evidence. Read `docs/DECISIONS.md` for the reasoning.

| | Decision | ADR |
|---|---|---|
| Integration | Vendor upstream history into this repo | 0002 |
| Shell | Native Kotlin + `WebView` + `WebViewAssetLoader` | 0009 |
| Layout | `packages/android/` (renderer) + `apps/android/` (Gradle) | 0010 |
| Toolchain | JDK 21, `compileSdk` 36, `minSdk` 26, `arm64-v8a` | 0011 |

If evidence appears that contradicts one, say so and record it — but do not
quietly substitute a different choice.

## Tasks

0. **Vendor upstream first (ADR-0002).**

   ```bash
   git remote add upstream https://github.com/anomalyco/opencode
   git fetch upstream dev
   git merge upstream/dev --allow-unrelated-histories
   ```

   Exactly two conflicts are expected:
   - `README.md` → keep ours (divergence D4)
   - `.gitignore` → upstream's, plus our Android/secrets section (D5)

   **A third conflict means the M1 measurement was wrong** — record it in
   `docs/UPSTREAM_SYNC.md` and reassess before continuing.

   Commit the merge on its own, so the Android work is reviewable separately.

   Then run `bun install --frozen-lockfile`. If the cloud proxy blocks it, record
   the actual error as BLOCKED and let CI be the authority — do **not** drop
   `--frozen-lockfile` or regenerate the lockfile.

1. **Create the Android Gradle project** at `apps/android/` with a **committed
   Gradle wrapper** (`gradlew`, `gradlew.bat`, `gradle/wrapper/**`). The wrapper is
   what flips `.github/workflows/android-ci.yml` out of its pre-M2 phase state.

2. Use a **version catalog** (`gradle/libs.versions.toml`). Kotlin. Versions per
   ADR-0011.

3. **`MainActivity` hosting a `WebView`** showing a placeholder page bundled in
   assets. Not a remote URL.

4. **`WebViewAssetLoader`** (`androidx.webkit`) so assets are served on an
   `https://` app origin rather than `file://` (ADR-0008). This mirrors the
   desktop's privileged `oc://renderer` scheme, which is registered with
   `{ secure, standard, supportFetchAPI, stream }` — the same four properties
   `WebViewAssetLoader` gives you. Keep `setAllowFileAccessFromFileURLs` and
   `setAllowUniversalAccessFromFileURLs` **false**.

   Note the origin you choose; it must later be passed to
   `Server.listen({ cors: [...] })`.

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

`docs/IMPLEMENTATION_PLAN.md` M2 now carries the **exact** checklist, covering the
vendoring merge, the Android build, the correctness of the shell's security
settings, and device installation. Work through it literally.

Installation and launch need device evidence. If I have not given you a device
report, mark that criterion **BLOCKED** and tell me to install the artifact and
report back (`docs/PHONE_WORKFLOW.md`).

## A note on scope

M2 is a **placeholder** page in a real APK. Do not pull the shared OpenCode UI in —
that is M3, and it depends on the `platform.tsx` widening that M2 does not do.

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
