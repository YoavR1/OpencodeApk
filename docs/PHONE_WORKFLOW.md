# Phone Workflow

This project is driven from an Android phone. That constraint shapes the whole
process: **you cannot build or run the app locally, so CI is the build authority
and the repository is the memory.**

---

## The loop

```
  Phone: open a Claude Code cloud session
     │
     ├─ paste the next prompt from prompts/
     │
  Cloud session: read CLAUDE.md → docs/CURRENT_STATUS.md → do the milestone
     │
     ├─ commits and pushes to a branch
     │
  GitHub Actions: builds, tests, produces the APK artifact
     │
  Phone: review the diff, check CI, download the APK, install, verify
     │
     └─ report device results back into the next session
```

## Starting a session

1. Open a Claude Code cloud session on this repository.
2. Paste the prompt for the current milestone from `prompts/`.
3. That is all you need to type. The prompt plus `CLAUDE.md` plus
   `docs/CURRENT_STATUS.md` carry all the context.

To find the current milestone, open `docs/CURRENT_STATUS.md` — the top of the file
names it and the recommended next prompt.

## Reviewing from a phone

GitHub's mobile web and the GitHub mobile app are both workable:

- **Diff:** open the branch → "Files changed". Review docs changes carefully;
  they are the project's memory and are the easiest thing for an agent to get
  subtly wrong.
- **CI:** the Actions tab shows the Android CI run. Check the job summary — the
  workflow writes a plain-language phase state there.
- **Artifacts:** from M2 onward, a successful run attaches `app-debug.apk` as a
  workflow artifact. Download it from the run page.

## Installing the APK on your phone

1. Download the artifact from the Actions run (it arrives as a `.zip`).
2. Extract it (most Android file managers can; otherwise use any unzip app).
3. Tap the `.apk`. Android will ask permission to install from this source —
   grant it for your browser or file manager.
4. Install and launch.

These are **debug** builds, signed with the standard Android debug key. That is
fine for testing and cannot be published.

## Debugging on the device

**A cloud session cannot reach your phone.** It runs in an isolated container —
no USB passthrough, no route to your LAN — so `adb` there lists nothing however
the phone is plugged in. The phone is connected to *your* machine, which is a
different computer from the one the session runs on. Anything device-side has to
run on your side.

Most of what the sessions need does not require any tooling: whether the UI
appears, whether a reply streams in, whether an error message is clear. Those are
observations, and reporting them in a sentence is worth more than a log.

When something fails *invisibly* — a blank screen, a request that goes nowhere —
`scripts/dev/capture-device-log.sh` collects what the session cannot see:

```
scripts/dev/capture-device-log.sh [path/to/app-debug.apk] [seconds]
```

It installs the APK, launches it, captures WebView console output and crashes for
90 seconds, redacts anything credential-shaped, and writes a `device-report-*.log`
to paste back. WebView console output is where a blocked CORS preflight appears
and nowhere else, which is the most likely silent failure in remote-server mode.

It needs `adb`, which means a computer, or a shell on the phone itself with
Android's wireless debugging enabled. If you have neither, skip it — answer the
questions in `docs/CURRENT_STATUS.md` instead and that will usually be enough to
tell the next session where to look.

## Reporting device results

Device evidence is the one thing a cloud session cannot produce. When you test on
your phone, report back in the next session with:

- phone model and Android version,
- what you did,
- what happened,
- any error text or screenshot.

The next session records it in `docs/CURRENT_STATUS.md` as verified device
evidence. Without it, milestones that claim runtime behaviour cannot be closed.

### Capturing a crash without a PC

If the app crashes, a logcat reader from the Play Store can capture the stack
trace without needing `adb`. Paste the trace into the next session.

## What you should expect at each milestone

| Milestone | What you can do on the phone |
|---|---|
| M0–M1 | Review docs only. Nothing to install. |
| M2 | Install the first APK. It shows a placeholder — that is success. |
| M3 | The real OpenCode UI appears, with no server connected. |
| M4 | The UI feels like a phone app: keyboard, back button, touch targets. |
| M5 | Connect to a remote OpenCode server and complete a real turn. |
| M6 | Nothing to install — read `docs/LOCAL_RUNTIME_REPORT.md`. |
| M7 | **The real milestone:** a turn completes with no external server. |
| M8 | Browse files, use the terminal, run Git operations. |
| M9 | Background the app mid-turn; it should survive. |
| M10 | Credentials stored securely. |
| M11 | A polished, installable app. |

## Guardrails worth knowing

The agent has been instructed that a milestone is complete only when a real test
or build supports it, and that every claim is tagged **VERIFIED**, **ASSUMED**, or
**BLOCKED**. If you read a status update that claims something works without
naming the evidence, that is a bug in the process — push back on it.

Similarly: **remote-server mode (M5) is not the goal.** If a session reports the
project as finished at M5, that is wrong.

## If a session goes wrong

Nothing is lost that was pushed. Start a new session and paste:

> Read `CLAUDE.md` and `docs/CURRENT_STATUS.md`. The previous session went wrong —
> [describe]. Assess the actual repository state, correct
> `docs/CURRENT_STATUS.md` if it is inaccurate, and tell me where we really are
> before doing any new work.

Correcting the status file is legitimate, valuable work.
