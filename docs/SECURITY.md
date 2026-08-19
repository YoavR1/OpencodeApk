# Security

A threat review of the standalone Android app, and what was changed because of
it. Every claim here is marked **VERIFIED**, **ASSUMED** or **BLOCKED** in the
sense of `.claude/rules/quality.md` Q3.

Device for all measurements: **OnePlus 15 (CPH2747), Android 16 / API 36,
arm64-v8a**, debug build unless stated.

---

## 1. What this app is, from an attacker's point of view

Three things make the threat model different from an ordinary app:

1. **It holds provider API credentials.** Those are money and, through the
   model, access to whatever the user's account can reach.
2. **It runs an agent that executes code and edits files by design.** "Arbitrary
   code execution in the app sandbox" is not a vulnerability here; it is the
   product. The boundary that matters is the *app sandbox*, not the process.
3. **It renders untrusted text.** Model output, file contents and diffs all come
   from outside and are drawn in a WebView that can reach native capabilities.

So the questions worth asking are: what can another app on the device get, what
can a malicious repository or prompt get, and what leaves the device.

---

## 2. Assets, and where they live

| Asset | Where | Protection | State |
|---|---|---|---|
| Provider API keys | `filesDir/opencode/.local/share/opencode/auth.json`, written by upstream at mode `0600` | app sandbox only; **not** Keystore-encrypted | **VERIFIED** — see §3, this is the top residual risk |
| Remote-server credentials | `SharedPreferences`, via `PreferenceStore` | AES-256-GCM, Keystore key, non-exportable | **VERIFIED** — opaque on disk |
| Per-launch local server password | process memory + the child's environment | never written to disk | **VERIFIED** |
| Drafts (unsent prompts) | `filesDir/drafts` + preferences | same cipher as above | **VERIFIED** |
| Session database | `filesDir/opencode/.local/share/opencode/*.db` | app sandbox only | **VERIFIED** |
| Project files | `filesDir/projects/<slug>` | app sandbox only | **VERIFIED** |
| Runtime binaries | `nativeLibraryDir` (read-only, W^X) | APK signature | **VERIFIED** |
| Runtime JS bundle | `filesDir/runtime` | app sandbox; re-extracted per install | **VERIFIED** — §8 |

Every one of these is inside the app sandbox. Measured: no file under `files/`
or `shared_prefs/` is readable outside the app's uid, and `allowBackup="false"`
plus explicit exclusion rules keep all of it out of cloud backup and device
transfer.

---

## 3. Provider credentials — the honest position

**Requirement 1 says to use Keystore-backed storage where appropriate.
Requirement 2 says no secrets in plaintext preferences. The app satisfies both
for everything *it* stores. It does not satisfy the spirit of them for provider
API keys, and that needs saying plainly.**

Provider credentials are written by **upstream**, not by this app:

```ts
// packages/opencode/src/auth/index.ts
const file = path.join(Global.Path.data, "auth.json")
...
yield* fsys.writeJson(file, { ...data, [norm]: info }, 0o600)
```

That is a plaintext JSON file at mode `0600`, inside app-private storage. It is
not in SharedPreferences — so requirement 2 is met literally — but it is not
Keystore-encrypted either.

**Why it was not simply encrypted.** Doing so means changing how upstream reads
its own auth store, on the hot path of every provider call, in a file that
`.claude/rules/architecture.md` A1 says to consume rather than fork. The
divergence ledger (`docs/UPSTREAM_SYNC.md`) is already the project's stated
design-smell metric.

**What protects it today** (all **VERIFIED**):

- App-private storage: unreadable by any other app on a non-rooted device.
- `allowBackup="false"` and explicit backup/transfer exclusions: it is not
  copied off the device by the platform.
- Release builds are not debuggable, so no debugger can attach to read it.

**What does not protect it:** a rooted device, or physical access with the
screen unlocked and USB debugging on. On both, the file is readable. A
Keystore-encrypted store would not fully close that either — the app must be
able to decrypt without user interaction to run a background turn, so the key is
available to anything running as the app.

**Recommended follow-up**, sized honestly: `OPENCODE_AUTH_CONTENT` already
exists in upstream's `all()` and is read before the file. Passing decrypted
credentials through the environment at launch would keep the plaintext off disk
entirely with no upstream change at all. It was not done in M10 because it moves
the credential into the process environment, which is readable from
`/proc/self/environ` by the same attacker who could read the file — so it is a
different trade rather than a clear win, and it deserves its own ADR.

