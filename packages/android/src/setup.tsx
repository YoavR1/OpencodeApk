import { createSignal, Show } from "solid-js"
import { isReachableFromSecureContext, normalizeServerUrl, type StoredServer } from "./server"

/**
 * First-run server setup.
 *
 * The shared UI cannot render without a server. `LayoutProvider`'s init reads
 * `serverSdk().scope`, and with an empty server list there is no server context
 * for it to read - the app throws before it draws anything. Upstream never hits
 * this because web is served *by* its server and desktop always has a sidecar;
 * Android in remote mode is the first case with genuinely none.
 *
 * So this stands in front of `AppInterface` until there is one. It is
 * deliberately the smallest thing that can produce a working connection -
 * upstream's own server dialog manages everything from the second server
 * onwards, and this is not a place to grow a second one.
 */

/** Probes reachability before saving, so a wrong entry is caught while it can still be corrected. */
async function probe(server: StoredServer): Promise<string | undefined> {
  const headers: Record<string, string> = {}
  if (server.password) {
    headers.Authorization = `Basic ${btoa(`${server.username || "opencode"}:${server.password}`)}`
  }
  try {
    const response = await fetch(`${server.url}/global/health`, {
      headers,
      signal: AbortSignal.timeout(8000),
    })
    if (response.status === 401) return "The server rejected those credentials."
    if (!response.ok) return `The server answered ${response.status}.`
    return undefined
  } catch {
    // A blocked mixed-content request and an unreachable host look identical
    // here, which is why the scheme is checked separately before this runs.
    return "Could not reach that server. Check the address and that it is running."
  }
}

export function AndroidServerSetup(props: { onConnected: (server: StoredServer) => void }) {
  const [url, setUrl] = createSignal("")
  const [username, setUsername] = createSignal("")
  const [password, setPassword] = createSignal("")
  const [error, setError] = createSignal<string>()
  const [busy, setBusy] = createSignal(false)

  const connect = async () => {
    setError(undefined)

    const normalized = normalizeServerUrl(url())
    if (!normalized) return setError("That does not look like a server address.")

    if (!isReachableFromSecureContext(normalized)) {
      return setError(
        "The app is served over https, and a browser will not let it call a plain http address. " +
          "Use https, or run the server on this device so it can be reached at 127.0.0.1.",
      )
    }

    const server: StoredServer = {
      url: normalized,
      username: username().trim() || undefined,
      password: password() || undefined,
    }

    setBusy(true)
    const failure = await probe(server)
    setBusy(false)
    if (failure) return setError(failure)

    props.onConnected(server)
  }

  const field =
    "w-full rounded-lg bg-surface-raised-base px-3 py-3 text-14-regular text-text-base " +
    "border border-border-base outline-none focus:border-border-strong"

  return (
    <div class="h-dvh w-screen flex flex-col justify-center gap-5 bg-background-base px-6">
      <div class="flex flex-col gap-1">
        <h1 class="text-18-medium text-text-base">Connect to a server</h1>
        <p class="text-13-regular text-text-weak">
          OpenCode runs on a machine you control. Enter its address to get started.
        </p>
      </div>

      <div class="flex flex-col gap-3">
        <input
          class={field}
          type="url"
          inputmode="url"
          autocapitalize="none"
          autocorrect="off"
          spellcheck={false}
          placeholder="127.0.0.1:4096"
          value={url()}
          onInput={(event) => setUrl(event.currentTarget.value)}
        />
        <input
          class={field}
          type="text"
          autocapitalize="none"
          autocorrect="off"
          spellcheck={false}
          placeholder="Username (optional)"
          value={username()}
          onInput={(event) => setUsername(event.currentTarget.value)}
        />
        <input
          class={field}
          type="password"
          placeholder="Password (optional)"
          value={password()}
          onInput={(event) => setPassword(event.currentTarget.value)}
        />
      </div>

      <Show when={error()}>
        {(message) => (
          <p role="alert" class="text-13-regular text-text-danger">
            {message()}
          </p>
        )}
      </Show>

      <button
        type="button"
        class="w-full rounded-lg bg-surface-raised-stronger px-3 py-3 text-14-medium text-text-base disabled:opacity-50"
        disabled={busy()}
        onClick={() => void connect()}
      >
        {busy() ? "Connecting…" : "Connect"}
      </button>
    </div>
  )
}
