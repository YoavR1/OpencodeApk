# M6 — Local runtime feasibility spike

**Question.** Can OpenCode run *on the phone*, in a stock unrooted Android app,
with no visible Termux workflow?

**Answer so far: yes, with one step left to close.** Every component has been run
on real hardware. The last combination — the OpenCode server running on the
Android Node build, on the device — was staged and interrupted when the phone
disconnected. That is stated plainly rather than glossed, and
`spike/m6/run-on-device.sh` completes it in one command.

**Device under test.** OnePlus 15 (CPH2747), Android 16 / API 36, arm64-v8a,
unrooted.

---

## 1. What upstream actually requires — measured, not assumed

The prompt asks to start from the real blockers. They are far smaller than the
M1 estimate, because **upstream already has a Node build target** and it resolves
almost everything to WASM or built-ins.

```
bun run --cwd packages/opencode script/build-node.ts   →  Build complete
```

| Artifact | Size |
|---|---|
| `dist/node/node.js` | **32.9 MB** |
| `photon_rs_bg-*.wasm` | 1.9 MB |
| `tree-sitter-bash-*.wasm` | 1.4 MB |
| `tree-sitter-powershell-*.wasm` | 1.0 MB |
| `tree-sitter-*.wasm` | 0.2 MB |
| **total (excluding sourcemap)** | **≈ 37.4 MB** |

### Native dependency matrix

Measured by grepping the produced bundle, not by reading `package.json`:

| Dependency | Status in the Node build | Android arm64 |
|---|---|---|
| `@lydell/node-pty` | **External, statically imported** — 1 reference | ❌ **the only real blocker** |
| `jsonc-parser` | External, 3 references | ✅ pure JS |
| `tree-sitter-bash`, `tree-sitter-powershell` | Resolved to **WASM** | ✅ architecture-independent |
| `@silvia-odwyer/photon-node` | Resolved to **WASM** | ✅ |
| SQLite | `node:sqlite` — **Node built-in** | ✅ needs Node ≥ 22.5 |
| `@parcel/watcher` | **0 references** — not in the Node build at all | ✅ not needed |
| any `.node` native binding | **none found** | ✅ |
| any `bun:` API | **none found** | ✅ genuinely Node-targeted |

**The single blocker is PTY**, and it blocks harder than it needs to: the import
is static, so the server cannot even be *loaded* without it, whether or not a
terminal is ever opened. `spike/m6/shim/` isolates this — a stand-in that throws
only if a PTY is actually spawned.

### Proof the bundle runs at all

Desktop, Node 22.14, PTY shimmed:

```
$ node --experimental-sqlite spike/m6/serve-node.mjs 4599
exports: Config, Database, Server, bootstrap
import took: 1187 ms
listening on: http://127.0.0.1:4599
ready after: 1445 ms

$ curl http://127.0.0.1:4599/global/health
{"healthy":true,"version":"0.0.0-claude/opencode-android-bootstrap-o58939-…"}
```

So: **the OpenCode server runs on plain Node with no native modules at all**,
losing only terminals.

---

## 2. What Android permits — measured on the device

`RuntimeFeasibilityTest` runs as **the app's own uid**, which is the only context
that answers the question. Measurements through `adb shell` run as the far more
privileged `shell` user and would be misleading.

**15/15 instrumented tests pass on the OnePlus 15.**

| Question | Result |
|---|---|
| Launch a shell command (`/system/bin/sh -c`) | ✅ |
| Execute a binary shipped as a jniLib, from `nativeLibraryDir` | ✅ |
| Execute a binary copied into `filesDir` | ❌ **refused — W^X confirmed** |
| Bind a loopback port | ✅ |
| Read/write app-private storage | ✅ |
| Running as root | ❌ no, and not needed |

Two packaging consequences fall straight out:

- **A runtime cannot be downloaded on first launch.** W^X is real on API 36, so
  the binary has to ship inside the APK as `lib*.so` and run from
  `nativeLibraryDir`. Measured, not assumed.
