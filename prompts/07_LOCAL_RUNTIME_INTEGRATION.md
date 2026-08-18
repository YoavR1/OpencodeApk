# M7 — Local Runtime Integration

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

**This is the milestone that makes the project real.** Its completion is what
distinguishes this project from a remote client.

Do not start it until M6 has produced `docs/LOCAL_RUNTIME_REPORT.md` with a
recommendation backed by artifacts. If M6 is not complete, run
`prompts/06_LOCAL_RUNTIME_SPIKE.md` instead.

## Goal

The OpenCode server runs **on the device**, and a full agent turn completes with
**no external server**.

## Tasks

1. **Package the runtime and server bundle into the APK** for `arm64-v8a`,
   following M6's recommendation. Watch APK size; record it.

2. **Create `OpencodeService`, a foreground service**, owning the server process
   lifecycle. A foreground service — not a bare thread — because an in-flight
   agent turn must survive the app being backgrounded.

3. **Port the desktop sidecar pattern** (`packages/desktop/src/main/sidecar.ts`,
   ADR-0005):

   - generate a **per-launch password** (in memory; never persisted, never logged);
   - start the server **out-of-process** on `127.0.0.1` with a chosen port;
   - pass `{ username: "opencode", password, cors: [<WebView origin>] }` to
     `Server.listen()`;
   - hand the resulting URL to the UI;
   - stop the process cleanly on shutdown.

   Upstream's `Server.listen()` signature is at
   `packages/opencode/src/server/server.ts:73`.

4. **Build the local `ServerConnection` value** and pass it to the **same** client
   factories M5 used. Switching between local and remote must change a *value*,
   not a *code path*. If you find yourself writing a second path, stop.

5. **App-private data directory** (the `XDG_STATE_HOME` equivalent — desktop sets
   exactly this in `sidecar.ts`), surviving app restarts, with the server
   reattaching to it.

6. **Startup, readiness, health check, and failure handling.** The UI must show a
   truthful state while the server starts, and a clear error if it fails.

7. **Complete a full agent turn with no external server.** This is the milestone.

## Constraints

- **Loopback only**, auth always on (ADR-0006). On Android any app can reach
  loopback ports, so loopback is not a trust boundary.
- Never log the server password or any credential.
- No root.
- Server survives backgrounding (fuller lifecycle work is M9, but the foreground
  service lands here).

## Exit criteria

See M7 in `docs/IMPLEMENTATION_PLAN.md`. Server starts on-device on `arm64-v8a`
with log evidence; binds `127.0.0.1` with auth; the UI connects via the same
`ServerConnection` path as M5; a full turn completes with no external server;
data persists across restart.

Several of these need device evidence. If you do not have a device report from me,
mark those **BLOCKED** and tell me exactly what to run and report back.

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
