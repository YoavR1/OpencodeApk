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

/**
 * A server the user has configured, as this app stores it.
 *
 * Deliberately not upstream's `ServerConnection`: this module is imported by
 * tests that run without the workspace installed, and keeping it free of
 * upstream value imports keeps them that way. `main.tsx` converts.
 */
export type StoredServer = {
  url: string
  username?: string
  password?: string
}

/** Where the first-run server lives. Upstream manages any further ones itself. */
export const SERVER_STORE = "android.dat"
export const SERVER_KEY = "server"

/**
 * Normalises what someone typed into a URL that can be connected to.
 *
 * Returns undefined for anything unusable, so a bad entry is reported at the
 * point of entry rather than becoming a server that can never be reached.
 */
export function normalizeServerUrl(input: string): string | undefined {
  const trimmed = input.trim()
  if (!trimmed) return undefined
  const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`
  let parsed: URL
  try {
    parsed = new URL(withScheme)
  } catch {
    return undefined
  }
  if (!parsed.hostname) return undefined
  // Trailing slashes would make two spellings of one server look like two.
  return `${parsed.protocol}//${parsed.host}${parsed.pathname.replace(/\/+$/, "")}`
}

/**
 * Whether a URL can be reached from the app's page at all.
 *
 * The app is served from an `https://` origin, and a secure context may not
 * make plaintext requests - Chromium blocks them as mixed content before any
 * network call happens, and no Android setting affects that. Loopback is the
 * exception: it is a "potentially trustworthy" origin by specification.
 *
 * So a plain-http server is only usable when it is loopback. Saying so at the
 * point of entry is far kinder than a connection that fails forever with
 * "could not reach", which is what the browser reports for a blocked request.
 */
export function isReachableFromSecureContext(url: string): boolean {
  try {
    const parsed = new URL(url)
    if (parsed.protocol === "https:") return true
    return parsed.hostname === "127.0.0.1" || parsed.hostname === "localhost" || parsed.hostname === "[::1]"
  } catch {
    return false
  }
}

/** The `ServerConnection.key` upstream will derive for a stored server. */
export function storedServerKey(server: StoredServer): string {
  return server.url
}