---

## 4. OpenCode config

The config file is `filesDir/opencode/.config/opencode/opencode.jsonc`, mode
`0600`, inside the sandbox. On a fresh install it holds a `$schema` line and
nothing else — **VERIFIED** on the device.

What matters is not what it contains today but what it is *able* to do, because
the agent can write it.

**It can carry secrets.** Any config string supports `{env:VAR}` and
`{file:path}` substitution (`packages/opencode/src/config/variable.ts`). So a
value can interpolate an environment variable or the contents of any file the app
can read — `auth.json` included.

**It can fetch remote config, with credential headers.** A config entry may name
a `url` plus `headers`, both substituted as above
(`substituteWellKnownRemoteConfig`). That is an outbound request the app makes on
startup, to a host named in a file the agent can write.

**It can load plugins**, which is arbitrary code in the server process
(`config.plugin`, path-like specs resolved relative to the declaring file). There
are no shell hooks — `experimental` carries policies only — so `plugin` is the
code-execution field.

Chained, those give a concrete attack: a malicious prompt writes a config whose
remote-config header is `{file:…/auth.json}`, and the next start posts the
provider keys to an attacker's host.

**This is persistence, not escalation.** The agent already reads files and makes
network requests by design; nothing here grants it a capability it lacked. What
config adds is *durability* — the behaviour survives restart and is not visible
in any transcript — and a quieter channel than an obvious tool call.

Consequences, taken seriously:

- Config is a **trusted input** to the server, and the agent can write it. Any
  future "review what the agent changed" feature should treat
  `opencode.jsonc` as security-relevant, not as ordinary project noise.
- It is one of the reasons the network security config's scope (§5) matters.

Not mitigated in M10. Doing so means constraining what the server accepts from
its own config file, which is upstream behaviour, not app behaviour — the same
A1 boundary as `auth.json` (§3). Recorded here rather than silently accepted.

Measured on the device, since "the agent can write it" is the load-bearing claim:

| Path | Writable by the app? |
|---|---|
| `opencode.jsonc` | **yes** |
| extracted runtime bundle (`files/runtime/launch.mjs`) | **yes** |
| runtime binaries (`nativeLibraryDir/libnode.so`) | **no** — W^X holds |

The last row is the one that must never change, and is now pinned by
`SandboxPostureTest`.

---

## 5. The local server, and the limits of the network policy

| Property | Evidence | State |
|---|---|---|
| Binds `127.0.0.1` only | the app's uid owns exactly one LISTEN socket: `0100007F:4096`, nothing on `00000000` | **VERIFIED** |
| Authentication is on | `curl` with no credentials → **401**; wrong password → **401** | **VERIFIED** |
| Password is per launch | generated in `LocalRuntimeController`, never persisted; a test asserts two controllers differ | **VERIFIED** |
| Password never logged | `SafeLog.redact`; `RuntimeState` carries the state name only | **VERIFIED** |

Loopback is shared by every app on the device, which is exactly why the password
is not optional. An unauthenticated loopback server would be a local privilege
escalation for any installed app.

### The network security config does not constrain the server

Requirement 6 asks for a review of the network security configuration. Reviewing
it turned up something that changes how its guarantees should be read.

Android's network security config is enforced by the **Android** HTTP stack —
WebView, `HttpURLConnection`, OkHttp, Cronet. The OpenCode server is a separate
native process with its own bundled OpenSSL, and it never consults that policy.

Demonstrated rather than argued. A debug APK was built with the *release*
(restrictive) policy — `cleartextTrafficPermitted=false` for everything except
loopback — installed, and the bundled Node asked to fetch a public host over
plain HTTP:

```
$ aapt2 dump xmltree --file res/xml/network_security_config.xml app-debug.apk
    E: base-config
      A: cleartextTrafficPermitted=false        <- cleartext denied

$ adb shell run-as ai.opencode.android … libnode.so -e 'http.get(…)'
NODE cleartext status: 204                      <- sent anyway
```

(Two earlier attempts returned `ENOTFOUND` and `ETIMEDOUT`. Those were the app
being idle, not the policy: Android restricts network for a stopped app. With the
app running, the request succeeds — which is what makes the result attributable.)

So, precisely:

- **What the policy does cover:** the WebView, and any HTTP the Kotlin side
  makes. That is what the release build and the CI gate in §13 protect.
- **What it does not cover:** every request the server makes — provider API
  calls, remote config fetches (§4), plugin downloads. Those are governed by
  upstream's own TLS handling, not by Android.

