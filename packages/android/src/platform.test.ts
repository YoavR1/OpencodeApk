import { describe, expect, test, beforeEach, afterEach } from "bun:test"
import { createAndroidPlatform, DEGRADED, UNSUPPORTED, UnsupportedOnAndroidError, refuseUnsupported } from "./platform"

/**
 * These tests protect the two properties requirement 4 of M3 is about:
 * unsupported capabilities stay *absent* rather than becoming silent no-ops,
 * and the capability declaration matches the implementation.
 */

/**
 * A minimal `window`, rather than a DOM library.
 *
 * The adapter only needs `addEventListener`, `open` and `location.reload`, so a
 * few stubs are cheaper and more explicit than pulling happy-dom in as a
 * dependency and changing the lockfile again. It also keeps these tests honest
 * about exactly which browser API surface the adapter touches.
 */
function installFakeWindow() {
  const fake = {
    addEventListener: () => {},
    open: () => null,
    location: { reload: () => {} },
  }
  Reflect.set(globalThis, "window", fake)
  return fake
}

beforeEach(() => {
  installFakeWindow()
})

afterEach(() => {
  Reflect.deleteProperty(globalThis, "window")
})

/** Attaches a host bridge to the fake window. */
function setHost(bridge: NonNullable<Window["__OPENCODE_ANDROID__"]>) {
  Reflect.set(Reflect.get(globalThis, "window") as object, "__OPENCODE_ANDROID__", bridge)
}

/**
 * Reads a capability by name without a type assertion.
 *
 * `Platform` is a union of exact shapes, so indexing it by a dynamic string is
 * not expressible in the type system. `Reflect.get` is the honest way to ask
 * "is this member present?" without casting the object into something it isn't.
 */
const capability = (name: string): unknown => Reflect.get(createAndroidPlatform(), name)

describe("platform identity", () => {
  test("reports the android platform name", () => {
    expect(createAndroidPlatform().platform).toBe("android")
  })

  test("implements the three members Platform requires", () => {
    const platform = createAndroidPlatform()
    expect(typeof platform.openExternal).toBe("function")
    expect(typeof platform.restart).toBe("function")
    expect(typeof platform.notify).toBe("function")
  })
})

describe("unsupported capabilities are absent, not stubbed", () => {
  test("every declared-unsupported member is undefined", () => {
    for (const name of Object.keys(UNSUPPORTED)) {
      expect(capability(name), `${name} must be absent, not a stub`).toBeUndefined()
    }
  })

  test("a stub returning undefined would fail this suite", () => {
    // Guards against a future 'fix' that adds no-op methods to quiet a type
    // error: upstream checks !!platform.openPath, so a stub would make the UI
    // believe the action is available and silently do nothing.
    const platform = createAndroidPlatform()
    expect(Object.hasOwn(platform, "openPath")).toBe(false)
    expect(Object.hasOwn(platform, "openDirectoryPickerDialog")).toBe(false)
  })

  test("refuseUnsupported throws a typed, explanatory error", () => {
    expect(() => refuseUnsupported("openPath")).toThrow(UnsupportedOnAndroidError)
    try {
      refuseUnsupported("storage")
      throw new Error("refuseUnsupported did not throw")
    } catch (error) {
      if (!(error instanceof UnsupportedOnAndroidError)) throw error
      expect(error.capability).toBe("storage")
      expect(error.message).toContain("M4")
    }
  })

  test("degraded members exist but are declared", () => {
    for (const name of Object.keys(DEGRADED)) {
      // Present, because Platform requires them...
      expect(typeof capability(name), `${name} must exist`).toBe("function")
      // ...and named, so the gap is visible rather than discovered on a device.
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
      // Either it never applies, or it names the milestone that delivers it.
      expect(/never|M\d+/.test(reason), `${name}: "${reason}"`).toBe(true)
    }
  })
})

describe("openExternal", () => {
  test("passes http, https and mailto to the host", () => {
    const seen: string[] = []
    setHost({ openExternal: (url: string) => seen.push(url) })
    const platform = createAndroidPlatform()

    platform.openExternal("https://opencode.ai/")
    platform.openExternal("http://127.0.0.1:4096/")
    platform.openExternal("mailto:someone@example.com")

    expect(seen).toEqual(["https://opencode.ai/", "http://127.0.0.1:4096/", "mailto:someone@example.com"])
  })

  test("refuses schemes that could trigger arbitrary intents", () => {
    const seen: string[] = []
    setHost({ openExternal: (url: string) => seen.push(url) })
    const platform = createAndroidPlatform()

    platform.openExternal("javascript:alert(1)")
    platform.openExternal("intent://scan/#Intent;scheme=zxing;end")
    platform.openExternal("file:///etc/passwd")
    platform.openExternal("content://com.android.providers/x")

    expect(seen).toEqual([])
  })

  test("ignores malformed urls instead of throwing", () => {
    const seen: string[] = []
    setHost({ openExternal: (url: string) => seen.push(url) })
    const platform = createAndroidPlatform()

    expect(() => platform.openExternal("not a url")).not.toThrow()
    expect(() => platform.openExternal("")).not.toThrow()
    expect(seen).toEqual([])
  })
})

describe("version", () => {
  test("comes from the host when present", () => {
    setHost({ versionName: "0.2.0-m3" })
    expect(createAndroidPlatform().version).toBe("0.2.0-m3")
  })

  test("is undefined outside the Android host", () => {
    expect(createAndroidPlatform().version).toBeUndefined()
  })
})
