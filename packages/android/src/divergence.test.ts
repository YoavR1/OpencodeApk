import { describe, expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"

/**
 * Guards the edits this project makes to upstream files.
 *
 * Every entry in `docs/UPSTREAM_SYNC.md` has to survive a `git merge upstream/dev`
 * and be re-applied by hand if the merge drops it. A lost divergence does not
 * announce itself: the build stays green, the types still check, and the app
 * simply stops working in a way nobody sees until it is on a phone.
 *
 * So each one gets a test whose failure message says what breaks.
 */

const here = fileURLToPath(new URL(".", import.meta.url))
const read = (path: string) => readFileSync(`${here}../../${path}`, "utf8")

describe("D1/D2 - the Platform union knows about Android", () => {
  const source = () => read("app/src/context/platform.tsx")

  test("PlatformName includes android", () => {
    // Without it the adapter cannot be typed as a Platform at all.
    expect(source()).toContain('"web" | "desktop" | "android"')
  })

  test("the exported union has an android arm", () => {
    expect(source()).toMatch(/\{\s*platform:\s*"android"/)
  })
})

describe("D3 - storage is chosen by capability, not identity", () => {
  const source = () => read("app/src/utils/persist.ts")

  test("the native store is used whenever one is supplied", () => {
    // Reverting this sends Android's persistence back to WebView localStorage,
    // which is silently cleared when the user clears app data - taking the
    // stored servers and drafts with it.
    expect(source()).toContain("hasNativeStorage")
  })

  test("the decision is a capability check, both times it is made", () => {
    // Both call sites, and both must read the capability rather than the name.
    // The remaining `=== "desktop"` checks in this file are about windowID -
    // per-window storage scoping, which really is desktop-only - so they stay.
    const assignments = source()
      .split("\n")
      .filter((line) => line.includes("const hasNativeStorage"))
      .map((line) => line.trim())

    expect(assignments).toEqual(["const hasNativeStorage = !!platform?.storage", "const hasNativeStorage = !!platform.storage"])
  })
})

describe("D6 - Android defaults the titlebar within thumb reach", () => {
  test("the default is platform-aware", () => {
    const source = read("app/src/context/settings.tsx")
    expect(source).toMatch(/platform\.platform === "android"\s*\?\s*"bottom"/)
  })
})

describe("D7 - the server accepts the Android WebView origin", () => {
  const ORIGIN = "https://appassets.androidplatform.net"

  test("the CORS allowlist includes the asset origin", () => {
    // The one divergence whose loss makes the app completely non-functional
    // against a remote server: Authorization makes every request preflighted,
    // and a rejected preflight blocks all of them.
    expect(read("server/src/cors.ts")).toContain(ORIGIN)
  })

  test("the allowlisted origin is the one the app actually serves from", () => {
    // Three files have to agree on this string. If the WebView origin is ever
    // changed, the CORS entry has to move with it.
    const kotlin = read("../apps/android/app/src/main/kotlin/ai/opencode/android/web/WebOrigin.kt")
    const domain = kotlin.match(/DOMAIN[^=]*=\s*"([^"]+)"/)?.[1]
    expect(domain).toBe("appassets.androidplatform.net")
    expect(`https://${domain}`).toBe(ORIGIN)
  })

  test("the entry is an exact match, not a prefix", () => {
    // A startsWith would also accept https://appassets.androidplatform.net.evil.com
    expect(read("server/src/cors.ts")).toContain(`input === "${ORIGIN}"`)
  })
})