This is not a defect introduced here, and it is not fixable with a config file;
it is a property of running a native server. It is written down because
"release denies cleartext" is otherwise easy to read as "this app cannot send
cleartext", and that is false. **VERIFIED.**

The practical mitigation is that provider endpoints are HTTPS by upstream default
and TLS verification is untouched anywhere in this project. What is unguarded is a
config that deliberately names an `http://` URL — see §4.

---

## 6. Exported components, intents and deep links

Enumerated from the **merged** manifest, not the source:

| Component | Exported | Notes |
|---|---|---|
| `MainActivity` | **yes** | It is the launcher; it must be. `MAIN`/`LAUNCHER` only — no deep links, no custom scheme, no `VIEW` filter. |
| `RuntimeService` | no | |
| `androidx.startup.InitializationProvider` | no | |
| `androidx.profileinstaller.ProfileInstallReceiver` | yes | androidx; guarded by the `android.permission.DUMP` signature permission. |

**Finding (fixed).** `MainActivity` accepted a notification tag from its Intent
and relayed it to the renderer as a `notification.clicked` event. Because the
Activity is exported, *any* app could send that extra and fire a callback the UI
had registered. Impact was low — the attacker must guess a tag, and can only
trigger the app's own callback — but the fix is free: tags are now checked
against the ones this process actually posted, and consumed on use so a replayed
Intent is not a second tap. Five unit tests cover it.

`PendingIntent`s are `FLAG_IMMUTABLE`, so nothing else can rewrite them.

**Outbound** intents are constrained too: `openExternal` allows only `http`,
`https` and `mailto`, which keeps web content from launching arbitrary
`intent://` or app-scheme targets through the bridge.

---

## 7. The WebView, and the CSP added in M10

The WebView is the largest attack surface, because the bridge behind it can read
the encrypted store, the clipboard and draft blobs — and the UI renders text the
user did not write.

Already in place before M10 (**VERIFIED** by reading `WebViewHost`):

- assets served over `https://` by `WebViewAssetLoader`, never `file://`
- `allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`,
  `allowUniversalAccessFromFileURLs` all **false**
- `MIXED_CONTENT_NEVER_ALLOW`
- off-origin navigation refused
- no `addJavascriptInterface`; the only channel is a `WebMessagePort` posted to
  the app origin specifically
- `setWebContentsDebuggingEnabled(BuildConfig.DEBUG)` — debug builds only

**Added in M10: a Content-Security-Policy**, attached as a response header by
the asset handler rather than as a `<meta>` tag — a meta tag is content, and
content is what an injection controls.

```
default-src 'self'; script-src 'self' 'wasm-unsafe-eval' 'sha256-…';
style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:;
connect-src 'self' http://127.0.0.1:* http://localhost:* https:;
frame-src 'none'; object-src 'none'; base-uri 'none';
form-action 'none'; frame-ancestors 'none'
```

Verified **on the device**, by attempting the attacks rather than reading the
header:

```json
{ "scriptRan": false,
  "violations": [ "script-src-elem blocked inline",
                  "base-uri blocked https://example.com/",
                  "img-src blocked https://example.com/pixel.png?stolen=secret" ] }
```

The inline script did not execute; both exfiltration paths were refused. The app
renders and the runtime reaches `ready` with the policy in force.

Two deliberate weaknesses, stated rather than hidden:

- **`style-src 'unsafe-inline'`.** Upstream's theme preload injects a `<style>`
  whose content varies with the user's theme, so it cannot be hashed. Injected
  CSS cannot reach the bridge.
- **`connect-src https:`.** Remote-server mode (ADR-0021) points the UI at a
  host the user types, unknown at build time. This is the weakest directive in
  the policy and should become `'self'` plus loopback when remote mode retires.

Inline script hashes are computed from the packaged `index.html` at runtime, so
an upstream change to the preload cannot silently ship a policy that blocks the
app's own code. A test asserts the packaged document still yields a hash.

---

## 8. Runtime binaries and updates

**Nothing is downloaded at runtime.** Every executable and every line of server
JavaScript ships inside the signed APK; the only outbound HTTP the app itself
makes is to its own loopback server. **VERIFIED** by grep over the Kotlin
sources and by the packaging scripts.

Binaries live in `nativeLibraryDir`, which is read-only to the app — W^X, and
the reason they ship as `lib*.so` at all (ADR-0023).

