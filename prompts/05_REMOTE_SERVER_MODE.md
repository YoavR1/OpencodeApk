# M5 — Remote Server Integration (CHECKPOINT)

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

## Read this first

**This milestone is a checkpoint, not the product.**

The project goal is an app that needs **no external OpenCode server**. M5 exists
only so the UI↔server path can be proven end to end *before* the hard on-device
runtime work in M6/M7.

Never report M5 as project completion. `docs/CURRENT_STATUS.md` must state
explicitly that this is an intermediate checkpoint (ADR-0003).

## Goal

The Android app connects to a real, remote OpenCode server and completes a real
agent turn.

## Tasks

1. **Server connection UI** — URL, username, password. Reachable from the app.

2. **Build a `ServerConnection.Http` value** and hand it to the *existing*
   upstream client factories in `packages/app/src/utils/server.ts`
   (`createSdkForServer`, `createApiForServer`). They already attach HTTP Basic
   auth from `username`/`password`.

   **Do not write a new HTTP client, base-URL resolver, or fetch path.** The whole
   point of this milestone is that M7 will reuse the same code with a different
   `ServerConnection` value.

3. **Credentials go through the secure storage interface from the start.** Design
   the M10 Keystore-backed interface now and use it. Do not ship plaintext
   credentials "temporarily" — temporary things survive.

4. **Verify the streaming/event transport works through Android WebView.** SSE and
   WebSocket behaviour in WebView is a real risk area and is exactly the kind of
   thing this checkpoint exists to discover early. Test it deliberately.

5. **Handle failure honestly:** offline, wrong credentials, unreachable host,
   connection dropped mid-turn. Each produces a clear UI state, not a crash and
   not a spinner forever.

6. **Complete a real agent turn** against a real server, end to end.

## Constraints

- One connection abstraction. Local and remote differ by *value*, never by *path*.
- Do not weaken TLS handling to make a test server work.
- Do not add an Android-only networking path.

## Exit criteria

See M5 in `docs/IMPLEMENTATION_PLAN.md`. A real turn completes; streaming works;
failures are handled; and `docs/CURRENT_STATUS.md` records this as a checkpoint.

## What comes next

M6 is the feasibility spike for running the server **on the device**. That is the
milestone that decides whether the project's actual goal is achievable.

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
