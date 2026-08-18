# M8 — Files, Terminal, and Git

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

The developer workflow features — file access, a terminal, and Git — adapted to a
phone rather than transplanted from a desktop.

## Tasks

### Files

- Browse and edit files in project folders granted via SAF
  (`ACTION_OPEN_DOCUMENT_TREE`, persisted permissions).
- A file tree that is usable with a thumb, not a scaled-down desktop tree.
- Handle large files and binary files without hanging the UI.

### Terminal

Start from M6's native-dependency matrix. `@lydell/node-pty` is a native N-API
module and may not be available on Android ARM64.

- If PTY is available: wire it up.
- If it is not: choose deliberately between a **pipe-based fallback** (no TTY
  semantics — no interactive programs, no job control, but adequate for running
  commands and reading output) and **disabling the terminal**.
- **Record the choice as an ADR either way.** A missing feature with a written
  reason is a decision; a missing feature without one is a defect.

### Git

- Status, diff, commit, branch operations against a real repository.
- Credential handling for remotes, using the M10 secure storage — never plaintext.
- A **mobile-appropriate diff view**: side-by-side does not work on a phone;
  unified with good typography does.

## Constraints

- No broad storage permission. SAF only.
- No root.
- Every feature gets tests.
- If a feature genuinely cannot work on Android, say so and record why rather than
  shipping something that looks like it works.

## Exit criteria

See M8 in `docs/IMPLEMENTATION_PLAN.md`. Files browsable and editable in a real
project folder; terminal working or its absence justified in an ADR; Git
status/diff/commit working against a real repository; tests for each.

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
