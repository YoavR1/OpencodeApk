import { describe, expect, test } from "bun:test"
import { hasNoServerSelected, NO_SERVER_SELECTED, startupServerKey } from "./server"

describe("startup server key", () => {
  test("the key is never empty", () => {
    // The whole point. ServerProvider gates on `!!state.active`, so an empty key
    // renders no application at all - no UI, and no way to reach the dialog that
    // would fix it.
    for (const stored of [null, undefined, "", "   ", "\t\n"]) {
      expect(startupServerKey(stored)).toBeTruthy()
    }
  })

  test("a stored key is used as-is", () => {
    expect(startupServerKey("http://192.168.1.10:4096")).toBe("http://192.168.1.10:4096")
  })

  test("nothing stored falls back to the sentinel", () => {
    expect(startupServerKey(null)).toBe(NO_SERVER_SELECTED)
    expect(startupServerKey(undefined)).toBe(NO_SERVER_SELECTED)
  })

  test("a blank stored value is treated as nothing stored", () => {
    expect(startupServerKey("   ")).toBe(NO_SERVER_SELECTED)
  })

  test("surrounding whitespace is trimmed off a real key", () => {
    expect(startupServerKey("  http://host:4096  ")).toBe("http://host:4096")
  })

  test("the sentinel cannot be mistaken for a real server key", () => {
    // Real keys are a URL, "sidecar", "wsl:<distro>" or "ssh:<host>"
    // (ServerConnection.key). None of those can produce this string.
    expect(NO_SERVER_SELECTED.startsWith("http")).toBe(false)
    expect(NO_SERVER_SELECTED).not.toBe("sidecar")
    expect(NO_SERVER_SELECTED.startsWith("wsl:")).toBe(false)
    expect(NO_SERVER_SELECTED.startsWith("ssh:")).toBe(false)
  })

  test("the sentinel is recognised, and real keys are not", () => {
    expect(hasNoServerSelected(startupServerKey(null))).toBe(true)
    expect(hasNoServerSelected(startupServerKey("http://host:4096"))).toBe(false)
    expect(hasNoServerSelected("sidecar")).toBe(false)
  })
})
