import { createDraftStore } from "@opencode-ai/app"
import type { DraftStore } from "@opencode-ai/app"
import type { Bridge } from "./bridge"

/**
 * Prompt drafts, backed by the Android host.
 *
 * Split out from `platform.ts` on purpose. This is the only module that imports
 * a *value* from `@opencode-ai/app`; keeping it separate lets the adapter and
 * its tests stay free of that dependency, and puts the upstream wiring in the
 * composition root where it belongs.
 *
 * Drafts matter more on a phone than on a desktop: the process is killed
 * routinely while backgrounded, and losing a half-written prompt to an incoming
 * call is exactly what makes an app feel untrustworthy
 * (.claude/rules/android.md N7).
 */

/** Base64 for a Blob, without the `data:` prefix. */
async function toBase64(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer())
  let binary = ""
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}

function fromBase64(base64: string, type: string): Blob {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return new Blob([bytes], { type })
}

export function createAndroidDraftStore(bridge: Bridge): DraftStore {
  return createDraftStore({
    get: (key) => bridge.request<string | null>({ method: "draft.get", params: { key } }),
    set: (key, value) =>
      bridge.request<boolean>({ method: "draft.set", params: { key, value } }).then(() => undefined),
    remove: (key) => bridge.request<boolean>({ method: "draft.remove", params: { key } }).then(() => undefined),
    putBlob: async (blob) =>
      bridge.request<string>({
        method: "draft.putBlob",
        params: { base64: await toBase64(blob), type: blob.type },
      }),
    getBlob: async (id) => {
      const stored = await bridge.request<{ base64: string; type: string } | null>({
        method: "draft.getBlob",
        params: { id },
      })
      return stored ? fromBase64(stored.base64, stored.type) : null
    },
  })
}
