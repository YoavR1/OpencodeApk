# Rule: Android

Binding constraints for anything that runs on a device.

---

## N1. Target and ABI

- **Primary ABI: `arm64-v8a`.** This is what real phones run. An `x86_64`-only
  result (emulator-only) is a *spike artifact*, never a milestone completion.
- `x86_64` may be built additionally to speed up CI emulator tests.
- Use a current supported JDK (**17 or 21**) and a current `compileSdk`.
  `minSdk` is chosen in M2 and recorded in `docs/DECISIONS.md`; bias toward a
  modern floor (API 26+) rather than maximum reach, because the local runtime
  work needs modern process and filesystem behaviour.

## N2. No root, ever

No feature may require a rooted device, a custom ROM, or `su`. If a design needs
root, the design is wrong.

## N3. No visible Termux workflow

The user must not be asked to install Termux, run shell commands, or see a
terminal to get a working app. Termux is a legitimate **research reference** for
"what runs on Android ARM64" during M6 — it is never a shipped dependency.

## N4. Permissions are opt-in and narrow

- **Never** request `MANAGE_EXTERNAL_STORAGE` without an ADR.
- Prefer app-private storage (`Context.filesDir`, `getExternalFilesDir`) and the
  Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) for user project folders.
- `INTERNET` is required for provider API calls. Note that a purely local server
  on loopback does **not** by itself require `INTERNET` on modern Android, but the
  AI providers do.
- Justify every permission in the manifest with a comment.

## N5. Network binding and exposure

- The on-device server binds **`127.0.0.1` only** by default. Upstream already
  defaults this way: `packages/cli/src/commands/commands.ts` declares
  `hostname: Flag.string("hostname").pipe(Flag.withDefault("127.0.0.1"))`.
- Authentication stays **on** even on loopback — other apps on the device can
  reach loopback ports. Use the per-launch password pattern from
  `packages/desktop/src/main/sidecar.ts`.
- Binding to `0.0.0.0` is a user-visible, opt-in, ADR-gated feature. Not a default.
- Do not weaken `usesCleartextTraffic` globally. Scope any cleartext exception to
  `127.0.0.1` via a network security config.

## N6. WebView rules

- Load shared UI from app assets, not from a remote origin.
- Prefer `WebViewAssetLoader` so assets are served over an `https://` origin
  rather than `file://` — `file://` origins break fetch/CORS and storage
  behaviour that the shared UI relies on.
- Whatever origin is chosen, it must be added to the server's CORS allowlist via
  the `cors` option of `Server.listen()`. Upstream's allowlist logic lives at
  `packages/server/src/cors.ts` and already permits `http://localhost:` and
  `http://127.0.0.1:` origins; desktop passes `["oc://renderer"]`.
- `setJavaScriptEnabled(true)` is required. `setAllowFileAccessFromFileURLs` and
  `setAllowUniversalAccessFromFileURLs` must stay **false**.
- The JS bridge (`addJavascriptInterface` or `WebMessagePort`) is the *only*
  Android surface exposed to web code. Keep it small, typed, and validated.
  Prefer `WebMessagePort`/`postMessage` over `addJavascriptInterface`.

## N7. Lifecycle and process death are normal

Design for these from the start, not as bug fixes:

- The Activity is destroyed and recreated on rotation and configuration change.
- The **process** can be killed at any time while backgrounded.
- A long-running agent turn must survive backgrounding — that means a
  **foreground service** with a user-visible notification, not a bare thread.
- WebView state is not durable. Session state lives on the server side; the UI
  must be able to reconnect and rehydrate after process death.
- Assume the server process can be killed independently and must be restartable
  with the same data directory.

## N8. Storage and credentials

- Provider credentials belong in Android Keystore-backed storage
  (`EncryptedSharedPreferences` or an equivalent using a Keystore key).
- Plaintext credentials are permitted **only** inside a branch explicitly labelled
  as a spike, and must never reach a release build.
- Never log a credential, token, or the server password.
- The generated server password is per-launch and in-memory; it is not persisted.

## N9. Battery, memory, and thermals

Phones are not laptops. A design that pins a CPU core or holds a wakelock through
idle time is a defect. Prefer bounded work and explicit cancellation.

## N10. Evidence

Any claim that something "works on Android" requires one of:

- a CI artifact (APK) plus the build log, or
- an instrumented/emulator test run in CI, or
- a device log the user captured.

"It compiles" is not "it works". Write which of these backs each claim in
`docs/CURRENT_STATUS.md`.
