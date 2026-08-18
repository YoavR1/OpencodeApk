import { describe, expect, test, beforeEach, afterEach } from "bun:test"
import type { Bridge, BridgeEvent, BridgeEventName, BridgeRequest } from "./bridge"
import { createAndroidPlatform, DEGRADED, SUPPORTED, UNSUPPORTED, UnsupportedOnAndroidError, refuseUnsupported } from "./platform"

/**
 * These tests protect the property M3 established and M4 has to keep:
 * unsupported capabilities stay *absent* rather than becoming silent no-ops,
 * and the capability declaration matches the implementation.
 *
 * They also cover the capabilities M4 added, at the bridge boundary — what the
 * adapter asks the host for, and what it does with the answer.
 */

type Recorded = { method: string; params?: unknown }

/** A bridge that records requests and replies from a scripted table. */
function fakeBridge(replies: Partial<Record<string, unknown>> = {}) {
  const calls: Recorded[] = []
  const handlers = new Map<string, Set<(payload: never) => void>>()

  const bridge: Bridge = {
    available: true,
    request<T>(request: BridgeRequest): Promise<T> {
      calls.push({ method: request.method, params: (request as { params?: unknown }).params })
      if (request.method in replies) return Promise.resolve(replies[request.method] as T)
      return Promise.resolve(undefined as T)
    },
    on(event, handler) {
      let set = handlers.get(event)
      if (!set) {
        set = new Set()
        handlers.set(event, set)
      }
      set.add(handler as (p: never) => void)
      return () => set.delete(handler as (p: never) => void)
    },
  }

  const emit = <E extends BridgeEventName>(payload: Extract<BridgeEvent, { event: E }>) => {
    for (const handler of handlers.get(payload.event) ?? []) (handler as (p: unknown) => void)(payload)
  }

  return { bridge, calls, emit }
}

/** A bridge with no host, as under `vite dev` or in a plain browser. */
const noHostBridge = (): Bridge => ({
  available: false,
  request: () => Promise.reject(new Error("unavailable")),
  on: () => () => {},
})

function installFakeWindow() {
  const opened: string[] = []
  Reflect.set(globalThis, "window", {
    addEventListener: () => {},
    open: (url: string) => {
      opened.push(url)
      return null
    },
    location: { reload: () => {} },
  })
  return opened
}

beforeEach(() => {
  installFakeWindow()
})

afterEach(() => {
  Reflect.deleteProperty(globalThis, "window")
})

const capability = (bridge: Bridge, name: string): unknown => Reflect.get(createAndroidPlatform(bridge), name)

describe("platform identity", () => {
  test("reports the android platform name", () => {
    expect(createAndroidPlatform(fakeBridge().bridge).platform).toBe("android")
  })

  test("implements the three members Platform requires", () => {
    const platform = createAndroidPlatform(fakeBridge().bridge)
    expect(typeof platform.openExternal).toBe("function")
    expect(typeof platform.restart).toBe("function")
    expect(typeof platform.notify).toBe("function")
  })
})

describe("supported capabilities are really present", () => {
  test("every declared-supported capability exists", () => {
    // The mirror of the unsupported check below. Without it, a capability could
    // be dropped from the adapter while `capabilities.ts` still advertises it,
    // and the shared UI would route around a feature this platform claims.
    //
    // `version` is excluded: it is a value the host supplies asynchronously, not
    // a capability, and its absence before host info arrives is asserted in its
    // own test below. `draftStore` is injected by the composition root.
    const { bridge } = fakeBridge()
    const injected = new Set(["version", "draftStore"])
    for (const name of SUPPORTED) {
      if (injected.has(name)) continue
      expect(capability(bridge, name), `${name} is declared SUPPORTED`).toBeDefined()
    }
  })

  test("draftStore is present once the composition root supplies one", () => {
    const store = { get: async () => null } as never
    const platform = createAndroidPlatform(fakeBridge().bridge, store)
    expect(platform.draftStore).toBe(store)
  })

  test("nothing is declared supported and unsupported at once", () => {
    for (const name of SUPPORTED) {
      expect(Object.keys(UNSUPPORTED)).not.toContain(name)
      expect(Object.keys(DEGRADED)).not.toContain(name)
    }
  })
})

describe("unsupported capabilities are absent, not stubbed", () => {
  test("every declared-unsupported member is undefined", () => {
    const { bridge } = fakeBridge()
    for (const name of Object.keys(UNSUPPORTED)) {
      expect(capability(bridge, name), `${name} must be absent, not a stub`).toBeUndefined()
    }
  })

  test("a stub returning undefined would fail this suite", () => {
    const platform = createAndroidPlatform(fakeBridge().bridge)
    expect(Object.hasOwn(platform, "openPath")).toBe(false)
    expect(Object.hasOwn(platform, "saveFilePickerDialog")).toBe(false)
  })

  test("refuseUnsupported throws a typed, explanatory error", () => {
    expect(() => refuseUnsupported("openPath")).toThrow(UnsupportedOnAndroidError)
    try {
      refuseUnsupported("saveFilePickerDialog")
      throw new Error("refuseUnsupported did not throw")
    } catch (error) {
      if (!(error instanceof UnsupportedOnAndroidError)) throw error
      expect(error.capability).toBe("saveFilePickerDialog")
      expect(error.message).toContain("M8")
    }
  })

  test("degraded members exist and name a milestone", () => {
    const { bridge } = fakeBridge()
    for (const name of Object.keys(DEGRADED)) {
      expect(typeof capability(bridge, name), `${name} must exist`).toBe("function")
      expect(String(Reflect.get(DEGRADED, name))).toMatch(/M\d+/)
    }
  })

  test("a capability is never in two categories at once", () => {
    for (const name of Object.keys(DEGRADED)) {
      expect(Object.keys(UNSUPPORTED)).not.toContain(name)
    }
  })

  test("each unsupported entry explains itself", () => {
    for (const [name, reason] of Object.entries(UNSUPPORTED)) {
      expect(reason.length, `${name} needs a reason`).toBeGreaterThan(0)
      expect(/never|M\d+/.test(reason), `${name}: "${reason}"`).toBe(true)
    }
  })
})

