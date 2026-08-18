# OpencodeApk

Building a **standalone OpenCode Android app** — installable as an APK, driven
entirely from a phone.

**Goal:** no PC required, no visible Termux workflow, and no external OpenCode
server required for the core experience.

---

## Status

**M1 (architecture audit) complete.** This repository currently contains a
project-control layer and a decided architecture — no application code yet. See
**[`docs/CURRENT_STATUS.md`](docs/CURRENT_STATUS.md)** for the authoritative state.

Next milestone: **M2 — first Android APK shell** (`prompts/02_ANDROID_SHELL.md`),
which begins by vendoring upstream OpenCode into this repository.

## Start here

| If you are… | Read |
|---|---|
| A Claude Code session | [`CLAUDE.md`](CLAUDE.md), then [`docs/CURRENT_STATUS.md`](docs/CURRENT_STATUS.md) |
| Reviewing from a phone | [`docs/PHONE_WORKFLOW.md`](docs/PHONE_WORKFLOW.md) |
| Wondering what this is | [`docs/PROJECT_CHARTER.md`](docs/PROJECT_CHARTER.md) |
| Wondering how it works | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| Wondering what happens next | [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) |
| Wondering why something is the way it is | [`docs/DECISIONS.md`](docs/DECISIONS.md) |

## How this project is built

Each milestone (M0–M11) is one paste-ready prompt in [`prompts/`](prompts/),
run in a Claude Code cloud session. The repository — not chat history — is the
project's memory, so any session can pick up where the last one stopped.

GitHub Actions is the build authority: cloud sessions have no Android SDK, so CI
performs the real build and publishes the debug APK as an artifact.

## Relationship to upstream OpenCode

This is a **wrapper** repository, not a fork of
[`anomalyco/opencode`](https://github.com/anomalyco/opencode) (MIT). Upstream is
consumed and reused; divergence is tracked file-by-file in
[`docs/UPSTREAM_SYNC.md`](docs/UPSTREAM_SYNC.md).
