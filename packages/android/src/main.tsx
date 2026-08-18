// @refresh reload

import {
  AppBaseProviders,
  AppInterface,
  PlatformProvider,
  ServerConnection,
  loadLocaleDict,
  normalizeLocale,
  type Locale,
} from "@opencode-ai/app"
import { Splash } from "@opencode-ai/ui/logo"
import { MemoryRouter, createMemoryHistory, type BaseRouterProps } from "@solidjs/router"
import { Show, createResource, onCleanup } from "solid-js"
import { render } from "solid-js/web"
import { createAndroidPlatform } from "./platform"
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
  throw new Error('Android renderer: #root missing from index.html')
}

/**
 * A memory router, as on desktop.
 *
 * The WebView has a real history stack that Android's back button drives, so a
 * browser-history router would let back navigate the *document* out from under
 * the app. Keeping routing in memory leaves MainActivity's
 * OnBackPressedDispatcher as the single owner of back.
 */
const LAST_ROUTE_KEY = "opencode.android.last-route"

function readLastRoute() {
  try {
    const value = localStorage.getItem(LAST_ROUTE_KEY)
    if (value?.startsWith("/") && !value.startsWith("//")) return value
  } catch {}
  return "/"
}

function AndroidRouter(props: BaseRouterProps) {
  const history = createMemoryHistory()
  const initial = readLastRoute()
  if (initial !== "/") history.set({ value: initial, replace: true, scroll: false })
  onCleanup(
    history.listen((value) => {
      try {
        localStorage.setItem(LAST_ROUTE_KEY, value)
      } catch {}
    }),
  )
  return <MemoryRouter {...props} history={history} />
}

function LoadingSplash() {
  return (
    <div class="h-dvh w-screen flex flex-col items-center justify-center bg-background-base">
      <Splash class="w-16 h-20 opacity-50 animate-pulse" />
    </div>
  )
}

function AndroidRoot() {
  const platform = createAndroidPlatform()

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
        <Show when={!locale.loading} fallback={<LoadingSplash />}>
          <AppInterface
            defaultServer={ServerConnection.Key.make("")}
            servers={[]}
            router={AndroidRouter}
          />
        </Show>
      </AppBaseProviders>
    </PlatformProvider>
  )
}

render(() => <AndroidRoot />, root)