**Finding (fixed) — a stale runtime could survive an upgrade.**
`RuntimeAssets` skipped re-extraction when a marker matched, and the marker was
a digest of the asset *listing* plus the version *name*. File contents are not
in a listing, and the version name is a constant during development. So a
changed bundle with unchanged filenames was never re-extracted: the device kept
executing old JavaScript while reporting the new version.

This is a security property, not a caching detail — a fix shipped in the bundle
would silently not apply.

Caught by comparing the device against the APK:

```
$ adb shell run-as ai.opencode.android grep -c 'parent-gone' files/runtime/launch.mjs
0        # the device was running a launcher two milestones old
```

The marker now includes the installed package's `lastUpdateTime` and version
code, both set by the package manager on every install. After the fix, the same
command returns `2`. Five unit tests cover it, including the exact case that
failed (same version name, new install).

**Residual, by design:** the extracted bundle is writable by the app, so the
agent could modify its own launcher and persist across restarts. This is not an
escalation — the agent already executes arbitrary code by design — but it does
mean *"the runtime is what the APK shipped"* holds only until the first agent
turn. Re-extraction on every install limits how long a modification survives.

---

## 9. Git credentials

The app bundles `git` and configures it for a device with no passwd entry:
`GIT_CONFIG_NOSYSTEM=1`, `GIT_ATTR_NOSYSTEM=1`, and a seeded `.gitconfig` with a
default identity.

**No credential helper is configured, and no Git credentials are stored.**
**VERIFIED** by grep. Remote operations over HTTPS would prompt or fail rather
than persist anything. `git-remote-https` is present so an https remote resolves
at all (M8), but authenticated push was never exercised — see §12.

When Git credential storage is added it must go through `PreferenceStore` (the
Keystore-backed path), not through Git's own `store` helper, which writes
plaintext to `~/.git-credentials`.

---

## 10. Logging and crash diagnostics

`SafeLog` redacts at the logging boundary rather than trusting call sites:
`key=value` and `key: value` for password/token/secret/apikey, whole
`Authorization:` header values, and HTTP Basic credentials embedded in URLs.
Verbose/debug output is compiled out of release by `BuildConfig.DEBUG`.

The runtime's own stdout is drained **through** `SafeLog`, which matters because
that output is upstream's and may contain anything.

**VERIFIED** on the device: a logcat scan for `password|token|secret|api_key|
authorization` in the app's own lines returned nothing. Note the OnePlus ROM
suppresses third-party app logs from logcat, so this is weaker evidence on this
device than it would be elsewhere; the unit tests on `SafeLog.redact` are the
stronger evidence.

**Gap:** redaction is regex-shaped and will miss a bare secret logged with no
surrounding key — e.g. `SafeLog.d(apiKey)`. Nothing in the codebase does that
today; nothing prevents it either.

---

## 11. Project files and content URIs

Projects are app-private directories with real POSIX paths (ADR-0024). The
Storage Access Framework provides `content://` URIs for import only, and the
tree is copied in — Node cannot open a `content://` URI, and holding long-lived
tree grants would be a broader permission than the work needs.

- **No broad storage permission.** No `MANAGE_EXTERNAL_STORAGE`, no
  `READ_EXTERNAL_STORAGE`. CI fails the build on the former.
