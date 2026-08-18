/**
 * What the Android platform can and cannot do, stated once and explicitly.
 *
 * `Platform`'s capability members are optional, and upstream guards each one
 * (`!!platform.openPath`, `platform.platform === "desktop" && ...`). That makes
 * "leave it undefined" the correct way to say *not supported* — a stub that
 * resolved silently would make the UI believe the action succeeded.
 *
 * This table exists so the decision is reviewable in one place rather than
 * inferred from which properties happen to be missing, and so a test can assert
 * that the implementation matches the declaration.
 */

/** Implemented for real on Android. */
export const SUPPORTED = [
  "version",
  "openExternal",
  "restart",
  "fetch",
  "notify",
  "storage",
  "draftStore",
  "getDefaultServer",
  "setDefaultServer",
  "readClipboardImage",
  "openDirectoryPickerDialog",
] as const

/**
 * Present because `Platform` requires them, but not yet fully functional.
 *
 * This category exists so that a required member which cannot do its job yet is
 * *stated* rather than left as a silent no-op. Anything here must be listed with
 * the milestone that completes it.
 *
 * Empty as of M4: `notify` moved to SUPPORTED once it gained a channel, the
 * POST_NOTIFICATIONS request and a click route through the bridge.
 */
export const DEGRADED: Readonly<Record<string, string>> = {}

export type DegradedCapability = keyof typeof DEGRADED

/**
 * Deliberately absent. Left `undefined` so upstream's capability checks route
 * around them, instead of being stubbed into a silent no-op.
 *
 * The milestone in brackets is when it is expected to arrive, or `never` when
 * the capability has no meaning on Android.
 */
export const UNSUPPORTED = {
  // Desktop window/OS integration - no Android equivalent.
  openPath: "never - no user-visible filesystem paths under SAF",
  openLocalFile: "never",
  revealPath: "never - Android has no 'reveal in file manager' contract",
  checkAppExists: "never - 'open in editor' is a desktop concept",
  getPathForFile: "never - SAF yields content:// URIs, not paths",
  runDesktopMenuAction: "never - no menu bar",
  webviewZoom: "never - the system WebView handles pinch zoom",
  getPinchZoomEnabled: "never",
  setPinchZoomEnabled: "never",
  windowFullscreen: "never",
  getDisplayBackend: "never - X11/Wayland is a Linux desktop concern",
  setDisplayBackend: "never",
  wslServers: "never - Windows only",
  updater: "never - app updates are a distribution concern on Android",
  setForceFocus: "never - desktop devtools affordance",
  windowID: "never - Android is single-window here",

  // Real gaps, scheduled.
  openAttachmentPickerDialog: "M8 - SAF ACTION_OPEN_DOCUMENT, alongside the file browser",
  saveFilePickerDialog: "M8 - SAF ACTION_CREATE_DOCUMENT",
  exportDebugLogs: "M9 - needs on-device logging first",
  recordFatalRendererError: "M9 - needs on-device logging first",
} as const

export type UnsupportedCapability = keyof typeof UNSUPPORTED

/** Every capability that is not fully working, with the reason, in one place. */
export const NOT_FULLY_SUPPORTED = { ...DEGRADED, ...UNSUPPORTED } as const

/**
 * Thrown when web code reaches for something Android does not provide.
 *
 * Nothing in the shared UI should hit this — every optional member is guarded —
 * so it is a loud signal that a guard is missing rather than an expected path.
 */
export class UnsupportedOnAndroidError extends Error {
  constructor(readonly capability: UnsupportedCapability) {
    super(`"${capability}" is not available on Android: ${UNSUPPORTED[capability]}`)
    this.name = "UnsupportedOnAndroidError"
  }
}
