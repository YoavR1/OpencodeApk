import { describe, expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"

/**
 * The bridge contract is written twice - once as a TypeScript union in
 * `bridge.ts`, once as a `Set<String>` in Kotlin - because neither language can
 * import the other. Nothing at compile time notices when they drift, and the
 * symptom on device is a capability that silently fails at runtime on a method
 * name nobody typo-checked.
 *
 * This test reads both files and compares them.
 */

/**
 * Newline normalisation.
 *
 * This repository is edited from both Linux and Windows. A working tree
 * checked out with CRLF would otherwise fail these file-reading tests locally
 * while passing in CI - the worst split there is, because the local run is the
 * one a person looks at first.
 */
const CRLF = String.fromCharCode(13, 10)
const LF = String.fromCharCode(10)
const here = fileURLToPath(new URL(".", import.meta.url))
const KOTLIN = `${here}../../../apps/android/app/src/main/kotlin/ai/opencode/android/bridge/BridgeContract.kt`
const TYPESCRIPT = `${here}bridge.ts`

/** The method names in Kotlin's `BridgeContract.METHODS`. */
function kotlinMethods(): string[] {
  const source = readFileSync(KOTLIN, "utf8").split(CRLF).join(LF)
  const block = source.match(/val METHODS: Set<String> = setOf\(([\s\S]*?)\n {4}\)/)
  if (!block) throw new Error(`could not find METHODS in ${KOTLIN}`)
  return [...block[1].matchAll(/"([^"]+)"/g)].map((m) => m[1])
}

/** The `method:` literals in the TypeScript `BridgeRequest` union. */
function typescriptMethods(): string[] {
  const source = readFileSync(TYPESCRIPT, "utf8").split(CRLF).join(LF)
  const block = source.match(/export type BridgeRequest =([\s\S]*?)\n\n/)
  if (!block) throw new Error(`could not find BridgeRequest in ${TYPESCRIPT}`)
  return [...block[1].matchAll(/\{ method: "([^"]+)"/g)].map((m) => m[1])
}

describe("bridge contract", () => {
  test("both sides declare methods at all", () => {
    // Guards the parsing above: if a refactor changes either shape, this fails
    // loudly instead of silently comparing two empty lists.
    expect(kotlinMethods().length).toBeGreaterThan(10)
    expect(typescriptMethods().length).toBeGreaterThan(10)
  })

  test("the renderer sends no method the host will not answer", () => {
    const host = new Set(kotlinMethods())
    const unanswered = typescriptMethods().filter((method) => !host.has(method))
    expect(unanswered).toEqual([])
  })

  test("the host answers no method the renderer cannot send", () => {
    // Dead entries in METHODS are not harmful, but they are usually the visible
    // half of a rename that only landed on one side.
    const renderer = new Set(typescriptMethods())
    const unreachable = kotlinMethods().filter((method) => !renderer.has(method))
    expect(unreachable).toEqual([])
  })

  test("neither side declares a method twice", () => {
    for (const methods of [kotlinMethods(), typescriptMethods()]) {
      expect(methods.length).toBe(new Set(methods).size)
    }
  })
})
