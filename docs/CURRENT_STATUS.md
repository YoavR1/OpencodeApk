# Current Status

> **This file is the authoritative state of the project.** Every session reads it
> first and updates it before finishing. If it disagrees with the repository, the
> repository is right — fix this file.

| | |
|---|---|
| **Last updated** | 2026-08-18 |
| **Session** | M3 shared OpenCode UI |
| **Branch** | `claude/opencode-android-bootstrap-o58939` |
| **Current milestone** | **M3 — complete except device verification** |
| **Next milestone** | **M4 — Android platform adapter / mobile UX** |
| **Next prompt** | **`prompts/04_MOBILE_PLATFORM_ADAPTER.md`** |

---

## Where the project actually is

**Upstream OpenCode is vendored into this repository, and the APK now contains the
real shared SolidJS application** rather than a placeholder. No screen has been
reimplemented in Kotlin.

No server of any kind is connected yet. That is M5 (remote) and M7 (on-device).

---

## The vendoring — VERIFIED

```
git remote add upstream https://github.com/anomalyco/opencode
git fetch upstream dev
git merge upstream/dev --allow-unrelated-histories
```

| | |
|---|---|
| Upstream pin | `4e81a0b` — the exact commit M0/M1 audited |
| Files added | 6,511 |
| Conflicts | **exactly two, both predicted**: `README.md`, `.gitignore` |
| Upstream files newly ignored by our patterns | 0 (checked) |
| Upstream root `package.json` edits | **none** — `packages/android` falls inside the existing `packages/*` glob |

**The M1 divergence measurement held.** Actual upstream source divergence:

```
 packages/app/src/context/platform.tsx | 3 ++-
 1 file changed, 2 insertions(+), 1 deletion(-)
```

`PlatformName` gains `"android"`, and the exported `Platform` union gains
`| { platform: "android"; os?: never }`. That is the entire change to upstream
code.

### One thing M1 did not predict

The first fetch used `--depth=1`, which made the repository shallow and got the
push rejected:

```
remote: fatal: did not receive expected object 32320409b17c4dff6af14b300a9e7ef3ba6ba76b
error: remote unpack failed: index-pack failed
```

Vendoring by merge needs full upstream history. `git fetch --unshallow upstream dev`
fixes it (`.git` grew from 80 MB to 326 MB). Recorded in `docs/UPSTREAM_SYNC.md`
so a future bump does not repeat it.

## What was built

| Path | What it is |
|---|---|
| `packages/android/` | `@opencode-ai/android` — the renderer package |
| `…/src/main.tsx` | Entry; composes `PlatformProvider` → `AppBaseProviders` → `AppInterface`, mirroring `packages/desktop/src/renderer/index.tsx` |
| `…/src/platform.ts` | The Android `Platform` implementation |
| `…/src/capabilities.ts` | SUPPORTED / DEGRADED / UNSUPPORTED, each with a reason |
| `…/src/platform.test.ts` | 12 adapter contract tests |
| `…/vite.config.ts` | Own vite root using `@opencode-ai/app/vite`, exactly as desktop does |
| `apps/android/…/web/AndroidHostBridge.kt` | The minimal native bridge |

## Capability honesty — requirement 4

Capabilities live in three explicit categories, because "the method is missing"
and "the method exists but does nothing" are different failures:

| Category | Meaning | Expression |
|---|---|---|
| **SUPPORTED** | Works | `version`, `openExternal`, `restart`, `fetch` |
| **DEGRADED** | Required by the type, not yet functional | `notify` — named, with the milestone that fixes it |
| **UNSUPPORTED** | Not provided | **left `undefined`** — 26 members, each with a reason |

Leaving a member `undefined` *is* how you say unsupported here, because upstream
already guards every optional capability (`!!platform.openPath`). A stub that
resolved silently would make the shared UI believe the action succeeded.

Tests assert all of it, including that a future "fix" adding no-op stubs would
fail the suite.

**`notify` is the one DEGRADED member.** Android WebView exposes no Notification
API, and native notifications need `POST_NOTIFICATIONS`, a channel and a click
route — all M4. `Platform` requires `notify`, so it cannot be omitted; it is
named instead of quietly doing nothing.

## Two bugs this milestone found before they shipped

**1. A silently blank APK.** The first Gradle wiring used a `Sync` task with a
`doFirst` guard. A `Sync` whose source directory is missing is skipped as
NO-SOURCE, so the guard never ran and the build **succeeded while producing an
APK with no web content at all**. The check now lives in its own always-running
task, and `verify-apk.sh` additionally asserts `assets/web/index.html` is really
inside the APK.

**2. The asset origin was wrong.** M1 sketched serving the UI from an `/assets/`
sub-path. But `packages/app/src/index.css` loads its fonts with root-absolute
URLs — `url("/assets/Inter.ttf")` — and vite emits its own chunks under
`/assets/` too. A sub-path would have 404'd every one of them, and **a missing
font falls back silently**, so this would have shipped looking almost right.

