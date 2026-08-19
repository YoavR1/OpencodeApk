// @refresh reload

import {
  AppBaseProviders,
  AppInterface,
  PlatformProvider,
  ServerConnection,
  loadLocaleDict,
  normalizeLocale,
  useLayout,
  type Locale,
} from "@opencode-ai/app"
import { Splash } from "@opencode-ai/ui/logo"
import { MemoryRouter, createMemoryHistory, type BaseRouterProps } from "@solidjs/router"
import { Show, createResource, onCleanup } from "solid-js"
import { render } from "solid-js/web"
import { createBackDispatcher, createHistoryDepth, dismissTopDialog, type BackDispatcher } from "./back"
import { createBridge, type Bridge } from "./bridge"
import { createAndroidDraftStore } from "./drafts"
import { revealFocusedInput } from "./focus"
import { createAndroidPlatform, readHostInfo } from "./platform"
import { startupServerKey } from "./server"
import "./styles.css"

/**
 * Android renderer entry point.
 *
 * This mirrors `packages/desktop/src/renderer/index.tsx`: build a `Platform`,
 * wrap the shared app in `PlatformProvider` / `AppBaseProviders` /
 * `AppInterface`, and let upstream own everything below that. No screen is
 * reimplemented here.
 *
 * M3 scope: the UI renders and routes with **no server connected**. Connecting
 * to a real server is M5 (remote) and M7 (on-device); until then `servers` is
 * empty and the app is expected to show its own "no server" state.
 */

const root = document.getElementById("root")
if (!(root instanceof HTMLElement)) {
  throw new Error("Android renderer: #root missing from index.html")
}

/**
 * Waits for the host to hand over its end of the message channel.
 *
 * The Kotlin side posts the port once the document has loaded, so this may
 * resolve before or after the bundle runs; both orders are handled. Outside the
 * Android host it resolves to an unavailable bridge after a short wait, so
 * `vite dev` in a desktop browser still boots.
 */
const HANDSHAKE = "opencode:bridge-port"
const HANDSHAKE_TIMEOUT_MS = 1_500

function connectBridge(): Promise<Bridge> {
  return new Promise((resolve) => {
    let settled = false
    const finish = (port?: MessagePort) => {
      if (settled) return
      settled = true
      window.removeEventListener("message", onMessage)
      clearTimeout(timer)
      resolve(createBridge(port))
    }

    const onMessage = (event: MessageEvent) => {
      if (event.data !== HANDSHAKE) return
      finish(event.ports?.[0])
    }

    window.addEventListener("message", onMessage)
    const timer = setTimeout(() => finish(undefined), HANDSHAKE_TIMEOUT_MS)
  })
}

/**
 * Publishes the soft keyboard height as a CSS variable.
 *
 * The Activity already pads the WebView by the IME inset, so layout is correct
 * without this. The variable is exposed anyway so the shared UI can know how
 * much room the keyboard took, and the event doubles as the cue to scroll the
 * focused field back into view (see focus.ts).
 */
function trackKeyboard(bridge: Bridge) {
  const apply = (height: number) => {
    document.documentElement.style.setProperty("--android-keyboard-height", `${height}px`)
    document.documentElement.dataset.keyboard = height > 0 ? "open" : "closed"
  }
  apply(0)
  return bridge.on("keyboard", ({ height }) => {
    apply(height)
    // After the next frame, so the reflow from the Activity's new padding has
    // landed and the field's position is the one the user will see.
    if (height > 0) requestAnimationFrame(() => revealFocusedInput())
  })
}

/**
 * Flushes pending work when the app leaves the foreground.
 *
 * Android can kill a backgrounded process without further warning, so `paused`
 * is the last reliable moment to persist anything. Dispatched as a DOM event so
 * the shared UI can react without knowing it is on Android.
 */
function trackLifecycle(bridge: Bridge) {
  return bridge.on("lifecycle", ({ state }) => {
    document.documentElement.dataset.appState = state
    window.dispatchEvent(new CustomEvent("opencode:lifecycle", { detail: { state } }))
  })
}

/**
 * A memory router, as on desktop.
 *
 * A browser-history router would let the back gesture navigate the *document*
 * out from under the app. Routing in memory keeps the WebView on one document
 * and makes this file the owner of what back means; `MainActivity` offers the
 * press here rather than deciding for itself. See back.ts.
 */
const LAST_ROUTE_KEY = "opencode.android.last-route"

function readLastRoute() {
  try {
    const value = localStorage.getItem(LAST_ROUTE_KEY)
    if (value?.startsWith("/") && !value.startsWith("//")) return value
  } catch {}
  return "/"
}

/**
 * A memory history that also reports where its cursor is.
 *
 * The router owns its entry list privately, so the position is tracked from the
 * two calls that move it: `set` (push or replace) and `go` (relative move,
 * which is how the router's own `navigate(-1)` travels). Watching only `set`
 * would drift, and back would then claim presses that navigate nowhere.
 */
