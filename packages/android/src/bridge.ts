/**
 * The typed channel between the renderer and the Android host.
 *
 * M3 used a three-method `addJavascriptInterface` object, which was appropriate
 * for three fire-and-forget calls. M4 needs return values, binary payloads and
 * host-initiated events, so this replaces it with the request/response channel
 * described in docs/ARCHITECTURE.md 2.4.
 *
 * Transport is `WebMessagePort` rather than `addJavascriptInterface`: the latter
 * exposes a reflective surface to every script in the WebView, and the surface
 * is no longer small enough for that to be comfortable.
 *
 * Everything here is deliberately explicit rather than generic. The bridge is
 * the one place web content meets native capability, so its shape should be
 * readable in one sitting and validated on the Kotlin side against the same list.
 */

/** Requests the renderer can make of the host. */
export type BridgeRequest =
  // --- persistent preferences -------------------------------------------------
  | { method: "store.get"; params: { name: string; key: string } }
  | { method: "store.set"; params: { name: string; key: string; value: string } }
  | { method: "store.remove"; params: { name: string; key: string } }
  | { method: "store.clear"; params: { name: string } }
  | { method: "store.keys"; params: { name: string } }
  // --- prompt drafts and their blobs -----------------------------------------
  | { method: "draft.get"; params: { key: string } }
  | { method: "draft.set"; params: { key: string; value: string } }
  | { method: "draft.remove"; params: { key: string } }
  | { method: "draft.putBlob"; params: { base64: string; type: string } }
  | { method: "draft.getBlob"; params: { id: string } }
  // --- system integration -----------------------------------------------------
  | { method: "clipboard.readText"; params?: never }
  | { method: "clipboard.writeText"; params: { text: string } }
  | { method: "clipboard.readImage"; params?: never }
  | { method: "share"; params: { text: string; title?: string } }
  | { method: "openExternal"; params: { url: string } }
  /**
   * Opens the system folder picker, copies the chosen folder into a project, and
   * resolves to the project's **real path** - not the `content://` URI the picker
   * returns, which the Node runtime cannot open. See ADR-0024.
   */
  | { method: "pickDirectory"; params: { title?: string } }
  // --- projects ---------------------------------------------------------------
  | { method: "project.list"; params?: never }
  | { method: "project.create"; params: { name: string } }
  | { method: "project.delete"; params: { slug: string } }
  | { method: "notify"; params: { title: string; body: string; tag: string } }
  | { method: "restart"; params?: never }
  // --- the on-device runtime --------------------------------------------------
  /**
   * Starts the local server if it is not already running and resolves once it
   * answers a health check. Rejects if it cannot be started, which is how the
   * renderer knows to offer a remote server instead.
   */
  | { method: "runtime.await"; params?: never }
  | { method: "runtime.stop"; params?: never }
  // --- navigation -------------------------------------------------------------
  /**
   * Answers a `back` event. The token identifies which press is being answered,
   * so a reply that arrives after the host gave up cannot exit a later screen.
   */
  | { method: "back.handled"; params: { token: number; handled: boolean } }
  // --- host facts -------------------------------------------------------------
  | { method: "host.info"; params?: never }
  | { method: "defaultServer.get"; params?: never }
  | { method: "defaultServer.set"; params: { url: string | null } }

export type BridgeMethod = BridgeRequest["method"]

/** The states the on-device runtime reports. Mirrors RuntimeState in Kotlin. */
export type RuntimeStateName = "stopped" | "starting" | "ready" | "degraded" | "failed" | "stopping"

/** A project on the device. `path` is a real POSIX path the runtime can work in. */
export type AndroidProject = { slug: string; name: string; path: string }

/** What `pickDirectory` resolves to once the chosen folder has been imported. */
export type ImportedProject = AndroidProject & {
  files: number
  skippedLarge: string[]
  skippedDirectories: string[]
  /** False when the import hit a size limit or skipped a file; see ADR-0024. */
  complete: boolean
}

/** What `runtime.await` resolves to. The same shape upstream calls ServerReadyData. */
export type RuntimeHandle = { url: string; username?: string; password?: string }

/** Events the host pushes without being asked. */
export type BridgeEvent =
  | { event: "notification.clicked"; tag: string }
  | { event: "lifecycle"; state: "resumed" | "paused" | "stopped" }
  /** Soft keyboard height in CSS pixels; 0 when hidden. */
  | { event: "keyboard"; height: number }
  /**
   * The hardware/gesture back was pressed. The renderer must answer with
   * `back.handled` - promptly, since the host exits the app if it does not.
   */
  | { event: "back"; token: number }
  /**
   * The on-device runtime changed state. Carries the state name only - never the
   * server password, which exists to be handed to the client and nowhere else.
   */
  | { event: "runtime.state"; state: RuntimeStateName; reason?: string }

