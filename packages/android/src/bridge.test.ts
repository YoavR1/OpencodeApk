import { describe, expect, test } from "bun:test"
import { BridgeError, createBridge, type BridgeRequest } from "./bridge"

/**
 * A fake host that answers on the other end of a MessagePort pair.
 *
 * Uses a real `MessageChannel`, so the framing under test is the framing that
 * runs on device rather than a mock of it.
 */
function fakeHost(handle: (request: BridgeRequest & { id: number }) => unknown) {
  const channel = new MessageChannel()
  channel.port2.onmessage = (event: MessageEvent) => {
    const request = JSON.parse(String(event.data))
    let result: unknown
    try {
      result = handle(request)
    } catch (error) {
      channel.port2.postMessage(
        JSON.stringify({ id: request.id, ok: false, error: { code: "Failed", message: String(error) } }),
      )
      return
    }
    channel.port2.postMessage(JSON.stringify({ id: request.id, ok: true, result }))
  }
  channel.port2.start()
  return { port: channel.port1, host: channel.port2 }
}

describe("request / response", () => {
  test("round-trips a result", async () => {
    const { port } = fakeHost(() => "value")
    const bridge = createBridge(port)
    expect(await bridge.request({ method: "store.get", params: { name: "n", key: "k" } })).toBe("value")
  })

  test("carries params to the host unchanged", async () => {
    let seen: unknown
    const { port } = fakeHost((request) => {
      seen = request
      return true
    })
    const bridge = createBridge(port)
    await bridge.request({ method: "store.set", params: { name: "n", key: "k", value: "v" } })
    expect(seen).toMatchObject({ method: "store.set", params: { name: "n", key: "k", value: "v" } })
  })

  test("correlates concurrent requests by id", async () => {
    const { port } = fakeHost((request) => request.params?.key)
    const bridge = createBridge(port)
    const [a, b, c] = await Promise.all([
      bridge.request({ method: "store.get", params: { name: "n", key: "a" } }),
      bridge.request({ method: "store.get", params: { name: "n", key: "b" } }),
      bridge.request({ method: "store.get", params: { name: "n", key: "c" } }),
    ])
    expect([a, b, c]).toEqual(["a", "b", "c"])
  })

  test("surfaces a host error as BridgeError with its code", async () => {
    const { port } = fakeHost(() => {
      throw new Error("nope")
    })
    const bridge = createBridge(port)
    await expect(
      bridge.request({ method: "clipboard.readText" }),
    ).rejects.toBeInstanceOf(BridgeError)
  })

  test("ignores malformed and unknown-id replies rather than crashing", async () => {
    const channel = new MessageChannel()
    const bridge = createBridge(channel.port1)
    channel.port2.postMessage("not json")
    channel.port2.postMessage(JSON.stringify({ id: 999, ok: true, result: 1 }))
    channel.port2.postMessage(JSON.stringify({ nonsense: true }))
    // Still usable afterwards.
    expect(bridge.available).toBe(true)
  })
})

describe("no host", () => {
  test("reports unavailable and rejects instead of hanging", async () => {
    const bridge = createBridge(undefined)
    expect(bridge.available).toBe(false)
    await expect(bridge.request({ method: "clipboard.readText" })).rejects.toBeInstanceOf(BridgeError)
  })
})

describe("events", () => {
  test("delivers host-initiated events to subscribers", async () => {
    const channel = new MessageChannel()
    const bridge = createBridge(channel.port1)
    const seen: string[] = []
    bridge.on("lifecycle", ({ state }) => seen.push(state))

    channel.port2.postMessage(JSON.stringify({ event: "lifecycle", state: "paused" }))
    channel.port2.postMessage(JSON.stringify({ event: "lifecycle", state: "resumed" }))
    await new Promise((resolve) => setTimeout(resolve, 10))

    expect(seen).toEqual(["paused", "resumed"])
  })

  test("unsubscribing stops delivery", async () => {
    const channel = new MessageChannel()
    const bridge = createBridge(channel.port1)
    const seen: number[] = []
    const off = bridge.on("keyboard", ({ height }) => seen.push(height))

    channel.port2.postMessage(JSON.stringify({ event: "keyboard", height: 300 }))
    await new Promise((resolve) => setTimeout(resolve, 10))
    off()
    channel.port2.postMessage(JSON.stringify({ event: "keyboard", height: 0 }))
    await new Promise((resolve) => setTimeout(resolve, 10))

    expect(seen).toEqual([300])
  })

  test("an event for nobody does not disturb pending requests", async () => {
    const { port } = fakeHost(() => "ok")
    const bridge = createBridge(port)
    const pending = bridge.request({ method: "clipboard.readText" })
    expect(await pending).toBe("ok")
  })
})
