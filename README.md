# OpencodeApk

Building a **standalone OpenCode Android app** — installable as an APK, driven
entirely from a phone.

**Goal:** no PC required, no visible Termux workflow, and no external OpenCode
server required for the core experience.

---

## Status

**The app runs standalone on a phone.** A signed release APK cleanly installs on
ARM64 Android, starts its own OpenCode server on loopback in about three seconds,
and drives the real upstream UI — no PC, no Termux, no external server.

M11 (polish and release engineering) is in progress. See
**[`docs/CURRENT_STATUS.md`](docs/CURRENT_STATUS.md)** for the authoritative
state, including what is verified and what is not.

**Installing it:** [`docs/INSTALL.md`](docs/INSTALL.md) — requirements, first
run, known limitations, troubleshooting.

**Not done yet:** no interactive terminal (ADR-0025), provider keys are stored
unencrypted (`docs/SECURITY.md` §3), and a full agent turn has never been run
end-to-end here because it needs a provider credential.

## Start here

| If you are… | Read |
|---|---|
| A Claude Code session | [`CLAUDE.md`](CLAUDE.md), then [`docs/CURRENT_STATUS.md`](docs/CURRENT_STATUS.md) |
| Reviewing from a phone | [`docs/PHONE_WORKFLOW.md`](docs/PHONE_WORKFLOW.md) |
| Wondering what this is | [`docs/PROJECT_CHARTER.md`](docs/PROJECT_CHARTER.md) |
| Wondering how it works | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| Wondering what happens next | [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) |
| Wondering why something is the way it is | [`docs/DECISIONS.md`](docs/DECISIONS.md) |
| Installing or using the app | [`docs/INSTALL.md`](docs/INSTALL.md) |
| Cutting a release | [`docs/RELEASE.md`](docs/RELEASE.md) |
| Checking the security posture | [`docs/SECURITY.md`](docs/SECURITY.md) |
| Checking licences and attribution | [`docs/LICENSES.md`](docs/LICENSES.md) |

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

The APK also bundles Node.js, Git, ICU, OpenSSL and others, redistributed under
their own licences — including GPL-2.0 (Git) and LGPL-2.1 (GNU libiconv). See
[`docs/LICENSES.md`](docs/LICENSES.md).
