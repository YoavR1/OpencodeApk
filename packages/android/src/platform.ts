import type { DraftStore, Platform } from "@opencode-ai/app"
import type { Bridge } from "./bridge"
import { DEGRADED, SUPPORTED, UNSUPPORTED, type UnsupportedCapability, UnsupportedOnAndroidError } from "./capabilities"

/**
 * What `Platform.storage` must return, derived from the boundary itself.
 *
 * Upstream's own type comes from `@solid-primitives/storage`, which
 * `packages/app` depends on and this package does not. Reading it back off
 * `Platform` keeps the dependency surface here at one package, and means the
 * adapter tracks upstream's contract rather than a second copy of it that could
 * drift - including any type arguments upstream applies.
 */
type PlatformStorage = ReturnType<NonNullable<Platform["storage"]>>

/**
 * The Android implementation of upstream's `Platform`.
 *
 * Every capability is backed by the typed bridge in `bridge.ts`. Where there is
 * no host - `vite dev` in a desktop browser, or a unit test - each method falls
 * back to the browser behaviour rather than failing, so the same renderer runs
 * in both places.
 *
 * Anything not implemented here is deliberately absent; see `capabilities.ts`.
 */

/** URL schemes the app will hand to the system. Matches upstream's web platform. */
const OPENABLE_SCHEMES = new Set(["http:", "https:", "mailto:"])

const browser = (): Window | undefined => (typeof window === "undefined" ? undefined : window)

function fromBase64(base64: string, type: string): Blob {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return new Blob([bytes], { type })
}

/**
 * @param draftStore supplied by the composition root, so this module needs no
 *   value import from `@opencode-ai/app` and stays testable without the
 *   workspace installed. See `drafts.ts`.
 */
export function createAndroidPlatform(bridge: Bridge, draftStore?: DraftStore): Platform {
  const notificationClicks = new Map<string, () => void>()

  bridge.on("notification.clicked", ({ tag }) => {
    const handler = notificationClicks.get(tag)
    notificationClicks.delete(tag)
    handler?.()
  })

  /**
   * Named stores, each backed by its own SharedPreferences file.
   *
   * Cached per name so that repeated `platform.storage("x")` calls hand back the
   * same object, matching how the desktop implementation behaves.
   */
  const storage = (() => {
    const cache = new Map<string, PlatformStorage>()

    const create = (name: string): PlatformStorage => {
      // Parameters are annotated rather than inferred: the contextual type is a
      // union (sync or async storage), and TypeScript does not reliably infer
      // parameters through one.
      const api: PlatformStorage = {
        getItem: (key: string) => bridge.request<string | null>({ method: "store.get", params: { name, key } }),
        setItem: (key: string, value: string) =>
          bridge.request<boolean>({ method: "store.set", params: { name, key, value } }).then(() => undefined),
        removeItem: (key: string) =>
          bridge.request<boolean>({ method: "store.remove", params: { name, key } }).then(() => undefined),
        clear: () => bridge.request<boolean>({ method: "store.clear", params: { name } }).then(() => undefined),
        key: async (index: number) =>
          (await bridge.request<string[]>({ method: "store.keys", params: { name } }))[index] ?? null,
        getLength: async () => (await bridge.request<string[]>({ method: "store.keys", params: { name } })).length,
        get length() {
          return api.getLength()
        },
      }
      return api
    }

    return (name = "default.dat") => {
      const cached = cache.get(name)
      if (cached) return cached
      const api = create(name)
      cache.set(name, api)
      return api
    }
  })()

  return {
    platform: "android",

    version: undefined, // replaced below once host.info answers

    openExternal(url: string) {
      let parsed: URL
      try {
        parsed = new URL(url)
      } catch {
        return
      }
      // Checked here as a convenience; the host re-checks, because any script in
      // the WebView can reach the bridge and the host must not trust its caller.
      if (!OPENABLE_SCHEMES.has(parsed.protocol)) return

      if (bridge.available) {
        void bridge.request({ method: "openExternal", params: { url: parsed.href } }).catch(() => undefined)
        return
      }
      browser()?.open(parsed.href, "_blank", "noopener,noreferrer")
    },

    async restart() {
      if (bridge.available) {
        await bridge.request({ method: "restart" }).catch(() => undefined)
        return
      }
      browser()?.location.reload()
    },

    async notify(title: string, description?: string, onClick?: () => void) {
      if (bridge.available) {
        const tag = `oc-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
        if (onClick) notificationClicks.set(tag, onClick)
        const posted = await bridge
          .request<boolean>({ method: "notify", params: { title, body: description ?? "", tag } })
          .catch(() => false)
        // A refused POST_NOTIFICATIONS means nothing was shown; drop the handler
        // rather than leaving it waiting for a tap that can never arrive.
        if (!posted) notificationClicks.delete(tag)
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

    storage,
    draftStore,

    async getDefaultServer() {
      if (!bridge.available) return null
      const url = await bridge.request<string | null>({ method: "defaultServer.get" }).catch(() => null)
      return (url ?? null) as Parameters<NonNullable<Platform["setDefaultServer"]>>[0]
    },

    async setDefaultServer(url) {
      if (!bridge.available) return
      await bridge
        .request({ method: "defaultServer.set", params: { url: url ?? null } })
        .catch(() => undefined)
    },

    async readClipboardImage() {
      if (!bridge.available) return null
      const image = await bridge
        .request<{ base64: string; type: string } | null>({ method: "clipboard.readImage" })
        .catch(() => null)
      if (!image) return null
      return new File([fromBase64(image.base64, image.type)], `pasted-image-${Date.now()}.png`, {
        type: image.type,
      })
    },

    /**
     * Project folders come from the Storage Access Framework, so this returns
     * `content://` tree URIs rather than filesystem paths. The app holds no
     * storage permission (.claude/rules/android.md N4).
     */
    async openDirectoryPickerDialog() {
      if (!bridge.available) return null
      const uri = await bridge
        .request<string | null>({ method: "pickDirectory", params: {} })
        .catch(() => null)
      return uri ?? null
    },

    fetch: (input, init) => (input instanceof Request ? fetch(input) : fetch(input, init)),

    // Everything in capabilities.UNSUPPORTED is intentionally not defined here.
  } as Platform
}

/** Reads host facts that are only available once the bridge answers. */
export async function readHostInfo(bridge: Bridge) {
  if (!bridge.available) return undefined
  return bridge
    .request<{ versionName: string; debug: boolean; notificationsPermitted: boolean }>({ method: "host.info" })
    .catch(() => undefined)
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

export { DEGRADED, SUPPORTED, UNSUPPORTED, UnsupportedOnAndroidError }