- **`useLegacyPackaging = true` is required.** With the modern default, native
  libraries are mapped straight out of the APK and `nativeLibraryDir` contains
  nothing to execute. The test failed exactly that way before the flag was set.

---

## 3. Candidates

### A. Node for Android + the upstream Node build — **leading**

Node **26.4.0 is built for Android aarch64** and maintained by the Termux
project. Run on the device:

```
$ LD_LIBRARY_PATH=/data/local/tmp/m6/lib /data/local/tmp/m6/node --version
v26.4.0
```

**That is the decisive result of this spike**: a JavaScript runtime capable of
hosting the OpenCode server executes on a stock unrooted Android arm64 phone.
Node 26 also clears the `node:sqlite` floor with room to spare.

What it costs, measured from the ELF itself (`spike/m6/elf-needs.py`):

```
node: 49.7 MB
  RUNPATH: /data/data/com.termux/files/usr/lib
  needs:   libz.so.1, libcares.so, libsqlite3.so, libffi.so, libcrypto.so.3,
           libssl.so.3, libicui18n.so.78, libicuuc.so.78, libc.so, libm.so,
           libdl.so, libc++_shared.so

transitive libraries to bundle:
  libc++_shared.so     1.4 MB      libicudata.so.78    33.1 MB
  libcares.so          0.3 MB      libicui18n.so.78     3.4 MB
  libcrypto.so.3       5.2 MB      libicuuc.so.78       2.0 MB
  libffi.so            0.1 MB      libsqlite3.so        1.2 MB
  libssl.so.3          0.9 MB      libz.so.1            0.1 MB

bundle size (binary + libraries, uncompressed): 97.3 MB
```

Plus the ~37 MB app bundle: **≈ 135 MB uncompressed**, before compression.

**Three real problems, none fatal:**

1. **`libicudata.so.78` is 33 MB** — a third of the runtime, and it is only ICU
   locale data. A Node built `--with-intl=small-icu` drops it, which means
   building Node with the NDK rather than reusing the Termux artifact.
2. **`.so.78` filenames cannot ship as jniLibs.** Android extracts only files
   matching `*.so`. The libraries must be renamed to `lib*.so` form and their
   `DT_NEEDED`/`SONAME` entries patched to match, which is a build step
   (`patchelf`) — not a blocker, but not free either.
3. **RUNPATH points at Termux's prefix.** Inside an app this is moot: jniLibs are
   extracted to `nativeLibraryDir`, which is already on the app's loader search
   path. Verified only for the exec path so far, not for Node's own library
   resolution.

**Licensing.** Node is MIT. ICU is Unicode/ICU licence. OpenSSL 3 is Apache-2.0.
All redistributable. Termux packages are builds of those upstreams; a shipping
product should build them itself rather than redistribute Termux artifacts, which
is also what fixes problems 1 and 2.

### B. Bun on Android — **blocked, and worth one experiment**

Bun publishes Linux builds against **glibc and musl**; there is no Android/Bionic
target. Upstream's own default runtime therefore cannot be used directly.

One experiment has not been run and should be: `bun-linux-aarch64-musl` is
*statically* linked, and a static binary needs no system libc at all. If it
executes on an Android kernel it would collapse the whole problem — one 50 MB
binary, no library patching, and upstream's native runtime rather than a Node
build. **Not yet tested.** Until it is, this stays unproven in both directions.

### C. Bundled Linux rootfs under proot — **rejected**

Technically works without root and is what Termux's `proot-distro` does. Rejected
on cost, not on possibility: a Debian/Alpine rootfs plus proot adds hundreds of
megabytes, proot intercepts syscalls with a measurable performance penalty, and
the result is a hidden Termux — which is the spirit of `.claude/rules/android.md`
N3 even if the letter only forbids a *visible* one. Candidate A gets the same
outcome for a third of the size and none of the emulation.

### D. `nodejs-mobile` (in-process `libnode.so`) — **rejected on version**