The app is now served from the origin root, matching the web build, with a small
custom `PathHandler` re-prefixing into `assets/web/` so the APK stays tidy.
`build-shared-ui.sh` now asserts every root-absolute reference in `index.html`
resolves inside `dist`, and that both font files came through.

---

## CI — VERIFIED

Run [32143575388](https://github.com/YoavR1/OpencodeApk/actions/runs/32143575388) on `f64027f8e`.

**The shared OpenCode UI builds in CI** — `Build shared OpenCode UI` succeeded in
42 seconds, producing the real SolidJS bundle that goes into the APK.

CI now does, in order: install with a frozen lockfile → build the shared UI →
install the Android SDK → `lintDebug testDebugUnitTest assembleDebug` → verify the
APK → upload `opencode-android-debug`.

### The lockfile, and why editing it was correct

The previous run failed with:

```
error: lockfile had changes, but lockfile is frozen
```

That was **not** the proxy — CI resolved all 545 packages in five seconds. Adding
`packages/android` to the workspace changes `bun.lock` **by definition**, and no
updated lockfile had been committed because a full local install is impossible in
this environment (below).

The entry was added by hand: eleven additive lines naming the package and its
`devDependencies`, every one of which already resolved elsewhere in the lockfile
as a workspace or catalog entry, so nothing new needed resolving.

This is not the silent lockfile migration `CLAUDE.md` §6 forbids. That rule is
about rewriting the lockfile to dodge an error. Adding a workspace package
*requires* a lockfile change, and `--frozen-lockfile` in CI is exactly the check
that proves the hand-written entry right or wrong.

### Typecheck scope

`check-opencode.sh` runs oxlint over the whole repository, and typecheck **scoped
to `@opencode-ai/android` and `@opencode-ai/app`** — the packages this project
owns or modifies. Typechecking all ~30 upstream packages verifies upstream's code
at upstream's own commit; upstream's CI already does that, and this project could
not fix a failure there. `TYPECHECK_ALL=1` runs the lot, which is what an upstream
bump should use. The reasoning is written into the script, not left implicit.

---

## BLOCKED — what this environment cannot do

**A full local `bun install` is not possible here.** Measured:

| Endpoint | Result |
|---|---|
| `api.github.com/rate_limit` | 200 |
| `api.github.com/repos/…/tarball/<sha>` | **403** |
| `codeload.github.com/…/tar.gz/<sha>` | **403** |
| `registry.npmjs.org` | 200 |
| `pkg.pr.new` | 200 |
| `git ls-remote` on the same repo | **OK** |

The proxy blocks GitHub *archive downloads*, which is exactly how Bun resolves the
one `github:` dependency in the workspace, `ghostty-web` (a direct dependency of
`packages/app`). Git protocol works; only archives are blocked.

With that dependency temporarily repointed at a local clone, `bun install` then
ran ~30 minutes without creating `node_modules` and was killed. The override was
reverted; `bun.lock` and `packages/app/package.json` are byte-identical to
upstream.

**Consequence:** the shared UI bundle cannot be built in a cloud session. CI is
the authority for it, exactly as `CLAUDE.md` §6 prescribes. Everything else —
Kotlin compilation, unit tests, lint, APK packaging, `verify-apk.sh` — *was*
validated locally against a stand-in bundle shaped like a real vite build.

---

## Not done — BLOCKED

| Item | Why |
|---|---|
| **The UI confirmed rendering on a real phone** | Needs a device report. CI proves it builds and is packaged; only a device proves it renders. |
| Navigation confirmed on device | Same. |
| Instrumented tests executed | Four written; no emulator job yet. |
| `notify` working natively | DEGRADED by design until M4. |
| Any server connection | M5 (remote), M7 (on-device). |

## What I need from you

Download **`opencode-android-debug`** from the CI run, install it, and report:

1. Does the **real OpenCode UI** appear — not a placeholder page?
2. Can you navigate between views?
3. Does text render in OpenCode's own fonts, or a generic system fallback?
   (A fallback means the font URLs are still wrong — the exact bug described above.)
4. Anything obviously broken in the mobile viewport?
5. Phone model and Android version.

Until then M3's device criteria stay **BLOCKED**.

---

## Decisions this session

No new ADRs. M3 executed ADR-0002 (vendor upstream), ADR-0004 (use upstream's
boundaries), ADR-0008 (app origin, now corrected to the origin *root*), and
ADR-0010 (`packages/android` + `apps/android`).

`docs/UPSTREAM_SYNC.md` moves D1, D2, D4, D5 to **Applied**. D3
(`persist.ts` identity → capability check) is still pending; it is only needed
once Android supplies `storage`, in M4.

## Recommended next session

Paste **`prompts/04_MOBILE_PLATFORM_ADAPTER.md`**.

M4 is where the bridge becomes the typed `WebMessagePort` channel and the
DEGRADED and M4-tagged UNSUPPORTED capabilities get real implementations:
`storage`, `draftStore`, `notify`, `getDefaultServer`/`setDefaultServer`, and SAF
directory picking — plus the mobile UX work (touch targets, keyboard insets, back
behaviour). Divergence D3 lands there too.