export type BridgeEventName = BridgeEvent["event"]

type Envelope =
  | { id: number; ok: true; result: unknown }
  | { id: number; ok: false; error: { code: string; message: string } }
  | ({ id?: never } & BridgeEvent)

export class BridgeError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = "BridgeError"
  }
}

type Pending = { resolve: (value: unknown) => void; reject: (reason: unknown) => void }

/**
 * Requests time out rather than hanging forever.
 *
 * A dropped reply would otherwise leave the UI waiting on a promise that never
 * settles, which on a phone looks identical to the app being frozen.
 */
const REQUEST_TIMEOUT_MS = 15_000

export interface Bridge {
  request<T>(request: BridgeRequest): Promise<T>
  on<E extends BridgeEventName>(event: E, handler: (payload: Extract<BridgeEvent, { event: E }>) => void): () => void
  /**
   * Binds a replacement port from the host, failing anything still in flight on
   * the old one. See the implementation for why in-flight calls are rejected
   * rather than left to time out.
   */
  attach(port: MessagePort): void
  readonly available: boolean
}

/**
 * Connects to the host port that Kotlin posts into the page on load.
 *
 * Returns an unavailable bridge when there is no host - `vite dev` in a desktop
 * browser, or a unit test - so callers can fall back rather than crash.
 */
export function createBridge(initial?: MessagePort): Bridge {
  const pending = new Map<number, Pending>()
  const listeners = new Map<string, Set<(payload: never) => void>>()
  let nextId = 1
  let port: MessagePort | undefined

  const receive = (message: MessageEvent) => {
    let envelope: Envelope
    try {
      envelope = typeof message.data === "string" ? JSON.parse(message.data) : (message.data as Envelope)
    } catch {
      return
    }
    if (!envelope || typeof envelope !== "object") return

    if ("event" in envelope && envelope.event) {
      const handlers = listeners.get(envelope.event)
      if (handlers) for (const handler of handlers) (handler as (p: Envelope) => void)(envelope)
      return
    }

    if (typeof envelope.id !== "number") return
    const waiting = pending.get(envelope.id)
    if (!waiting) return
    pending.delete(envelope.id)
    if (envelope.ok) waiting.resolve(envelope.result)
    else waiting.reject(new BridgeError(envelope.error?.code ?? "Unknown", envelope.error?.message ?? "bridge error"))
  }

  /**
   * Binds a port, replacing any previous one.
   *
   * The host hands over a fresh channel whenever it has to rebuild one. Anything
   * still in flight went out on the old port and can never be answered now, so
   * it is failed immediately rather than left to time out - fifteen seconds of
   * apparent hang is indistinguishable from a frozen app, and the caller can
   * retry a rejection.
   */
  const attach = (next: MessagePort) => {
    for (const [, waiting] of pending) {
      waiting.reject(new BridgeError("Reconnected", "the host replaced the bridge before this call was answered"))
    }
    pending.clear()

    if (port) port.onmessage = null
    port = next
    port.onmessage = receive
    port.start?.()
  }

  if (initial) attach(initial)

  return {
    get available() {
      return !!port
    },
    attach,

    request<T>(request: BridgeRequest): Promise<T> {
      if (!port) return Promise.reject(new BridgeError("Unavailable", "no Android host bridge in this environment"))

      const id = nextId++
      return new Promise<T>((resolve, reject) => {
        const timer = setTimeout(() => {
          pending.delete(id)
          reject(new BridgeError("Timeout", `${request.method} did not answer within ${REQUEST_TIMEOUT_MS}ms`))
        }, REQUEST_TIMEOUT_MS)

        pending.set(id, {
          resolve: (value) => {
            clearTimeout(timer)
            resolve(value as T)
          },
          reject: (reason) => {
            clearTimeout(timer)
            reject(reason)
          },
        })

        port.postMessage(JSON.stringify({ id, ...request }))
      })
    },

    on(event, handler) {
      let handlers = listeners.get(event)
      if (!handlers) {
        handlers = new Set()
        listeners.set(event, handlers)
      }
      handlers.add(handler as (p: never) => void)
      return () => {
        handlers.delete(handler as (p: never) => void)
      }
    },
  }
}