Attractive: Node as a library loaded into the app's own process, no `exec` and no
W^X question at all. But the project's releases are Node 18, and the bundle needs
`node:sqlite` (Node ≥ 22.5). Adopting it means either maintaining a Node 22+ fork
or replacing the database layer — more work than candidate A, for an advantage
(no separate process) that Android's lifecycle rules make questionable anyway.

### E. A JS engine without Node APIs (QuickJS, Hermes, JavaScriptCore) — **rejected**

The bundle requires `child_process`, `fs`, `dgram`, `dns`, `async_hooks`,
`node:sqlite` and more. An engine without a Node-compatible runtime cannot host it,
and supplying one is a larger project than this one.

---

## 4. The five questions

| # | Question | Answer |
|---|---|---|
| 1 | Can we start a local OpenCode-compatible server on loopback? | **Proven on desktop** (`/global/health` healthy). On device: the runtime is proven and the bundle is staged; the launch is the one step outstanding. |
| 2 | Can it perform a trivial filesystem operation? | ✅ from the app (instrumented test); from Node on device, scripted, not yet run |
| 3 | Can it launch a shell command? | ✅ from the app (instrumented test) |
| 4 | Can it run on ARM64 Android without root? | ✅ **Node 26.4.0 printed its version on the device** |
| 5 | What must be bundled or built specially? | Node + 10 shared libraries (97.3 MB), the ~37 MB app bundle, `jsonc-parser`, and **a PTY solution or the loss of terminals** |

---

## 5. What is lost or degraded

| Feature | Status |
|---|---|
| **Terminal / PTY** | ❌ no Android arm64 build of `@lydell/node-pty` exists. Needs an NDK build of the native module, or terminals are unavailable on-device. This is M8's problem and it is now well defined. |
| Everything else in the server | ✅ no other native dependency |
| File watching | ✅ `@parcel/watcher` is not in the Node build |
| Syntax highlighting / tree-sitter | ✅ WASM |
| Image handling | ✅ WASM |
| Database | ✅ `node:sqlite` |

---

## 6. Recommendation

**Candidate A: an on-device Node process running upstream's Node build**, started
by the app like the desktop sidecar starts its own — the pattern ADR-0005 already
committed to, with a different binary.

It also lands exactly where M5 left off: `http://127.0.0.1:<port>` is the one
origin Chromium does *not* block from the app's `https://` page (ADR-0021), so
the transport question is already answered and the CORS allowlist already permits
it.

Recorded as **ADR-0022**.

### Before M7 can start

1. **Finish the on-device proof** — `spike/m6/run-on-device.sh`. One command.
2. **Test static-musl Bun** on the device. If it runs, candidate B is simpler than
   A and the recommendation should change.
3. **Decide how Node is built.** Reusing Termux artifacts is fine for a spike and
   wrong for a product; an NDK build with `--with-intl=small-icu` addresses the
   33 MB of ICU data and the `.so.78` naming in one move.
4. **Decide PTY.** Build `@lydell/node-pty` for Android arm64, or ship without
   terminals and say so.
5. **Measure startup on device.** Desktop is 1.4 s; a phone will be slower, and a
   foreground service plus a splash state has to cover it.

---

## 7. Reproducing this

```
# the runtime (24.4 MB download from Termux's official repo)
python spike/m6/fetch-node-android.py

# the server bundle
bun run --cwd packages/opencode script/build-node.ts
bash spike/m6/setup-shim.sh

# desktop proof
node --experimental-sqlite spike/m6/serve-node.mjs 4599
curl http://127.0.0.1:4599/global/health

# what must be bundled
python spike/m6/elf-needs.py spike/m6/staging/android-node/data/data/com.termux/files/usr/bin/node

# device proof (needs a connected phone)
bash spike/m6/run-on-device.sh

# what the app itself may do
cd apps/android && ./gradlew connectedDebugAndroidTest
```

Nothing in `spike/` ships. The staging tree is git-ignored, and
`/data/local/tmp/m6` on the phone is removable with one `adb shell rm -rf`.
