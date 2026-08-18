# Project Charter — OpencodeApk

## Purpose

Deliver a **real, standalone OpenCode application for Android**, distributed as an
APK, that a developer can install and use from a phone alone.

## The end state

A user installs one APK and gets a working OpenCode. Specifically:

- **No PC required** — not for setup, not for pairing, not for a server.
- **No visible Termux workflow** — no shell, no package installs, no terminal
  instructions as part of normal use.
- **No external OpenCode server required** for the core experience — the agent
  runtime runs on the device.

## Why a wrapper repository

This repository is **not** a fork of `anomalyco/opencode`. It is a wrapper that
consumes upstream and adds an Android target.

The reason is maintenance cost. OpenCode is developed rapidly. A fork accrues
merge debt on every upstream commit. A wrapper with a pinned upstream and a small,
explicit patch ledger keeps the cost of following upstream bounded and visible.

The upstream integration mechanism is deliberately **not yet fixed** — see
ADR-0002 in `docs/DECISIONS.md`. It is decided in M1 with evidence.

## Scope

### In scope

- An Android application shell (Kotlin, Gradle, WebView-hosted UI).
- Reuse of upstream's shared SolidJS UI (`@opencode-ai/app`, `session-ui`, `ui`).
- An Android implementation of upstream's `Platform` boundary.
- Mobile UX adaptation (touch targets, keyboard, safe areas, navigation).
- Running the OpenCode server **on the device**, on loopback, authenticated.
- Remote-server connection mode as an **intermediate integration checkpoint**.
- File browsing, terminal, and Git operations appropriate to a phone.
- Android lifecycle resilience: backgrounding, process death, foreground service.
- Secure credential storage via Android Keystore.
- CI producing a debug APK artifact.

### Out of scope

- Running Electron on Android, in any form.
- A native rewrite of the OpenCode UI (Compose / React Native / Flutter).
- iOS.
- Publishing to Google Play (revisit at M11 if the project gets that far).
- Changing OpenCode's agent, provider, or tool semantics.
- Any feature requiring root.

## Non-goals, stated explicitly

**Remote-server mode is not the product.** It exists so that M3/M4 UI work can be
validated against a real OpenCode server before the hard local-runtime problem is
solved. Shipping only remote mode would fail the charter.

## Success criteria

| # | Criterion | Verified by |
|---|---|---|
| 1 | An installable debug APK builds in CI | CI artifact |
| 2 | The shared OpenCode UI renders in the Android shell | Screenshot + instrumented test |
| 3 | The app connects to an OpenCode server via `ServerConnection` | Integration test (M5) |
| 4 | An OpenCode server runs on-device on `arm64-v8a` | Device/emulator log (M7) |
| 5 | A full agent turn completes with no external server | Device evidence (M7) |
| 6 | Agent turns survive backgrounding | Instrumented test (M9) |
| 7 | Credentials are stored Keystore-backed | Code review + test (M10) |
| 8 | No root, no Termux, no broad storage permission | Manifest review |

## Principal risks

| Risk | Impact | Mitigation |
|---|---|---|
| No usable JS runtime for OpenCode core on Android ARM64 | Fatal to the end goal | M6 is a dedicated, evidence-driven feasibility spike run **before** committing to an approach |
| Native dependencies (`node-pty`, `@parcel/watcher`, `tree-sitter`) lack Android ARM64 builds | Feature loss or fatal | Enumerate in M6; identify WASM/pure-JS fallbacks (`web-tree-sitter` is already WASM) |
| Upstream churn outpaces integration | Rising maintenance cost | Pinned upstream + divergence ledger in `docs/UPSTREAM_SYNC.md` |
| Phone-only workflow limits verification | Unverified claims accumulate | CI is the build authority; every claim tagged VERIFIED/ASSUMED/BLOCKED |
| Memory pressure kills the server process mid-turn | Poor UX | Foreground service + reconnect/rehydrate design in M9 |

## Working agreement

- A milestone is complete only when a real test or build supports the claim.
- Every implementation session updates `docs/CURRENT_STATUS.md`.
- Architectural choices are recorded in `docs/DECISIONS.md` when made.