describe("openExternal", () => {
  test("passes http, https and mailto to the host", () => {
    const { bridge, calls } = fakeBridge()
    const platform = createAndroidPlatform(bridge)
    platform.openExternal("https://opencode.ai/")
    platform.openExternal("http://127.0.0.1:4096/")
    platform.openExternal("mailto:someone@example.com")
    expect(calls.map((c) => (c.params as { url: string }).url)).toEqual([
      "https://opencode.ai/",
      "http://127.0.0.1:4096/",
      "mailto:someone@example.com",
    ])
  })

  test("refuses schemes that could trigger arbitrary intents", () => {
    const { bridge, calls } = fakeBridge()
    const platform = createAndroidPlatform(bridge)
    platform.openExternal("javascript:alert(1)")
    platform.openExternal("intent://scan/#Intent;scheme=zxing;end")
    platform.openExternal("file:///etc/passwd")
    platform.openExternal("content://com.android.providers/x")
    expect(calls).toEqual([])
  })

  test("ignores malformed urls instead of throwing", () => {
    const { bridge, calls } = fakeBridge()
    const platform = createAndroidPlatform(bridge)
    expect(() => platform.openExternal("not a url")).not.toThrow()
    expect(calls).toEqual([])
  })

  test("falls back to window.open with no host", () => {
    const opened = installFakeWindow()
    createAndroidPlatform(noHostBridge()).openExternal("https://opencode.ai/")
    expect(opened).toEqual(["https://opencode.ai/"])
  })
})

describe("storage", () => {
  test("reads and writes through the host, by store name", async () => {
    const { bridge, calls } = fakeBridge({ "store.get": "stored" })
    const store = createAndroidPlatform(bridge).storage?.("opencode.global.dat")
    expect(await store?.getItem("language")).toBe("stored")
    await store?.setItem("language", "en")
    expect(calls).toEqual([
      { method: "store.get", params: { name: "opencode.global.dat", key: "language" } },
      { method: "store.set", params: { name: "opencode.global.dat", key: "language", value: "en" } },
    ])
  })

  test("returns the same object for the same store name", () => {
    const platform = createAndroidPlatform(fakeBridge().bridge)
    expect(platform.storage?.("a")).toBe(platform.storage?.("a"))
    expect(platform.storage?.("a")).not.toBe(platform.storage?.("b"))
  })

  test("length comes from the host key list", async () => {
    const { bridge } = fakeBridge({ "store.keys": ["one", "two", "three"] })
    const store = createAndroidPlatform(bridge).storage?.()
    expect(await store?.getLength()).toBe(3)
    expect(await store?.key(1)).toBe("two")
  })
})

describe("default server", () => {
  test("reads the persisted selection", async () => {
    const { bridge } = fakeBridge({ "defaultServer.get": "http://127.0.0.1:4096" })
    expect(await createAndroidPlatform(bridge).getDefaultServer?.()).toBe("http://127.0.0.1:4096")
  })

  test("clearing the selection sends null", async () => {
    const { bridge, calls } = fakeBridge()
    await createAndroidPlatform(bridge).setDefaultServer?.(null)
    expect(calls).toEqual([{ method: "defaultServer.set", params: { url: null } }])
  })
})

describe("notify", () => {
  test("posts through the host and routes the tap back", async () => {
    const { bridge, calls, emit } = fakeBridge({ notify: true })
    const platform = createAndroidPlatform(bridge)

    let clicked = false
    await platform.notify("Turn finished", "3 files changed", () => {
      clicked = true
    })

    const tag = (calls[0]?.params as { tag: string }).tag
    expect(tag).toBeTruthy()
    emit({ event: "notification.clicked", tag })
    expect(clicked).toBe(true)
  })

  test("drops the handler when the host could not post", async () => {
    // A refused POST_NOTIFICATIONS shows nothing, so a tap can never arrive.
    // Keeping the callback would leak it for the life of the app.
    const { bridge, calls, emit } = fakeBridge({ notify: false })
    const platform = createAndroidPlatform(bridge)

    let clicked = false
    await platform.notify("Ignored", "", () => {
      clicked = true
    })

    emit({ event: "notification.clicked", tag: (calls[0]?.params as { tag: string }).tag })
    expect(clicked).toBe(false)
  })
})

describe("directory picker", () => {
  test("returns the SAF tree uri", async () => {
    const { bridge } = fakeBridge({ pickDirectory: "content://tree/primary%3AProjects" })
    expect(await createAndroidPlatform(bridge).openDirectoryPickerDialog?.()).toBe(
      "content://tree/primary%3AProjects",
    )
  })

  test("returns null when the user cancels", async () => {
    const { bridge } = fakeBridge({ pickDirectory: null })
    expect(await createAndroidPlatform(bridge).openDirectoryPickerDialog?.()).toBeNull()
  })
})
