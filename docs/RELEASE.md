# Release engineering

How an OpenCode Android build is produced, signed, versioned and verified.

Everything here was run on this repository; measured numbers name the device and
build type they came from.

---

## 1. Building

Three steps, in order. The Gradle build fails rather than guessing if the first
is skipped.

```bash
bun install --frozen-lockfile
bun run --cwd packages/android build:release   # NOT `build` - see §2
python scripts/runtime/prepare-android-runtime.py
python scripts/runtime/collect-licenses.py

cd apps/android
./gradlew assembleRelease      # APK
./gradlew bundleRelease        # AAB
```

`prepare-android-runtime.py` is optional: without it the APK builds without the
on-device runtime and needs an external server (ADR-0023). The build says which
kind it produced rather than failing.

## 2. The channel, and why `build:release` is a separate script

Upstream resolves the release channel from `OPENCODE_CHANNEL` and **defaults to
`"dev"`** (`packages/app/vite.js`). A dev-channel bundle draws a DEV badge in the
titlebar and enables debug tooling.

M11 found exactly that shipping in a release APK — visible only in a screenshot,
because nothing else about the build looked wrong. So:

- `bun run --cwd packages/android build` builds the **dev** channel, for local
  iteration.
- `bun run --cwd packages/android build:release` sets `OPENCODE_CHANNEL=prod`.
- The build records the channel in `dist/build-info.json`, and
  **`assembleRelease` fails** if that says `dev` or is missing.

The channel is baked into the bundle by vite's `define`, so it cannot be read
back reliably once the minifier has folded the comparisons away. Recording it in
a file is what makes the gate possible.

## 3. Signing

**Signing material never enters this repository** (`.claude/rules/quality.md`
Q8). It is read from the environment:

| Variable | Meaning |
|---|---|
| `OPENCODE_KEYSTORE` | Path to the keystore file |
| `OPENCODE_KEYSTORE_PASSWORD` | Store password |
| `OPENCODE_KEY_ALIAS` | Key alias inside the store |
| `OPENCODE_KEY_PASSWORD` | Key password; defaults to the store password |

When they are absent the release build is left **unsigned**. It deliberately does
*not* fall back to the debug key: a debug-signed "release" installs, looks
finished, and can never be upgraded by a properly signed build, because Android
refuses an update whose signature differs. Being unsigned is the loud failure;
being debug-signed is the quiet one.

When `OPENCODE_KEYSTORE` is set but is not a file, the build fails instead of
silently producing an unsigned APK.

v1 (JAR) signing is off. `minSdk` is 26, so every supported device understands
v2/v3, and v1 is the weaker scheme.

### Creating a keystore

```bash
keytool -genkeypair -v -keystore opencode-release.jks -alias opencode \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep it outside the repository. Losing it means the app can never be updated
again under the same identity.

### In CI

Store the keystore as a base64 secret and materialise it into the runner's
workspace at build time, never into the checkout:

```yaml
- run: echo "${{ secrets.OPENCODE_KEYSTORE_B64 }}" | base64 -d > "$RUNNER_TEMP/ks.jks"
- run: ./gradlew bundleRelease
  env:
    OPENCODE_KEYSTORE: ${{ runner.temp }}/ks.jks
    OPENCODE_KEYSTORE_PASSWORD: ${{ secrets.OPENCODE_KEYSTORE_PASSWORD }}
    OPENCODE_KEY_ALIAS: ${{ secrets.OPENCODE_KEY_ALIAS }}
    OPENCODE_VERSION_CODE: ${{ github.run_number }}
```

## 4. Versioning

| | Source | Default |
|---|---|---|
| `versionName` | `-Popencode.versionName` or `OPENCODE_VERSION_NAME` | `0.1.0` |
| `versionCode` | `-Popencode.versionCode` or `OPENCODE_VERSION_CODE` | `1` |

`versionCode` must increase monotonically for every build a device may see;
Android refuses to install an APK whose code is not higher than the installed
one. CI passes `github.run_number`, which is monotonic per repository. A local
build gets `1`, which is fine because a local build is never published.

Verified: an in-place upgrade from `versionCode` 11 to 12 on a OnePlus 15 kept
the app's data — the runtime restarted, the server answered, and the UI came back
with its previous state.

## 5. Reproducibility

The build is reproducible in the sense that matters here: **the same inputs
produce the same APK contents.** Two properties support that.

- Nothing is fetched at build time. The renderer bundle, the runtime binaries and
  the licence texts are all materialised into the tree by the scripts above,
  before Gradle runs.
- No timestamps are embedded by our own steps. `build-info.json` records the
  channel and nothing else, specifically so that two identical inputs do not
  produce different outputs.

What is *not* claimed: byte-identical APKs across machines. AGP embeds build
tooling versions, and the zip contains ordering and timestamp metadata this
project does not currently normalise. Achieving bit-for-bit reproducibility would
need `SOURCE_DATE_EPOCH` handling and a pinned build-tools version; it has not
been done and is not asserted.

## 6. What the release build verifies

`assembleRelease` and `bundleRelease` both depend on two gates that no unit test
or lint check can perform, because they are properties of the packaged artifact:

| Gate | Fails when |
|---|---|
| `verifyReleaseChannel` | the packaged UI was built for the `dev` channel |
| `verifyAttribution` | a bundled native library has no licence entry, or `NOTICE.txt` is stale |

Then `scripts/ci/verify-apk.sh` checks the artifact itself — permissions,
exported components, `allowBackup`, debuggability, and that release denies
cleartext by default. See `docs/SECURITY.md` §13.

## 7. Measured

OnePlus 15 (CPH2747), Android 16 / API 36, arm64-v8a. Signed release build with
R8 and resource shrinking on, after a clean install.

| | |
|---|---|
| Cold start (`am start -W`, `LaunchState: COLD`) | **234 ms** / 239 ms |
| App launch → local server answering | **~2.8 s** (two runs, 2829 / 2820 ms; includes adb polling) |
| App process memory | 120 MB PSS / 298 MB RSS |
| Runtime (Node) process memory | **382 MB PSS** / 385 MB RSS |
| Release APK | 57.7 MB |
| Release AAB | 58.5 MB |
| Debug APK, for comparison | 82.5 MB |

APK composition:

| Part | Size | Share |
|---|---|---|
| native libraries | 39.1 MB | 67.7% |
| shared UI | 11.0 MB | 19.1% |
| runtime bundle | 6.8 MB | 11.8% |
| dex | 0.4 MB | 0.7% |
| everything else | 0.3 MB | 0.5% |

The runtime's 382 MB is the number to watch. It is Node plus the OpenCode server,
and it is why `minSdk` targets modern devices; on a phone with little free memory
Android will reclaim it, which is what `docs/LIFECYCLE.md` is about.

## 8. R8

Enabled in M11 with resource shrinking. It removes 24.8 MB (82.5 → 57.7 MB), most
of it unused framework and library code.

The risk with R8 is runtime failure that no build step catches, so it was
verified by running the shrunk build rather than by inspecting it: clean install
on hardware, cold start measured, local runtime started, server answered with
401, and the UI rendered — see the screenshots referenced in
`docs/CURRENT_STATUS.md`.

There is nothing reflective to keep: the app talks to the WebView over a
`WebMessagePort`, not `addJavascriptInterface`, so no keep rules were needed
beyond the AGP defaults.
