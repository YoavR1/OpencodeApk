# M0 — Baseline / Bootstrap

**Status: COMPLETE.** Kept for the record and for re-bootstrapping if the control
layer is ever lost.

## What this milestone did

Established a durable project-control layer so that future Claude Code cloud
sessions can continue with no chat history, and audited the real upstream
architecture rather than assuming it.

## Original task

Given an empty repository that is meant to become a standalone OpenCode Android
APK:

1. Inspect the entire repository structure and Git status.
2. Determine whether it is a fork/copy of `anomalyco/opencode` or a new wrapper
   repository.
3. Inspect the **actual** upstream architecture — core/server, shared SolidJS app,
   Electron desktop wrapper, SDK/API generation — by reading the code, not from
   memory.
4. Inspect package manager and version, lockfiles, existing CI, and build scripts.
5. Create the control layer: `CLAUDE.md`, `.claude/**`, `docs/**`, `prompts/**`,
   `scripts/**`, `.github/workflows/android-ci.yml`.
6. Run safe baseline checks and document the real commands and results.
7. Do **not** start the Android implementation.
8. Update `docs/CURRENT_STATUS.md` with the exact real repository state.

## What was found

- The repository was **completely empty** — no commits locally, no refs on the
  remote. It is a **new wrapper repository**, not a fork (ADR-0001).
- Upstream `anomalyco/opencode` at `4e81a0b`: a Bun workspace monorepo
  (`bun@1.3.14`), Turborepo-orchestrated, oxlint + `tsgo`.
- Upstream's root `test` script deliberately exits 1.
- Upstream already provides both boundaries this project needs: the `Platform`
  type (`packages/app/src/context/platform.tsx`) and the `ServerConnection` union
  (`packages/app/src/context/server.tsx:181`) — see ADR-0004.
- The desktop app uses a **sidecar** pattern (`packages/desktop/src/main/sidecar.ts`)
  that is the model for Android (ADR-0005), while Electron itself is never ported.
- A **Node build target already exists** (`packages/opencode/script/build-node.ts`,
  `src/node.ts`), and the server's HTTP stack is `node:http` — making a
  Node-compatible on-device runtime the leading M7 candidate (ADR-0007).

Full detail is in `docs/ARCHITECTURE.md`; decisions are in `docs/DECISIONS.md`.

## Re-running this milestone

You should not need to. If the control layer is lost, restore it from Git history.
If that is impossible, the fastest recovery is to read `docs/ARCHITECTURE.md` (if
it survives) or re-audit upstream from scratch:

```bash
GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 \
  https://github.com/anomalyco/opencode /workspace/anomalyco/opencode
```

## Next

`prompts/01_ARCHITECTURE_AUDIT.md`
