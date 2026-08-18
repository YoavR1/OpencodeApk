# M9 — Lifecycle and Resilience

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

Treat Android lifecycle and process death as **normal operation**, not as bugs to
patch. On a phone, backgrounding and being killed are routine.

## Tasks

1. **Agent turns survive backgrounding.** Verify under *real* backgrounding — the
   app actually sent to the background and left there — not merely with the app in
   the foreground. The foreground service from M7 is the mechanism; this milestone
   proves it works.

2. **Process-death recovery.** The process is killed while backgrounded, then
   relaunched: the app must reconnect and rehydrate session state. Server-side
   state is the source of truth; WebView state is not durable. Test with
   "Don't keep activities" and with a forced process kill.

3. **Configuration changes do not restart the server.** Rotation, dark-mode
   toggle, font-size change, multi-window. The Activity may be recreated; the
   server must not be.

4. **Low-memory behaviour.** Respond to `onTrimMemory` sensibly. Degrade
   gracefully rather than dying badly.

5. **Network transitions.** Wi-Fi ↔ cellular ↔ offline ↔ back online, mid-turn.
   No lost data, no permanently stuck UI.

6. **Battery.** No wakelock held through idle time. Bounded work with explicit
   cancellation. A background service that keeps a core busy while nothing is
   happening is a defect.

## Constraints

- These need **instrumented tests**, not reasoning. Lifecycle bugs are exactly the
  class of problem that looks fine in code review.
- Do not paper over a lifecycle bug by keeping the app artificially alive.
- Do not request battery-optimisation exemptions to avoid solving the problem.

## Exit criteria

See M9 in `docs/IMPLEMENTATION_PLAN.md`. Instrumented tests for: turn survives
backgrounding; state recovers after simulated process death; rotation does not
restart the server; network transitions lose no data.

Overnight-background and battery-drain checks need real device use. Ask me to run
them and report back (`docs/PHONE_WORKFLOW.md`).

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
