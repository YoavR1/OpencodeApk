import type { Platform } from "@opencode-ai/app"
import {
  DEGRADED,
  UNSUPPORTED,
  type UnsupportedCapability,
  UnsupportedOnAndroidError,
} from "./capabilities"

/**
 * The Android implementation of upstream's `Platform`.
 *
 * Deliberately small. M3's job is to get the shared UI rendering; the native
 * bridge that backs storage, drafts, notifications and pickers arrives in M4
 * (docs/ARCHITECTURE.md 2.4). Everything not implemented here is left
 * `undefined` on purpose — see `capabilities.ts`.
 */

declare global {
  interface Window {
    /**
     * Injected by the Android host before the bundle runs. Absent when the
     * bundle is served by `vite dev` in a desktop browser, which is why every
     * use below is guarded.
     */
    __OPENCODE_ANDROID__?: {
      versionName?: string

      // `this: void` is accurate, not a workaround: the bootstrap script in
      // AndroidHostBridge.kt builds this object from plain closures that never
      // read `this`, so pulling a member into a local is safe. Declaring it
      // keeps the unbound-method lint honest instead of suppressed.

      /** Hands a URL to the system browser via Intent.ACTION_VIEW. */
      openExternal?(this: void, url: string): void
      /** Restarts the hosting Activity. */
      restart?(this: void): void
      /** Posts a system notification; `tag` correlates the click callback. */
      notify?(this: void, title: string, body: string, tag: string): void
    }
  }
}

/**
 * The browser window, or `undefined` outside one.
 *
 * Importing this module must not require a DOM. It is imported by unit tests,
 * and a module-scope `window` reference would make the whole file unloadable
 * there - which is exactly how this was caught.
 */
const browser = (): Window | undefined => (typeof window === "undefined" ? undefined : window)

const host = () => browser()?.__OPENCODE_ANDROID__

/** URL schemes the app will hand to the system. Matches upstream's web platform. */
const OPENABLE_SCHEMES = new Set(["http:", "https:", "mailto:"])

const notificationClicks = new Map<string, () => void>()

let clickListenerInstalled = false

/**
 * Subscribes to notification taps relayed by the host.
 *
 * Installed when the platform is created rather than at module scope, so that
 * importing this file has no side effects and needs no DOM. Idempotent, because
 * the platform may legitimately be constructed more than once (the Activity is
 * disposable and can be recreated).
 */
function installNotificationClickListener() {
  if (clickListenerInstalled) return
  const win = browser()
  if (!win) return
  clickListenerInstalled = true
  win.addEventListener("opencode:notification-click", (event) => {
    const tag = (event as CustomEvent<{ tag?: string }>).detail?.tag
    if (!tag) return
    const handler = notificationClicks.get(tag)
    notificationClicks.delete(tag)
    handler?.()
  })
}

export function createAndroidPlatform(): Platform {
  installNotificationClickListener()

  return {
    platform: "android",

    version: host()?.versionName,

    /**
     * Scheme-checked before crossing the bridge. An unchecked URL here would let
     * page content trigger arbitrary Intents.
     */
    openExternal(url: string) {
      let parsed: URL
      try {
        parsed = new URL(url)
      } catch {
        return
      }
      if (!OPENABLE_SCHEMES.has(parsed.protocol)) return

      const native = host()?.openExternal
      if (native) {
        native(parsed.href)
        return
      }
      // vite dev in a browser: behave like upstream's web platform.
      browser()?.open(parsed.href, "_blank", "noopener,noreferrer")
    },

    async restart() {
      const native = host()?.restart
      if (native) {
        native()
        return
      }
      browser()?.location.reload()
    },

    /**
     * DEGRADED until M4 - see capabilities.ts.
     *
     * `notify` is required by `Platform`, so it must exist. The native path is
     * taken when the host provides one; otherwise it falls back to the Web
     * Notification API, which Android WebView does not implement. On device
     * today that means this call does nothing visible. That is recorded in
     * DEGRADED rather than left for someone to discover.
     */
    async notify(title: string, description?: string, onClick?: () => void) {
      const native = host()?.notify
      if (native) {
        const tag = `oc-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
        if (onClick) notificationClicks.set(tag, onClick)
        native(title, description ?? "", tag)
        return
      }

      if (typeof Notification === "undefined") return
      if (Notification.permission === "default") await Notification.requestPermission()
      if (Notification.permission !== "granted") return
      const notification = new Notification(title, { body: description ?? "" })
      notification.onclick = () => {
        onClick?.()
        notification.close()
      }
    },

    fetch: (input, init) => (input instanceof Request ? fetch(input) : fetch(input, init)),

    // Everything in capabilities.UNSUPPORTED is intentionally not defined here.
  }
}

/**
 * Asserts a capability is genuinely unavailable rather than forgotten.
 *
 * Exported for tests and for any future call site that wants to fail loudly
 * instead of silently doing nothing.
 */
export function refuseUnsupported(capability: UnsupportedCapability): never {
  throw new UnsupportedOnAndroidError(capability)
}

export { DEGRADED, UNSUPPORTED, UnsupportedOnAndroidError }
