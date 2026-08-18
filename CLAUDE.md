# OpencodeApk — Claude Code project control file

**Read this file first in every session. Then read `docs/CURRENT_STATUS.md`.**

This repository builds a **real, standalone OpenCode Android APK**. Chat history is
not durable; this repository is. Everything a future session needs to continue is
committed here.

---

## 1. What this project is

| | |
|---|---|
| **Goal** | A standalone OpenCode Android app (APK) |
| **Upstream** | `anomalyco/opencode` (public, MIT) |
| **This repo** | A **new wrapper repository** — it is *not* a fork or copy of upstream |
| **Package manager (upstream)** | `bun@1.3.14` (declared in upstream `package.json#packageManager`) |
| **Android build** | Gradle + Android Gradle Plugin (does not exist yet — see M2) |

### The end goal, stated precisely

- No PC required.
- No visible Termux workflow.
- **No external OpenCode server required for the final core experience.**

Remote-server mode (M5) is a legitimate **intermediate integration checkpoint**.
It is explicitly **not** the end state. Never present M5 as "done".

---

## 2. Non-negotiable principles

These are binding. If a task appears to require breaking one, stop and write the
conflict into `docs/DECISIONS.md` instead of quietly breaking it.

1. **Reuse upstream before rewriting.** Every line re-implemented is a line that
   must be re-merged forever. Prefer consuming an upstream package over copying it.
2. **Never run Electron on Android.** Electron is the desktop shell only. The
   *pattern* it uses (a spawned local server + a webview client) is what we port —
   not the runtime.
3. **Isolate Android behind a platform boundary.** Upstream already defines one
   (`packages/app/src/context/platform.tsx`). Android code goes behind it, not
   sprinkled through shared UI.
4. **Minimize upstream divergence.** Track every upstream file you must modify in
   `docs/UPSTREAM_SYNC.md`. A growing patch list is a design smell.
5. **One connection abstraction.** Local loopback and optional remote server must
   both flow through upstream's `ServerConnection` type. Do not add a parallel path.
6. **Local runtime work is evidence-driven.** "Bun/Node runs on Android" is a claim
   that requires an artifact — a log, an APK, a device/emulator run. ARM64
   (`arm64-v8a`) is the target that matters.
7. **No root requirement.** Ever.
8. **No unnecessary broad storage permissions.** No `MANAGE_EXTERNAL_STORAGE`
   unless a written decision in `docs/DECISIONS.md` justifies it.
9. **Local server binds to loopback by default** (`127.0.0.1`), with authentication
   on, matching upstream desktop behaviour.
10. **Provider credentials must end up in secure Android storage** (Keystore-backed).
    Plaintext credentials are acceptable only inside an explicitly-labelled spike.
11. **Android lifecycle and process death are normal, not edge cases.** Backgrounding,
    low-memory kills, and configuration changes must be designed for.
12. **A milestone is complete only when a real test or build supports the claim.**
13. **Every implementation session updates `docs/CURRENT_STATUS.md`.**

---

## 3. Repository map

| Path | Purpose |
|---|---|
| `CLAUDE.md` | This file — entry point for every session |
| `.claude/settings.json` | Claude Code harness config (hooks, permissions) |
| `.claude/rules/architecture.md` | Architectural constraints |
| `.claude/rules/android.md` | Android-specific rules |
| `.claude/rules/quality.md` | Definition of done, evidence standards |
| `docs/PROJECT_CHARTER.md` | Scope, goals, non-goals |
| `docs/ARCHITECTURE.md` | Upstream architecture (verified) + target Android design |
| `docs/IMPLEMENTATION_PLAN.md` | Milestones M0–M11 with exit criteria |
| `docs/CURRENT_STATUS.md` | **Live state. Updated every session.** |
| `docs/DECISIONS.md` | ADR log |
| `docs/TEST_MATRIX.md` | What is tested, how, and where |
| `docs/UPSTREAM_SYNC.md` | Upstream pin + divergence ledger |
| `docs/CLAUDE_CLOUD_SETUP.md` | Cloud session environment notes |
| `docs/PHONE_WORKFLOW.md` | How to drive this project from a phone |
| `prompts/` | Ready-to-paste prompts, one per milestone |
| `scripts/cloud/session-start.sh` | Resilient session bootstrap |
| `scripts/ci/*.sh` | CI entry points (also runnable locally) |
| `.github/workflows/android-ci.yml` | Android CI (separate from upstream CI) |

---

## 4. Session protocol

**At session start**

1. Read this file.
2. Read `docs/CURRENT_STATUS.md` — it is the source of truth for where we are.
3. Read the milestone prompt in `prompts/` for the milestone you are working on.
4. Run `scripts/cloud/session-start.sh` to check the environment. It is
   informational and must never fail the session on its own.

**During the session**

- Work on exactly one milestone. Do not skip ahead.
- Record every architectural choice in `docs/DECISIONS.md` as it is made.
- Record every upstream file you had to modify in `docs/UPSTREAM_SYNC.md`.

**Before ending the session**

1. Update `docs/CURRENT_STATUS.md` with: what changed, real commands run, real
   output, what is verified vs. assumed, and the recommended next prompt.
2. Commit and push to the session branch.
3. State plainly what is *not* done.

---

## 5. Commands

`scripts/ci/check-opencode.sh` and `scripts/ci/build-android.sh` are the
authoritative entry points. Run those rather than memorising flags.

### Hard rule: never run upstream's root `test` script

Upstream's root `package.json` defines:

```json
"test": "echo 'do not run tests from root' && exit 1"
```

This is **intentional**, not broken. Running `bun test` / `bun run test` at the
upstream root is a guaranteed false failure. Run per-package tests instead
(`bun test --cwd packages/<name>`). `scripts/ci/check-opencode.sh` enforces this.

### Upstream check commands (verified in upstream `package.json`)

| Task | Command |
|---|---|
| Lint | `bun run lint` (oxlint) |
| Typecheck | `bun run typecheck` (`bun turbo typecheck`) |
| Per-package test | `bun test --cwd packages/<pkg>` |

---

## 6. Cloud-session constraints

- Prefer the **declared** package-manager version (`bun@1.3.14`). Do not "upgrade
  to fix" a network problem.
- **Never silently migrate or regenerate a lockfile.** A lockfile change is a
  reviewable decision, not a side effect.
- If Bun install or network fetching fails behind the cloud proxy: **report it,
  record it in `docs/CURRENT_STATUS.md`, and treat CI as the build authority.**
  Do not change package-manager semantics to make local output look green.

---

## 7. Anti-patterns — do not do these

- Vendoring a copy of upstream source into this repo "to make it easier".
- Re-implementing the OpenCode UI in Compose / React Native / Flutter.
- Bundling an OpenCode server binary for `x86_64` only and calling it done.
- Marking a milestone complete on the basis of code that has never been run.
- Adding a second HTTP client path alongside `ServerConnection`.
- Committing an APK, keystore, or provider credential to this repository.
