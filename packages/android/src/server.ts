/**
 * Which server the app starts on.
 *
 * `ServerProvider` renders nothing until its `ready` memo is true, and that memo
 * is `ready() && !!state.active` (packages/app/src/context/server.tsx). The
 * active key starts as whatever `defaultServer` prop it was given, so an **empty
 * key gates the entire application off** - the provider never renders its
 * children and the app sits on a blank screen with no way to reach the server
 * dialog and fix it.
 *
 * That is exactly what M3 and M4 shipped, passing `Key.make("")`. It is why this
 * module exists and why the value below is never allowed to be empty.
 *
 * Desktop does not hit this because it always has a sidecar server to fall back
 * to, and web because it is served *by* the server it talks to. Android in
 * remote mode is the first case with genuinely no server until the user adds
 * one.
 */

/**
 * Stands in for "the user has not chosen a server yet".
 *
 * Non-empty so the provider's gate opens; namespaced so it cannot collide with
 * a real key, which is a URL, `sidecar`, `wsl:<distro>` or `ssh:<host>`.
 *
 * No server matches it, so upstream's `current()` memo falls through to
 * `allServers()[0]` - meaning a returning user with a stored server lands on it
 * without having had to mark anything as default.
 */
export const NO_SERVER_SELECTED = "android:no-server-selected"

/**
 * Resolves the key to start on from whatever was persisted.
 *
 * @param stored the value of `Platform.getDefaultServer()`, which is null on a
 *   fresh install and can be a stale key for a server since removed.
 */
export function startupServerKey(stored: string | null | undefined): string {
  const trimmed = stored?.trim()
  return trimmed ? trimmed : NO_SERVER_SELECTED
}

/** Whether the app is running without a server chosen. */
export function hasNoServerSelected(key: string): boolean {
  return key === NO_SERVER_SELECTED
}
