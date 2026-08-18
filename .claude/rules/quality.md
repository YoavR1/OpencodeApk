# Rule: Quality

How work in this repository is judged. This exists to prevent the most likely
failure mode of a long agent-driven project: **plausible-looking progress that
was never actually run.**

---

## Q1. Definition of done for a milestone

A milestone is complete only when **all** of the following hold:

1. Its exit criteria in `docs/IMPLEMENTATION_PLAN.md` are met.
2. A real command was run and its **actual output** is recorded — not paraphrased,
   not predicted.
3. Where the milestone claims runtime behaviour, an artifact exists (CI APK, test
   report, emulator log).
4. `docs/CURRENT_STATUS.md` is updated.
5. New decisions are in `docs/DECISIONS.md`; new upstream edits are in
   `docs/UPSTREAM_SYNC.md`.

If a criterion is unmet, the milestone is **in progress**. Say so.

## Q2. Never fabricate output

Do not write command output you did not observe. Do not write "tests pass" from
inference. If a command could not be run — proxy failure, missing SDK, no device —
write exactly that, with the error, and mark the claim **unverified**.

## Q3. Distinguish the three states explicitly

Every claim in `docs/CURRENT_STATUS.md` carries one of:

- **VERIFIED** — a command was run in this repo/CI and the output is quoted.
- **ASSUMED** — believed true from reading code or docs, not executed.
- **BLOCKED** — could not be checked; the reason is stated.

## Q4. Green output is not the goal

Do not modify a build, a lockfile, a config, or a test to make output look green.
Specifically forbidden:

- Regenerating or migrating a lockfile to work around a network failure.
- Switching package managers or package-manager versions to dodge an error.
- Deleting, skipping, or `@Ignore`-ing a failing test.
- Catching an exception to silence it without handling it.
- Weakening a type (`any`, `!`, unchecked cast) to satisfy the typechecker.

A red build that is honestly reported is worth more than a green one that lies.

## Q5. Failure triage order

1. Reproduce.
2. Read the actual error, in full.
3. Find the root cause.
4. Fix the cause.
5. If the cause is environmental (proxy, missing SDK), say so and route the check
   to CI — do not "fix" it by changing project semantics.

## Q6. Tests

- New Kotlin logic gets JVM unit tests (`test/`) unless it is pure Android
  framework glue.
- Behaviour that only manifests on a device gets an instrumented test
  (`androidTest/`) once M2 exists.
- Changes to the platform adapter get tests for the adapter contract.
- `docs/TEST_MATRIX.md` is updated whenever the set of tests changes.

## Q7. Never run upstream's root `test` script

Upstream root `package.json` contains, deliberately:

```json
"test": "echo 'do not run tests from root' && exit 1"
```

Invoking it produces a **false failure**. Run per-package tests. This is enforced
by `scripts/ci/check-opencode.sh` and denied in `.claude/settings.json`.

## Q8. Commits

- Small, focused, and buildable.
- Present-tense imperative subject: `add Android platform adapter skeleton`.
- The body says *why*, not just *what*.
- Never commit: APKs, keystores, `local.properties`, `.env`, credentials, tokens,
  or generated build output.

## Q9. Scope discipline

Do the milestone in front of you. Discovering interesting work in M7 while doing
M2 means writing it down in `docs/IMPLEMENTATION_PLAN.md` — not doing it.

## Q10. Honest handoff

The last thing every session writes is what is **not** done, what is
**unverified**, and what the **next** session should run. A handoff that only
lists accomplishments is an incomplete handoff.