function createTrackedHistory() {
  const history = createMemoryHistory()
  const cursor = createHistoryDepth()
  const set = history.set.bind(history)
  const go = history.go.bind(history)

  // Both entry points are wrapped, and before the history is handed to the
  // router: MemoryRouter reads `set` and `go` off the object once, at
  // construction, so a later override would never be seen.
  history.set = (options) => {
    if (options.replace) cursor.replaced()
    else cursor.pushed()
    set(options)
  }
  history.go = (delta) => {
    cursor.moved(delta)
    go(delta)
  }

  return {
    history,
    /** Pops one entry. Returns false on the first screen, where back must exit. */
    pop() {
      if (!cursor.canGoBack) return false
      // Through the wrapper, so the cursor and the history stay in step.
      history.go(-1)
      return true
    },
  }
}

function AndroidRouter(props: BaseRouterProps & { back: BackDispatcher }) {
  const { history, pop } = createTrackedHistory()
  const initial = readLastRoute()
  if (initial !== "/") history.set({ value: initial, replace: true, scroll: false })

  // Registered first, so it is consulted last: dialogs and the drawer unwind
  // before the route does.
  onCleanup(props.back.register(pop))
  onCleanup(
    history.listen((value) => {
      try {
        localStorage.setItem(LAST_ROUTE_KEY, value)
      } catch {}
    }),
  )
  return <MemoryRouter {...props} history={history} />
}

/**
 * Registers the back behaviour that needs the shared UI's own state.
 *
 * Mounted through `AppInterface`'s `serverScoped` slot, which upstream renders
 * inside `LayoutProvider` - so this reads the real drawer state rather than
 * duplicating it. Registration order matters: these run after the router's, and
 * are therefore consulted before it.
 */
function AndroidBackHandlers(props: { back: BackDispatcher }) {
  const layout = useLayout()

  onCleanup(
    props.back.register(() => {
      if (!layout.mobileSidebar.opened()) return false
      layout.mobileSidebar.hide()
      return true
    }),
  )
  // Registered last, so an open dialog is the first thing back closes.
  onCleanup(props.back.register(() => dismissTopDialog()))

  return null
}

/** Answers the host's back events. */
function answerBack(bridge: Bridge, back: BackDispatcher) {
  return bridge.on("back", ({ token }) => {
    // The host exits the app if no answer arrives, so one is sent even if
    // dispatch throws - hence the `finally`. Declining is the safe default: it
    // leaves the app, which is recoverable, rather than swallowing the press.
    let handled = false
    try {
      handled = back.dispatch()
    } finally {
      void bridge.request({ method: "back.handled", params: { token, handled } }).catch(() => {})
    }
  })
}

function LoadingSplash() {
  return (
    <div class="h-dvh w-screen flex flex-col items-center justify-center bg-background-base">
      <Splash class="w-16 h-20 opacity-50 animate-pulse" />
    </div>
  )
}

function AndroidRoot(props: { bridge: Bridge }) {
  const platform = createAndroidPlatform(props.bridge, createAndroidDraftStore(props.bridge))
  const back = createBackDispatcher()

  // The version is a host fact, so it arrives asynchronously.
  void readHostInfo(props.bridge).then((info) => {
    if (info?.versionName) Object.assign(platform, { version: info.versionName })
  })

  onCleanup(trackKeyboard(props.bridge))
  onCleanup(trackLifecycle(props.bridge))
  onCleanup(answerBack(props.bridge, back))

  /**
   * The server to start on, read from the persisted default.
   *
   * Mirrors what desktop does with the same `Platform.getDefaultServer` hook.
   * `startupServerKey` guarantees a non-empty result, without which
   * `ServerProvider` renders nothing at all - see server.ts.
   */
  const [startupServer] = createResource(async () => {
    try {
      return (await platform.getDefaultServer?.()) ?? null
    } catch {
      // A store that cannot be read must not stop the app from starting; the
      // user lands on the no-server state and can pick one.
      return null
    }
  })

  const [locale] = createResource(async () => {
    const raw = platform.storage?.("opencode.global.dat")
    if (!raw) return undefined
    const current = await raw.getItem("language")
    const parsed = typeof current === "string" ? current.match(/"locale"\s*:\s*"([^"]+)"/)?.[1] : undefined
    if (!parsed) return undefined
    const next = normalizeLocale(parsed)
    if (next !== "en") await loadLocaleDict(next)
    return next satisfies Locale
  })

  return (
    <PlatformProvider value={platform}>
      <AppBaseProviders locale={locale.latest}>
        <Show when={!locale.loading && !startupServer.loading} fallback={<LoadingSplash />}>
          <AppInterface
            defaultServer={ServerConnection.Key.make(startupServerKey(startupServer.latest))}
            router={(routerProps) => <AndroidRouter {...routerProps} back={back} />}
            serverScoped={<AndroidBackHandlers back={back} />}
          />
        </Show>
      </AppBaseProviders>
    </PlatformProvider>
  )
}

void connectBridge().then((bridge) => {
  render(() => <AndroidRoot bridge={bridge} />, root)
})
