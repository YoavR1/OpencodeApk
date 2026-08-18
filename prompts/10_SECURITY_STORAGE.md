# M10 — Security and Storage

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

Production-grade credential and data handling. Everything earlier milestones
deferred gets settled here — and anything that shipped insecurely gets migrated.

## Tasks

1. **Provider credentials in Android Keystore-backed storage**
   (`EncryptedSharedPreferences` or an equivalent using a Keystore key). If any
   earlier milestone stored a credential less securely, **migrate it** and
   verify the old copy is gone.

2. **Audit every permission in the manifest.** For each: why it is needed, what
   breaks without it. Remove anything that cannot be justified in a sentence.
   Confirm `scripts/ci/verify-apk.sh`'s forbidden list is still respected.

3. **Verify loopback-only binding by test.** Prove the server cannot be reached
   from off-device, and that authentication cannot be disabled. Remember that on
   Android, other apps can reach loopback — auth is the actual boundary, not the
   bind address.

4. **Audit the JS bridge.** Every message validated; no path from web content to
   arbitrary Android capability; no reflection-based `addJavascriptInterface`
   exposure. Assume the WebView could be running hostile content and check what it
   could reach.

5. **Log audit.** No credential, token, or server password in any log, at any
   level, including crash reports and debug builds.

6. **Decide the data-at-rest posture** for the server's SQLite database (Q6).
   Encrypted or not, and why. Record as an ADR. Android's full-disk encryption
   already covers a locked device — the question is what threat model justifies
   more.

## Constraints

- Never log a secret.
- Never weaken a security control to make a test pass — change the test.
- No feature may require root.
- If you find a vulnerability introduced by an earlier milestone, fix it and record
  it in `docs/CURRENT_STATUS.md`. That is a good outcome, not an embarrassment.

## Exit criteria

See M10 in `docs/IMPLEMENTATION_PLAN.md`. Credentials Keystore-backed with tests;
every permission justified in writing; loopback-only binding verified by test;
bridge reviewed and documented; log audit clean; the review recorded in
`docs/DECISIONS.md`.

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
