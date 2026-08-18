import { describe, expect, test, beforeEach, afterEach } from "bun:test"
import { createAndroidPlatform, DEGRADED, UNSUPPORTED, UnsupportedOnAndroidError, refuseUnsupported } from "./platform"

/**
 * These tests protect the two properties requirement 4 of M3 is about:
 * unsupported capabilities stay *absent* rather than becoming silent no-ops,
 * and the capability declaration matches the implementation.
 */

const originalHost = window.__OPENCODE_ANDROID__

beforeEach(() => {
  delete window.__OPENCODE_ANDROID__
})

afterEach(() => {
  if (originalHost) window.__OPENCODE_ANDROID__ = originalHost
  else delete window.__OPENCODE_ANDROID__
})

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
    const platform = createAndroidPlatform() as Record<string, unknown>
    for (const capability of Object.keys(UNSUPPORTED)) {
      expect(platform[capability]).toBeUndefined()
    }
  })

  test("a stub returning undefined would fail this suite", () => {
    // Guards against a future 'fix' that adds no-op methods to quiet a type
    // error: upstream checks !!platform.openPath, so a stub would make the UI
    // believe the action is available and silently do nothing.
    const platform = createAndroidPlatform() as Record<string, unknown>
    expect("openPath" in platform).toBe(false)
    expect("openDirectoryPickerDialog" in platform).toBe(false)
  })

  test("refuseUnsupported throws a typed, explanatory error", () => {
    expect(() => refuseUnsupported("openPath")).toThrow(UnsupportedOnAndroidError)
    try {
      refuseUnsupported("storage")
    } catch (error) {
      expect(error).toBeInstanceOf(UnsupportedOnAndroidError)
      expect((error as UnsupportedOnAndroidError).capability).toBe("storage")
      expect((error as Error).message).toContain("M4")
    }
  })

  test("degraded members exist but are declared", () => {
    const platform = createAndroidPlatform() as Record<string, unknown>
    for (const capability of Object.keys(DEGRADED)) {
      // Present, because Platform requires them...
      expect(typeof platform[capability]).toBe("function")
      // ...and named, so the gap is visible rather than discovered on a device.
      expect(DEGRADED[capability as keyof typeof DEGRADED]).toMatch(/M\d+/)
    }
  })

  test("a capability is never in two categories at once", () => {
    for (const capability of Object.keys(DEGRADED)) {
      expect(Object.keys(UNSUPPORTED)).not.toContain(capability)
    }
  })

  test("each unsupported entry explains itself", () => {
    for (const [capability, reason] of Object.entries(UNSUPPORTED)) {
      expect(reason.length, `${capability} needs a reason`).toBeGreaterThan(0)
      // Either it never applies, or it names the milestone that delivers it.
      expect(/never|M\d+/.test(reason), `${capability}: "${reason}"`).toBe(true)
    }
  })
})

describe("openExternal", () => {
  test("passes http, https and mailto to the host", () => {
    const seen: string[] = []
    window.__OPENCODE_ANDROID__ = { openExternal: (url) => seen.push(url) }
    const platform = createAndroidPlatform()

    platform.openExternal("https://opencode.ai/")
    platform.openExternal("http://127.0.0.1:4096/")
    platform.openExternal("mailto:someone@example.com")

    expect(seen).toEqual(["https://opencode.ai/", "http://127.0.0.1:4096/", "mailto:someone@example.com"])
  })

  test("refuses schemes that could trigger arbitrary intents", () => {
    const seen: string[] = []
    window.__OPENCODE_ANDROID__ = { openExternal: (url) => seen.push(url) }
    const platform = createAndroidPlatform()

    platform.openExternal("javascript:alert(1)")
    platform.openExternal("intent://scan/#Intent;scheme=zxing;end")
    platform.openExternal("file:///etc/passwd")
    platform.openExternal("content://com.android.providers/x")

    expect(seen).toEqual([])
  })

  test("ignores malformed urls instead of throwing", () => {
    const seen: string[] = []
    window.__OPENCODE_ANDROID__ = { openExternal: (url) => seen.push(url) }
    const platform = createAndroidPlatform()

    expect(() => platform.openExternal("not a url")).not.toThrow()
    expect(() => platform.openExternal("")).not.toThrow()
    expect(seen).toEqual([])
  })
})

describe("version", () => {
  test("comes from the host when present", () => {
    window.__OPENCODE_ANDROID__ = { versionName: "0.2.0-m3" }
    expect(createAndroidPlatform().version).toBe("0.2.0-m3")
  })

  test("is undefined outside the Android host", () => {
    expect(createAndroidPlatform().version).toBeUndefined()
  })
})
