# Android lifecycle and resilience

Android destroys and recreates things for reasons the user never sees, and kills
processes without warning. This is what the app does about it, what was measured
rather than assumed, and what to check on a real phone.

---

## 1. The state machine

```
                    ┌──────────────────────────────────────────────┐
                    │                  PROCESS                     │
                    │  OpenCodeApplication owns the runtime        │
                    │                                              │
   launch ─────────▶│  stopped ──▶ starting ──▶ ready ──▶ stopping │──▶ stopped
                    │                 │           │▲               │
                    │                 │           ││               │
                    │              failed      degraded            │
                    │                            (not answering)   │
                    └──────────────────────────────────────────────┘
                                       │
        Activity recreated ────────────┤ runtime untouched
        App backgrounded ──────────────┤ runtime untouched; service held only if busy
        App process killed ────────────┴─▶ runtime exits on stdin EOF
```

`degraded` is deliberately separate from `failed`: a server that started and then
stopped answering is recoverable and worth retrying; one that never started is
not, and showing one message for both would be wrong.

## 2. What survives what

| Event | Runtime process | Server state | UI state |
|---|---|---|---|
| **Activity recreation** (locale, theme, "don't keep activities") | survives — owned by the process, not the Activity | untouched | route restored from storage; WebView state restored |
| **Backgrounding** | survives; a foreground service holds the process **only while a turn is running** | untouched | restored on return |
| **App process death** (LMK, force-stop, swipe-away) | **exits** — see §4 | on disk, in `filesDir/opencode` | route and drafts restored from encrypted storage |
| **Runtime process death** | watchdog reports `failed` within 5s | on disk | UI is told; a later request restarts the runtime |

The reason ownership sits on `Application` rather than the Activity is not
theoretical. Each `LocalRuntimeController` mints its **own per-launch password**,
so a second one would have a credential the running server does not accept, and
the first runtime's watchdog dies with the Activity's scope, leaving it
unmonitored.

Measured on a OnePlus 15 (Android 16): a recreation did *not* in fact leave two
servers running, because the renderer's handle survived and nothing asked again.
That is luck rather than design — the invariant should not depend on which of two
things happens first — so ownership was moved regardless.

## 3. Clean shutdown

`stop()` moves through `stopping → stopped`: the watchdog is cancelled, the
process is asked to terminate, and it is given three seconds to close SQLite
properly before being killed outright. The launcher stops the HTTP listener
before exiting so nothing is half-written.

## 4. Orphans

A server that outlives its app is unreachable, holds a port and owns a database
nobody can talk to.

Android usually kills the whole process group with the app — and on the test
device it does: after `am force-stop`, the runtime was gone and port 4096 was
free. But "usually" is not a guarantee across OEMs and kill paths, so the
launcher does not rely on it. The host holds the child's **stdin** open for
exactly as long as it lives; EOF on that pipe means the app is gone, whatever way
it went, and the runtime shuts down on it.

That is why `EmbeddedProcessRuntime` must never close `process.outputStream` —
doing so would look like tidying up and would kill the runtime.

## 5. Battery

The foreground service runs **only while a session is mid-turn**, never for the
life of the app. The signal comes from the server's own `/session/status` rather
than from the UI: the server is the authority on whether work is in flight, and
asking it needs no coupling to upstream's internals and no second signal to keep
in sync.

It costs no extra wakeups — the check rides on the health poll that already runs
every five seconds. Unreadable or unexpected responses read as **not busy**,
because holding the process awake on a failed request is the wrong way to be
wrong. The service is `START_NOT_STICKY`: if Android kills the process mid-turn
the turn is already lost, and restarting a service with no UI and no work would
just burn battery.

## 6. Automated coverage

