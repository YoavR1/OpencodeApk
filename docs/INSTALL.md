# Installing and using OpenCode for Android

A standalone OpenCode app. It runs a real OpenCode server **on the phone** — no
PC, no Termux, no external server needed for the core experience.

---

## 1. What you need

| | |
|---|---|
| **Android version** | 8.0 (API 26) or newer |
| **CPU** | **arm64-v8a** — every phone sold in the last decade |
| **Free storage** | ~250 MB (a 58 MB APK that unpacks a runtime) |
| **Free memory** | The server alone uses roughly 380 MB while running |
| **Permissions** | Internet, network state, notifications, foreground service — nothing else |

**32-bit-only devices (`armeabi-v7a`) are not supported** and the APK will not
install on them. `x86_64` is not shipped either; emulator use needs a build with
that ABI added.

No root. Ever. If something appears to need it, that is a bug.

## 2. Installing

Download the APK and open it. Android will ask you to allow installing from your
browser or file manager the first time — that permission is per-app and can be
turned off again afterwards.

To install over USB instead:

```bash
adb install app-release.apk
```

Upgrading in place keeps your projects and sessions. You only lose data if you
uninstall.

## 3. First run

The app starts its own OpenCode server on `127.0.0.1` and connects to it. The
home screen shows a green dot and `127.0.0.1:4096` when that has worked — this
takes about **3 seconds** on first launch.

To do anything useful you then need:

1. **A provider** — tap *Connect provider* and add an API key for Claude, GPT,
   Gemini or another supported provider. Some free models work without one.
2. **A project** — tap *Open project* or *Add project*. You can create an empty
   project or import a folder from your device.

## 4. Fullscreen

The app hides the status and navigation bars and uses the whole screen.

**Swipe from the top or bottom edge** to bring them back for a few seconds — to
check the time or the battery, or to use the gesture bar. They hide again on
their own; there is nothing to dismiss.

The back gesture works normally throughout.

## 5. What works

- Running a full OpenCode server on the device, on loopback, with authentication
- Creating and resuming sessions; sessions survive the app being closed
- Real projects on disk, with real filesystem paths the agent can work in
- **Git**: init, add, commit, status, diff, branch, log, and remotes over HTTPS
- Importing a folder through the Android file picker
- Continuing an agent turn while the app is in the background
- Connecting to a **remote** OpenCode server instead, if you prefer

## 6. Known limitations

| | |
|---|---|
| **No terminal** | There is no interactive shell or PTY. Commands the agent runs work; you cannot type into one yourself (ADR-0025). |
| **Landscape is tight** | The layout is portrait-first. Landscape works and scrolls correctly at any font size, but a short screen shows much less at once. |
| **Memory** | The server is a real Node process using ~380 MB. On a phone under memory pressure Android may kill it; the app restarts it, but an in-flight turn is lost. |
| **Remote servers must be HTTPS** | A plain-`http://` server on your LAN cannot be reached from the app's secure origin. Use HTTPS or a tunnel (ADR-0021). |
| **Provider keys are stored unencrypted** | They sit in the app's private storage, readable only by this app on an unrooted device — but not encrypted. See `docs/SECURITY.md` §3. |
| **arm64 only** | See §1. |

## 7. Troubleshooting

**The dot is red, or it says it cannot reach the server.**
The runtime failed to start or died. The app restarts it automatically within
about five seconds. If it keeps failing, you are most likely out of storage or
memory — free some and reopen the app.

**The app closed while I was waiting for a long answer.**
Android reclaims memory from backgrounded apps. A turn that is actually running
holds a foreground service with a visible notification, which protects it; if
that notification is not showing, the app did not think work was in flight.
Reopen the app — your session and project are on disk and will come back.

**Everything is huge.**
The app follows your system font size, including the largest settings. Content
stays reachable — panes scroll and button rows wrap — but a landscape screen at
2× text shows very little at once. Rotate to portrait, or reduce
*Settings → Display → Font size*.

**Git says it cannot find my identity.**
The app writes a default one (`OpenCode <opencode@localhost>`) the first time.
Change it from a session with `git config user.name` / `user.email`.

**Nothing loads — the screen is blank.**
That means the packaged UI is missing, which should not happen in a released
build. Reinstall; if it persists, it is a build defect worth reporting.

**I want to see what went wrong.**
```bash
adb logcat -s OpenCode:V
```
Some phone makers (OnePlus/Oplus among them) suppress third-party app logs from
`logcat` by default, so this may show nothing even when the app is logging.

## 8. Privacy and data

- Everything lives in the app's private storage. Nothing is backed up to the
  cloud or transferred to a new device — `allowBackup` is off.
- The on-device server listens on `127.0.0.1` only, with a password generated
  fresh each launch, so other apps on the phone cannot use it.
- Your code stays on the device except where you send it to an AI provider,
  which is the point of the app.
- The full threat review is `docs/SECURITY.md`.

## 9. Open source

OpenCode for Android bundles Node.js, Git, ICU, OpenSSL, curl, SQLite and others,
and redistributes them under their own licences — including **GPL-2.0** (Git) and
**LGPL-2.1** (GNU libiconv). The complete notice ships inside the app at
`assets/licenses/NOTICE.txt`, with full licence texts alongside it.

Obligations, source URLs and the written offer are in
[`docs/LICENSES.md`](LICENSES.md). Upstream OpenCode
([`anomalyco/opencode`](https://github.com/anomalyco/opencode)) is MIT.
