# Current Status

> **This file is the authoritative state of the project.** Every session reads it
> first and updates it before finishing. If it disagrees with the repository, the
> repository is right — fix this file.

| | |
|---|---|
| **Last updated** | 2026-08-18 |
| **Session** | M4 mobile platform adapter |
| **Branch** | `claude/opencode-android-bootstrap-o58939` |
| **Current milestone** | **M4 — code complete, device verification outstanding** |
| **Next milestone** | **M5 — remote server connection** |
| **Next prompt** | **`prompts/05_REMOTE_SERVER_MODE.md`** |
| **CI** | ✅ green — [run 32154997649](https://github.com/YoavR1/OpencodeApk/actions/runs/32154997649) |

---

## Where the project actually is

The APK contains the real shared OpenCode UI (M3), and as of M4 the renderer can
reach native Android capabilities through a typed bridge: preferences, prompt
drafts and their blobs, clipboard (text and images), sharing, external links,
notifications with a click route, a SAF directory picker, app lifecycle, keyboard
insets, and back.

No screen has been reimplemented in Kotlin. No server of any kind is connected —
that is M5 (remote) and M7 (on-device).

---

## What M4 built

### The bridge (ADR-0013)

M3's `addJavascriptInterface` object is gone, replaced by one `WebMessagePort`
pair carrying JSON envelopes. 22 methods, 4 events.

| Piece | File |
|---|---|
| Renderer client | `packages/android/src/bridge.ts` |
| Wire format + parser | `apps/android/…/bridge/BridgeContract.kt` |
| Dispatch | `apps/android/…/bridge/BridgeHost.kt` |
| Transport | `apps/android/…/web/BridgePort.kt` |

The method list exists in two languages that cannot import each other, so
`packages/android/src/contract.test.ts` reads both files and fails on drift in
either direction. It was mutation-checked: renaming one Kotlin entry fails two
tests with the exact names on both sides.

### Capabilities

`notify` was the last DEGRADED entry and is now SUPPORTED, so **`DEGRADED` is
empty**. Eleven capabilities are implemented; the rest stay `undefined` with a
recorded reason. Full matrix in `docs/ARCHITECTURE.md` 2.3a.

`Notifications.post` returns `false` when `POST_NOTIFICATIONS` is refused, and the
adapter drops the click handler in response — it does not report a notification it
did not post.

### Mobile layout (ADR-0016) — reuse, not reimplementation

Reading the shared UI first changed what this milestone had to build. Upstream
**already has** a mobile layout, on by default: a 767 px breakpoint, an off-canvas
drawer (`layout.mobileSidebar`), a bottom-titlebar option, and a `hover-reveal`
utility that escapes hover-only reveals through `@media (hover: none)`.

So M4 made that path apply rather than writing a second one:

- `width=device-width` is present, so the breakpoint fires (without it a WebView
  reports ~980 CSS px and the entire mobile path stays dormant);
- the titlebar defaults to the **bottom** on Android, for one-handed reach (D6);
- an `@media (hover: none)` rule in `packages/android/src/styles.css` reveals the
  four controls that open-code `opacity-0 group-hover:opacity-100` instead of
  using upstream's own utility — including the remove button on a pasted image
  attachment, which is otherwise invisible and unreachable on a phone.

The back gesture, keyboard insets and focus handling are described in
`docs/ARCHITECTURE.md` 2.4 and 2.4a.

---

## Bugs this milestone found

**1. Back exited the app from any screen — a live defect shipped in M2.** The
handler asked `webView.canGoBack()`. The renderer uses a *memory* router
precisely so back does not navigate the document, so the WebView's history is
always empty, `canGoBack()` is always false, and every back press exited,
whatever was on screen.

Back is now decided by the renderer, which owns the dialog, drawer and route
state (ADR-0014), with a 400 ms host-side timeout so a wedged web app can never
trap the user in it.

**2. An unsolicited bridge message could close the app.** `BackCoordinator` used
`0` as its "nothing pending" sentinel while comparing it against the reply's
token. Web content can call `back.handled` whenever it likes, so a reply carrying
token `0` matched "nothing outstanding" and exited. Found by a unit test written
for exactly that class of input, before any of it ran on a device.

**3. `BridgePort` used two WebView features it never checked.** `connect()` gated
on three; `send()` and `disconnect()` then called `postMessage` and `close`,
gated on nothing. All five are now checked in one place, before the channel is
established — a WebView missing any of them gets no bridge rather than a
half-working one.

**4. Lint caught a real permission race.** `NotificationManagerCompat.notify` was
called behind a `permitted()` helper that lint cannot follow. The permission can
genuinely be revoked between the check and the call, so the `SecurityException` is
now caught by name rather than swept up by `runCatching`.

---

## Verification — what actually ran

**CI — VERIFIED GREEN.** Run
[32154997649](https://github.com/YoavR1/OpencodeApk/actions/runs/32154997649) on
`b4f9e113b`, all five jobs:

```
detect phase                                          success
repo hygiene                                          success
opencode workspace checks                             success
  Run safe workspace checks (lint + typecheck)        success
  Shared UI builds                                    success
  Android adapter tests                               success
android build                                         success
  Build shared OpenCode UI                            success   (53s)
  Build (lintDebug, testDebugUnitTest, assembleDebug) success   (94s)
  Verify APK                                          success
  Upload debug APK                                    success
android build (pre-M2 phase)                          skipped
```

| Artifact | Size |
|---|---|
| **`opencode-android-debug`** | **15,942,362 bytes** (15,582,286 at M3) |

Locally, against a stand-in bundle shaped like a real vite build:

```
./gradlew lintDebug testDebugUnitTest assembleDebug      BUILD SUCCESSFUL
  lint          0 errors, 3 warnings
  unit tests    60 tests, 0 failures
  APK           app-debug.apk produced

bun test --cwd packages/android                          72 pass, 0 fail
```

The three remaining lint warnings are inherent and deliberate: `targetSdk 36` vs
`compileSdk 37`, the API-33 `enableOnBackInvokedCallback` attribute we opt into,
and `setJavaScriptEnabled` — which is the entire premise of a WebView shell.

| Layer | M3 | M4 |
|---|---|---|
| Kotlin unit tests | 15 | **60** |
| Renderer tests | 12 | **72** |

### Two CI failures, both mine

The first push failed the typecheck: `platform.ts` imported
`@solid-primitives/storage`, which `packages/app` depends on and
`packages/android` does not. It now reads the type back off the boundary
(`ReturnType<NonNullable<Platform["storage"]>>`), which needs no new dependency
and no lockfile change. The second was the same TS7006 class: the derived type
is a union, and TypeScript does not reliably infer function parameters through a
union contextual type, so those parameters are now annotated.

Neither was visible locally, and the reason is worth recording: `node_modules`
cannot be installed here, so *every* import in the workspace is unresolvable and
a real missing dependency looks exactly like the ambient noise. The local
typecheck carries no signal at all — CI is the only authority for it.

---

## BLOCKED — what this environment cannot do

**A full local `bun install` is not possible here**, unchanged from M3. The proxy
returns 403 for `api.github.com/…/tarball` and `codeload.github.com`, which is how
Bun resolves the workspace's one `github:` dependency (`ghostty-web`, a direct
dependency of `packages/app`). Git protocol works; only archives are blocked.

**Consequence: the shared UI bundle cannot be built or run in a cloud session.**
CI is the authority for it, exactly as `CLAUDE.md` §6 prescribes.

**What this costs M4 specifically.** Every renderer test in this milestone
exercises adapter and protocol logic — bridge framing, back policy, focus
predicates, contract drift. **None of them renders a component.** The
mobile-layout work is therefore reasoned from upstream's source, not observed:

- that the 767 px breakpoint activates in the WebView,
- that the bottom titlebar looks right and is actually reachable,
- that the hover override reveals the controls it targets,
- that the keyboard neither occludes the prompt nor double-insets it.

These are the claims to check on a device first. D6 in particular turns on a
layout upstream still gates behind a non-prod channel in its own settings UI; it
changes a default only, and Settings can flip it back.

---

## Not done

| Item | Why |
|---|---|
| **Mobile layout confirmed on a phone** | Cannot be built here; see above. The main open M4 risk. |
| Instrumented tests executed | Four written; no emulator job yet. |
| Long-press / context actions | Not implemented. Upstream has no touch context-menu affordance to reuse, and inventing one is a bigger change than M4 should make blind. |
| Attachment and save-file pickers | M8 — `capabilities.ts` names them. |
| Credentials in the Keystore | M10. Preferences are deliberately unencrypted and must not hold secrets (ADR-0015). |
| Any server connection | M5 (remote), M7 (on-device). |

---

## What I need from you

Download **`opencode-android-debug`** from the CI run for this branch, install it,
and report:

1. Does it lay out as a **phone app** — or as a desktop page scaled down?
2. Is the titlebar at the **bottom**, and are its controls thumb-reachable?
3. **Back**: does it close an open dialog, then the drawer, then go back a screen,
   and only then leave the app? Does it ever feel stuck or exit too early?
4. Open the keyboard in the prompt. Does the input stay visible, and is anything
   inset twice or hidden behind it?
5. Can you open the drawer, navigate, and return?
6. Phone model and Android version.

Question 3 matters most: the back policy is unit-tested but its 400 ms timeout has
never been observed against a real renderer under load.

---

## Decisions this session

Four new ADRs in `docs/DECISIONS.md`:

| ADR | Decision |
|---|---|
| **ADR-0013** | The bridge is a `WebMessagePort`, not `addJavascriptInterface` |
| **ADR-0014** | Back is decided by the renderer, with a host-side timeout |
| **ADR-0015** | Preferences are not encrypted, and credentials will not live in them |
| **ADR-0016** | Mobile layout reuses upstream's own responsive path |

`docs/UPSTREAM_SYNC.md`: **D3 applied** (`persist.ts`, identity check → capability
check) and **D6 added** (Android defaults the titlebar to the bottom). Upstream
source divergence is now 3 files, +17/−10 lines. D3 is worth proposing upstream;
D6 is deliberately platform-keyed and is not.

## Recommended next session

Paste **`prompts/05_REMOTE_SERVER_MODE.md`** — but read the device report first.

M5 connects the UI to a real OpenCode server over the network, which is the first
point the app does something rather than only presenting itself. It also settles
**Q9**: whether SSE over `fetch` + `ReadableStream` works reliably in Android
WebView. If the device report from M4 shows layout problems, fix those first —
M5 assumes the app is navigable.