| Behaviour | Test |
|---|---|
| One server per process; concurrent callers join one start | `LocalRuntimeControllerTest` |
| A failed start does not cancel the Activity's scope | `LocalRuntimeControllerTest` |
| A failure is not cached; the next call retries | `LocalRuntimeControllerTest` |
| Stop clears the start so a later call restarts | `LocalRuntimeControllerTest` |
| Busy detection: idle, running, unknown states, unreadable input | `SessionActivityTest` |
| States are stable, distinct, and never carry the password | `RuntimeStateTest` |
| Start → serve → stop, and really gone afterwards | `RuntimeStartupTest` (device) |

## 7. Physical-device checklist

Automated tests cannot force a low-memory kill, and an emulator's behaviour is
not the OEM's. Run these on a real phone after touching anything in this
document.

**Setup:** install the debug APK, open the app, wait for the UI to connect.

| # | Do this | Expect |
|---|---|---|
| 1 | `adb shell ps -A \| grep libnode` | exactly **one** runtime, parent = the app |
| 2 | Rotate the device several times | UI intact; still one runtime; same pid |
| 3 | Enable *Developer options → Don't keep activities*, background and reopen | UI restored; still **one** runtime; app pid unchanged |
| 4 | Background the app for 5 minutes with no turn running | no notification in the shade; runtime may or may not survive — either is correct |
| 5 | Start a long turn, background immediately | notification "OpenCode is working" appears; turn completes |
| 6 | When the turn finishes | notification disappears within ~5s |
| 7 | `adb shell am force-stop ai.opencode.android` | **no** `libnode` process remains; the port is free |
| 8 | Swipe the app away from recents, then `ps` | same as 7 |
| 9 | Reopen after any of the above | previous route restored; projects and sessions intact |
| 10 | `adb shell run-as ai.opencode.android kill -9 <runtime pid>` while the app is open | UI notices within ~5s, restarts the runtime, and comes back `ready` |

`adb shell kill -9` on an app-owned process returns *Operation not permitted* —
the shell user cannot signal it. `run-as` is the working form, and the failure is
worth knowing about: it looks like the test passed when nothing was killed.

Record results in `docs/CURRENT_STATUS.md` with the device and Android version —
"it worked on a Pixel" is not evidence about a OnePlus, and this project has
already been caught out by one OEM's logging behaviour.

## 8. Results — OnePlus 15, Android 16, 2026-08-19

Run against the debug APK, one session ("Big Pickle") open in project `tmp`.

| # | Result | Evidence |
|---|---|---|
| 1 | pass | `30697 25646 libnode.so` — one runtime, parent = the app |
| 2 | pass | configuration changes ×3: app pid and runtime pid both unchanged |
| 3 | pass | *don't keep activities* + background/reopen: `app=23952`, still one runtime `29671` |
| 4 | pass | 20 minutes idle in the background: **0** `RuntimeService` in `dumpsys activity services` |
| 5 | **not run** | needs a real turn, which needs a provider credential |
| 6 | **not run** | same |
| 7 | pass | after `am force-stop`: no `libnode`, port 4096 returns `000` |
| 8 | pass | after `am kill` (the LMK path): app and runtime both gone, no orphan |
| 9 | pass | reopen: session "Big Pickle" and project `tmp` restored, state `ready` |
| 10 | pass | `27543 → 28793`, app pid unchanged, `runtime failed: … (exit 137) - restarting` |

Items 5 and 6 are the only ones unverified. They are the foreground-service
*path*, not its policy: that the service stays absent while idle is item 4, and it
passed. What is untested is that it appears during a turn and clears afterwards.

**Data integrity under `kill -9`.** The interesting case is a SIGKILL *mid-session*,
because the runtime keeps SQLite in WAL mode and holds a lock directory with a
heartbeat. Killed with a 249 KB database and a 259 KB WAL open: the restart
reopened it with no error or lock complaint in `opencode.log`, the stale lock
directory was gone, and the session rehydrated in the UI by name.

### The defect this found

Item 10 failed the first time in a way the checklist would not have caught by
reading: the renderer noticed the death and asked for a restart, and the
controller returned the *completed* start — the address of the process that had
just died. No new process, and a UI pointing at nothing. See ADR-0026; the fix is
that a cached start is reusable only while the process it produced is alive.
