# Prompts

One paste-ready prompt per milestone. From a phone, open the file for the current
milestone (named at the top of `docs/CURRENT_STATUS.md`), copy it, and paste it
into a fresh Claude Code cloud session.

Each prompt is self-contained by design: it assumes **no chat history**, only the
repository.

| File | Milestone |
|---|---|
| `00_BOOTSTRAP.md` | M0 — baseline / bootstrap (done) |
| `01_ARCHITECTURE_AUDIT.md` | M1 — architecture audit |
| `02_ANDROID_SHELL.md` | M2 — first Android APK shell |
| `03_SHARED_UI.md` | M3 — shared OpenCode UI |
| `04_MOBILE_PLATFORM_ADAPTER.md` | M4 — platform adapter / mobile UX |
| `05_REMOTE_SERVER_MODE.md` | M5 — remote server (checkpoint) |
| `06_LOCAL_RUNTIME_SPIKE.md` | M6 — local runtime feasibility |
| `07_LOCAL_RUNTIME_INTEGRATION.md` | M7 — local runtime integration |
| `08_TERMINAL_FILES_GIT.md` | M8 — files / terminal / Git |
| `09_ANDROID_LIFECYCLE.md` | M9 — lifecycle / resilience |
| `10_SECURITY_STORAGE.md` | M10 — security / storage |
| `11_POLISH_RELEASE.md` | M11 — polish / release |
| `REVIEW_PROMPT.md` | Independent review of any completed milestone |

`REVIEW_PROMPT.md` is not a milestone. Run it in a **separate** session after a
milestone is claimed complete, to check the claim against reality.
