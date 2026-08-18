# Claude Code Cloud Session Setup

How this project behaves inside a Claude Code cloud session, and what to do when
the environment fights you.

---

## Environment observed at M0 (2026-08-18)

Measured, not assumed:

| Tool | Version | Note |
|---|---|---|
| Node | `v22.22.2` | |
| npm | `10.9.7` | |
| Bun | `1.3.11` | ⚠️ Upstream declares `bun@1.3.14` — see "Bun version mismatch" |
| pnpm | `10.33.0` | Not used by this project |
| Go | `go1.24.7 linux/amd64` | Not used by this project |
| Java | OpenJDK `21.0.10` | Suitable for Android builds |
| Gradle | `8.14.3` (system) | The project will use its own wrapper from M2 |
| Android SDK | **absent** | `ANDROID_HOME` and `ANDROID_SDK_ROOT` are both unset |
| Git | `2.43.0` | |

**Host architecture is `x86_64`.** The device target is `arm64-v8a`. A cloud
session can never prove device behaviour by running something locally — this is
exactly why `.claude/rules/quality.md` requires artifacts.

## No Android SDK in the cloud session

No Android SDK is provisioned by default. **CI remains the Android build
authority** — `.github/workflows/android-ci.yml` installs the SDK and performs the
build that counts, and a device report is what proves runtime behaviour.

**Installing the SDK in a session is allowed when it shortens a real debug loop.**
M2 hit a genuine toolchain problem (AGP/androidx version constraints) that only a
real build could diagnose, and each CI round cost minutes. Installing the
command-line tools turned that into a seconds-long local loop and produced better
code — it found a WebView render-process crash bug and three toolchain errors that
would otherwise have been several more CI rounds each.

The line to hold is the *purpose*:

- **Legitimate:** iterating locally to diagnose a real failure, then pushing and
  letting CI confirm.
- **Not legitimate:** treating a local green build as the result, or changing
  project semantics so that something passes locally.

To set it up:

```bash
curl -sSL -o cmdtools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-9862592_latest.zip
unzip -q cmdtools.zip -d "$ANDROID_HOME/cmdline-tools"
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-37.0" "build-tools;37.0.0"
```

Note the platform is `android-37.0`, not `android-37`: current platforms carry a
minor version. `sdkmanager --list | grep platforms` shows what is actually offered.

Cloud sessions are for: reading code, writing code, diagnosing, and pushing.
CI is for: proving. A device is for: proving runtime behaviour.

## Network and the agent proxy

Outbound HTTPS goes through a pre-configured agent proxy (CA bundle at
`/root/.ccr/ca-bundle.crt`). `JAVA_TOOL_OPTIONS` is preset with a truststore and
proxy host so JVM tooling works.

If a tool fails TLS verification, or gets 403/405/407:

1. Read `/root/.ccr/README.md`.
2. Run `curl -sS "$HTTPS_PROXY/__agentproxy/status"`.
3. **Never** disable TLS verification. **Never** unset `HTTPS_PROXY`.

## Bun version mismatch — do not "fix" it

Upstream declares `packageManager: "bun@1.3.14"`. The session provides Bun
`1.3.11`.

**Rule:** prefer the declared version. If a Bun operation fails because of the
version gap or a proxy problem:

- **Report it.** Write it into `docs/CURRENT_STATUS.md` as **BLOCKED** with the
  actual error text.
- **Route the check to CI**, which sets up Bun via upstream's own
  `.github/actions/setup-bun`.
- **Do not** upgrade/downgrade Bun globally, switch package managers, or delete
  and regenerate `bun.lock` to make an error go away.

A lockfile change is a reviewable decision, never a side effect of debugging.

## Never run the upstream root `test` script

```json
"test": "echo 'do not run tests from root' && exit 1"
```

Deliberate upstream behaviour. Running it is a guaranteed false failure. It is
denied in `.claude/settings.json` and guarded by `scripts/ci/check-opencode.sh`.

## Session start

`.claude/settings.json` registers a `SessionStart` hook running
`scripts/cloud/session-start.sh`. It:

- prints tool versions and repository state,
- reports whether upstream is integrated and whether an Android project exists,
- prints the current milestone from `docs/CURRENT_STATUS.md`,
- **always exits 0.**

It is diagnostic only. A cloud session must never be blocked by its own
bootstrap. Fresh clones, missing SDKs, and absent upstream are all normal states
it reports rather than fails on.

## Reading upstream from a session

Upstream is public, so an anonymous shallow clone works:

```bash
GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 \
  https://github.com/anomalyco/opencode /workspace/anomalyco/opencode
```

Use a generous timeout — the shallow pack took minutes at M0. Read it there;
do not copy it into this repository until ADR-0002 is decided.

## Git conventions

- Work on the assigned session branch. Never push to another branch without
  explicit permission.
- Push with `git push -u origin <branch>`.
- On network failure, retry up to 4 times with backoff (2s, 4s, 8s, 16s).
- Never commit APKs, keystores, `local.properties`, `.env`, or credentials.

## Ending a session

1. Update `docs/CURRENT_STATUS.md` — real commands, real output, honest status.
2. Commit and push.
3. State what is not done and name the next prompt.

The container is ephemeral. Anything not pushed is lost.
