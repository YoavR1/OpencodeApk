# Independent Milestone Review

Use this in a **separate session**, after a milestone has been claimed complete.
Its purpose is to check the claim against reality.

Paste this prompt, naming the milestone to review.

---

## Your role

You are reviewing milestone **M__** of this project. You did not do this work.
Your job is **not** to be agreeable — it is to find out whether the claims are
true.

The most likely failure mode of a long agent-driven project is **plausible-looking
progress that was never actually run**. You are the check on that.

## Read

1. `CLAUDE.md`
2. `docs/CURRENT_STATUS.md`
3. `docs/IMPLEMENTATION_PLAN.md` — the exit criteria for this milestone
4. `.claude/rules/quality.md`
5. `docs/TEST_MATRIX.md`
6. The actual diff for the milestone (`git log`, `git diff`)

## Check, in this order

### 1. Are the claims backed by evidence?

For every claim in `docs/CURRENT_STATUS.md`:

- Is it tagged **VERIFIED** / **ASSUMED** / **BLOCKED**?
- If VERIFIED — is there real, quoted command output, or a CI artifact?
- Does the quoted output actually support the claim, or merely accompany it?
- Is any claim about **runtime behaviour** backed only by "it compiles"?

**Re-run what you can.** Do not take a recorded result on trust if the command is
cheap to run again.

### 2. Are the exit criteria genuinely met?

Go through them one at a time. For each: met, partially met, or not met — and the
evidence. A criterion that was quietly reworded to be easier is **not met**.

### 3. Was anything faked to get green?

Look specifically for the failure modes in `.claude/rules/quality.md` Q4:

- a lockfile regenerated or migrated;
- a package-manager version changed to dodge an error;
- a test deleted, skipped, or `@Ignore`d;
- an exception caught and silenced;
- a type weakened (`any`, `!`, unchecked cast) to satisfy the typechecker;
- a lint rule disabled;
- the upstream root `test` script invoked (it intentionally exits 1).

### 4. Were the architectural rules respected?

- Upstream reused rather than rewritten?
- Any new entry in `docs/UPSTREAM_SYNC.md` — and is the divergence proportionate?
- Anything Electron-related aimed at Android?
- Android-specific behaviour leaking outside the `Platform` boundary?
- A second connection path added alongside `ServerConnection`?
- Any new permission, and is it justified?
- Anything requiring root?
- A credential, keystore, or APK committed?

### 5. Is the documentation honest?

- Does `docs/CURRENT_STATUS.md` match the repository as it actually is?
- Are decisions recorded in `docs/DECISIONS.md`?
- Is `docs/TEST_MATRIX.md` updated, and do the ✅ marks correspond to tests that
  really pass?
- Is M5 (remote server) still correctly described as a checkpoint rather than the
  goal?

## Report

Write your findings as:

```
## Verdict: COMPLETE | INCOMPLETE | MISREPRESENTED

### Exit criteria
- [criterion]: MET / PARTIAL / NOT MET — evidence

### Claims that do not hold up
...

### Rule violations
...

### What must be fixed before this milestone can be called complete
...
```

Be specific and cite files and lines. "Looks good" is not a review.

**Do not fix anything.** Report. Fixing is the next implementation session's job,
and mixing the two destroys the value of an independent check.

If the milestone genuinely is complete, say so plainly — an honest pass is as
useful as an honest fail.
