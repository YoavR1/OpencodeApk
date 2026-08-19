import { describe, expect, test } from "bun:test"
import {
  hasNoServerSelected,
  isReachableFromSecureContext,
  normalizeServerUrl,
  NO_SERVER_SELECTED,
  startupServerKey,
} from "./server"

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

describe("normalizeServerUrl", () => {
  test("bare host:port gets a scheme", () => {
    expect(normalizeServerUrl("192.168.1.10:4096")).toBe("http://192.168.1.10:4096")
  })

  test("an explicit scheme is kept", () => {
    expect(normalizeServerUrl("https://opencode.example:8443")).toBe("https://opencode.example:8443")
  })

  test("trailing slashes are dropped so one server has one spelling", () => {
    expect(normalizeServerUrl("http://host:4096/")).toBe("http://host:4096")
    expect(normalizeServerUrl("http://host:4096///")).toBe("http://host:4096")
  })

  test("surrounding whitespace is ignored", () => {
    expect(normalizeServerUrl("  host:4096  ")).toBe("http://host:4096")
  })

  test("a path is preserved, for a server behind a prefix", () => {
    expect(normalizeServerUrl("http://host/opencode")).toBe("http://host/opencode")
  })

  test("unusable input is refused rather than guessed at", () => {
    for (const bad of ["", "   ", "http://", "://nope", "http:// space"]) {
      expect(normalizeServerUrl(bad)).toBeUndefined()
    }
  })
})

describe("isReachableFromSecureContext", () => {
  test("https is always reachable", () => {
    expect(isReachableFromSecureContext("https://opencode.example")).toBe(true)
    expect(isReachableFromSecureContext("https://192.168.1.10:4096")).toBe(true)
  })

  test("loopback over http is reachable", () => {
    // Potentially trustworthy by specification, which is why the on-device
    // server in M7 works while a LAN server over http cannot.
    expect(isReachableFromSecureContext("http://127.0.0.1:4096")).toBe(true)
    expect(isReachableFromSecureContext("http://localhost:4096")).toBe(true)
  })

  test("a LAN address over http is NOT reachable", () => {
    // Verified on a device: Chromium blocks it as mixed content before any
    // request is made, and no Android network setting changes that.
    expect(isReachableFromSecureContext("http://192.168.1.156:4096")).toBe(false)
    expect(isReachableFromSecureContext("http://10.0.0.5:4096")).toBe(false)
    expect(isReachableFromSecureContext("http://my-laptop.local:4096")).toBe(false)
  })

  test("nonsense is not reachable", () => {
    expect(isReachableFromSecureContext("not a url")).toBe(false)
  })
})