- **Path traversal is refused at the boundary.** Document names come from another
  app: `safeName` rejects empty, `.`, `..`, and anything containing `/` or `\`,
  before the name is used to build either a file or a directory path.
- **Project names are slugged** before becoming directory names, with tests that
  assert no slug can contain a separator or traverse upward.
- **Import is bounded**: 20k files, 512 MB total, 32 MB per file, with
  `node_modules`/`.git`/build outputs excluded — and it reports what it skipped
  instead of silently truncating.

---

## 12. Residual risks

Ordered by how much they should worry you.

| # | Risk | Why it remains | Mitigation |
|---|---|---|---|
| 1 | **Provider API keys are plaintext on disk** | Upstream writes `auth.json`; encrypting it means forking the auth path (§3) | App sandbox, no backup, non-debuggable release |
| 2 | **Config is a trusted input the agent can write** | `{file:}` interpolation into remote-config headers, plus `plugin` code loading, make it an exfiltration *and* persistence channel (§4) | None; constraining it is upstream behaviour |
| 3 | **A rooted or unlocked-with-adb device exposes everything** | Inherent — the app must decrypt without user interaction to run a background turn | None; document it |
| 4 | **The network security config does not cover the server** | The server is a native process with its own OpenSSL; the policy binds the Android stack only (§5) | Providers are HTTPS by upstream default; TLS verification untouched |
| 5 | **The agent can modify its own runtime bundle** | It executes code by design (§8) | Re-extraction on every install |
| 6 | **`connect-src https:`** | Remote-server mode needs a host unknown at build time (§7) | Tighten when remote mode retires |
| 7 | **`style-src 'unsafe-inline'`** | Upstream's theme preload injects varying CSS (§7) | CSS cannot reach the bridge |
| 8 | **Debug builds permit cleartext to any host and enable WebView debugging** | Deliberate, for a LAN dev server (ADR-0018) | CI asserts release differs; both checked in `verify-apk.sh` |
| 9 | **`SafeLog` redaction is regex-shaped** | A bare secret with no key would pass through (§10) | No call site does this today |
| 10 | **No R8/shrinking in release** | Deferred to M11 so rules can be written against real code | Not a confidentiality control |
| 11 | **Release APK is unsigned** | No keystore exists yet; signing is a release concern | M11 |
| 12 | **Git push with credentials never exercised** | Needs a real remote and a token | §9 |

Not risks, though they look like ones:

- **`INTERNET` is requested.** Providers are remote. A local-only server would
  not need it, but the model calls do.
- **`FOREGROUND_SERVICE_DATA_SYNC`.** Runs only while a turn is in flight
  (ADR-0027); measured at 0 instances while idle.

---

## 13. What CI now enforces

`scripts/ci/verify-apk.sh` gates the APK — the only artifact that reflects what
actually ships. Added in M10, each mutation-tested by deliberately breaking the
property and confirming the check fails:

| Check | Mutation tested |
|---|---|
| No forbidden permission | pre-existing |
| At most 2 exported components | exported a third → **[FAIL] unexpected exported components: 3** |
| `allowBackup="false"` | flipped to true → **[FAIL] allowBackup is not false** |
| Release is not debuggable | — |
| No blanket `usesCleartextTraffic` | — |
| **Release denies cleartext by default** | shipped the debug config → **[FAIL] release permits cleartext by default** |
| **The cleartext exception is loopback-only** | — |
| The packaged launcher does not bind `0.0.0.0` | — |

**Read the cleartext gates with §5 in mind.** They assert what the *Android*
stack will do. They say nothing about the server process, which is outside that
policy entirely.

`aapt2` is now located inside the SDK when it is not on `PATH`. Without that
these checks degraded to warnings, which reads like a pass and is not one.

---

## 14. Test coverage for security-sensitive helpers

| Helper | Tests |
|---|---|
| `SafeLog.redact` | key/value, header, URL-embedded credentials |
| `GcmCipher` / `KeystoreCipher` | round-trip, wrong key, tampered ciphertext, bad IV length, pre-encryption plaintext |
| `PreferenceStore` | values opaque on disk, key names readable, filename constraint |
| `ContentSecurityPolicy` | 11 tests — no `unsafe-inline`/`unsafe-eval` for scripts, no remote images, no arbitrary cleartext, closed defaults, exact hashing, the real packaged document |
| `Notifications.claimPosted` | 5 tests — unknown tag refused, posted tag accepted once, no cross-tag confusion, bounded memory |
| `RuntimeAssets` | 6 tests — re-extract on new install, no re-extract when unchanged, the same-version-name case that failed |
| `ProjectStore.slug` / `ProjectImport.safeName` | traversal, separators, unicode, truncation |
| Network security config | contents pinned, both build types |
| **Sandbox posture** (instrumented) | 4 tests — runtime binaries and `nativeLibraryDir` not writable (W^X), app storage not readable or writable by other apps, written files not world-accessible |

---

## 15. Not done

- **Encrypting `auth.json`** (§3). The largest remaining item, deferred with a
  written reason and a concrete proposal rather than silently.
- **Constraining what config may do** (§4) — `{file:}` interpolation into remote
  request headers, and `plugin` loading. Both are upstream behaviour; limiting
  them is an upstream conversation, not an app patch.
- **A live penetration test** — no attempt was made to install a second app on
  the device and probe the exported Activity or loopback port from it. The
  properties were established by reading the merged manifest and by socket and
  auth measurements, not by an adversarial app. **ASSUMED**, not **VERIFIED**.
- **Signing and R8** — M11.
- **A crash reporter.** There is none, so there is nothing to redact from crash
  diagnostics today. If one is added, its payload needs the same treatment as
  `SafeLog`.
