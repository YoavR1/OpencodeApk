/**
 * What the Android back gesture does.
 *
 * On a phone back is the primary navigation control, and users expect one
 * consistent rule: it undoes the last thing that took over the screen. A dialog
 * closes before the drawer, the drawer closes before the route changes, and only
 * an unhandled press leaves the app.
 *
 * This lives in the renderer rather than in Kotlin because that is where the
 * state being undone actually is. `MainActivity` cannot see an open dialog, and
 * `WebView.canGoBack()` is always false here - the app routes through a memory
 * history precisely so back does not navigate the document (see main.tsx).
 */

/** Returns true when the press was consumed and must not reach the host. */
export type BackHandler = () => boolean

export type BackDispatcher = {
  /**
   * Registers a handler. The most recently registered runs first, so UI that
   * opened last is dismissed first.
   */
  register(handler: BackHandler): () => void
  /** Runs handlers until one consumes the press. */
  dispatch(): boolean
}

export function createBackDispatcher(): BackDispatcher {
  const handlers: BackHandler[] = []
  return {
    register(handler) {
      handlers.push(handler)
      return () => {
        const index = handlers.indexOf(handler)
        if (index !== -1) handlers.splice(index, 1)
      }
    },
    dispatch() {
      for (let i = handlers.length - 1; i >= 0; i--) {
        // A throwing handler must not strand the user on this screen: treat it
        // as declining and let the next one - ultimately the host - take over.
        try {
          if (handlers[i]()) return true
        } catch {}
      }
      return false
    },
  }
}

/**
 * Closes the topmost open dialog, if there is one.
 *
 * Upstream dismisses its dialogs from their own Escape handlers rather than
 * through a shared stack, so this reproduces the keypress rather than reaching
 * into each component. Dialogs are matched by ARIA role, which the underlying
 * primitive sets on every one of them.
 */
export function dismissTopDialog(doc: Document = document): boolean {
  const dialogs = doc.querySelectorAll<HTMLElement>('[role="dialog"],[role="alertdialog"]')
  const top = dialogs[dialogs.length - 1]
  if (!top) return false
  // The constructor comes from the document's own realm rather than the ambient
  // global: that is what makes the event dispatchable into this document, and it
  // keeps the function usable where no DOM globals exist.
  const KeyboardEventCtor = doc.defaultView?.KeyboardEvent
  if (!KeyboardEventCtor) return false

  // Duck-typed rather than `instanceof HTMLElement`, for the same reason.
  const active = doc.activeElement as HTMLElement | null
  const target = active && typeof active.dispatchEvent === "function" && top.contains(active) ? active : top
  target.dispatchEvent(
    new KeyboardEventCtor("keydown", { key: "Escape", code: "Escape", bubbles: true, cancelable: true }),
  )
  return true
}

/**
 * Mirrors the position of `createMemoryHistory`'s cursor.
 *
 * The router keeps its entry list private, so back has no way to ask whether
 * there is anywhere to go. Getting that wrong is worse than it sounds: claiming
 * a press was handled when the navigation does nothing leaves the user pressing
 * back on a screen that never changes.
 *
 * So this tracks the two movements the history actually makes, matching its
 * implementation rather than approximating it:
 *  - a push moves forward one and discards any forward entries;
 *  - a replace does not move at all, which is why restoring the last route at
 *    startup does not make back look available on the first screen;
 *  - `go(n)` moves by n, clamped to the ends, and is how the router's own
 *    `navigate(-1)` travels - counting only pushes would drift out of step
 *    with it.
 */
export function createHistoryDepth() {
  let index = 0
  let last = 0

  return {
    get index() {
      return index
    },
    get canGoBack() {
      return index > 0
    },
    pushed() {
      index += 1
      last = index
    },
    /** Replacing the current entry does not move the cursor. */
    replaced() {},
    /** Applies a relative move, clamped exactly as the history clamps it. */
    moved(delta: number) {
      index = Math.min(Math.max(index + delta, 0), last)
    },
  }
}
