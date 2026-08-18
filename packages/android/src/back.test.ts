import { describe, expect, test } from "bun:test"
import { createBackDispatcher, createHistoryDepth, dismissTopDialog } from "./back"

describe("back dispatcher", () => {
  test("an unhandled press falls through to the host", () => {
    expect(createBackDispatcher().dispatch()).toBe(false)
  })

  test("the most recently registered handler runs first", () => {
    // What opened last is what back should undo.
    const order: string[] = []
    const back = createBackDispatcher()
    back.register(() => {
      order.push("outer")
      return true
    })
    back.register(() => {
      order.push("inner")
      return true
    })
    back.dispatch()
    expect(order).toEqual(["inner"])
  })

  test("a declining handler passes the press on", () => {
    const back = createBackDispatcher()
    back.register(() => true)
    back.register(() => false)
    expect(back.dispatch()).toBe(true)
  })

  test("every handler declining leaves the press unconsumed", () => {
    const back = createBackDispatcher()
    back.register(() => false)
    back.register(() => false)
    expect(back.dispatch()).toBe(false)
  })

  test("a handler that throws does not trap the user", () => {
    // If a bug in one handler could swallow back, the only way out of the app
    // would be the task switcher. It declines instead.
    const back = createBackDispatcher()
    back.register(() => {
      throw new Error("boom")
    })
    expect(back.dispatch()).toBe(false)
  })

  test("a throwing handler still lets the ones below it run", () => {
    const back = createBackDispatcher()
    back.register(() => true)
    back.register(() => {
      throw new Error("boom")
    })
    expect(back.dispatch()).toBe(true)
  })

  test("unregistering removes only that handler", () => {
    const back = createBackDispatcher()
    back.register(() => false)
    const dispose = back.register(() => true)
    dispose()
    expect(back.dispatch()).toBe(false)
  })

  test("unregistering twice is harmless", () => {
    const back = createBackDispatcher()
    const dispose = back.register(() => true)
    dispose()
    dispose()
    expect(back.dispatch()).toBe(false)
  })

  test("handlers are consulted again on the next press", () => {
    let open = true
    const back = createBackDispatcher()
    back.register(() => {
      if (!open) return false
      open = false
      return true
    })
    expect(back.dispatch()).toBe(true)
    expect(back.dispatch()).toBe(false)
  })
})

describe("history cursor", () => {
  test("the first screen cannot go back", () => {
    expect(createHistoryDepth().canGoBack).toBe(false)
  })

  test("a push makes back available", () => {
    const cursor = createHistoryDepth()
    cursor.pushed()
    expect(cursor.canGoBack).toBe(true)
  })

  test("restoring a route at startup does not make back available", () => {
    // The last route is restored with replace, so the first screen the user
    // sees is still the bottom of the stack.
    const cursor = createHistoryDepth()
    cursor.replaced()
    expect(cursor.canGoBack).toBe(false)
  })

  test("moving back to the first screen ends navigation", () => {
    const cursor = createHistoryDepth()
    cursor.pushed()
    cursor.moved(-1)
    expect(cursor.canGoBack).toBe(false)
    expect(cursor.index).toBe(0)
  })

  test("the cursor tracks nesting", () => {
    const cursor = createHistoryDepth()
    cursor.pushed()
    cursor.pushed()
    cursor.pushed()
    cursor.moved(-1)
    expect(cursor.index).toBe(2)
    expect(cursor.canGoBack).toBe(true)
  })

  test("moving back past the start clamps rather than going negative", () => {
    // The history clamps the same way. Drifting below zero would make back
    // appear unavailable later, after a push that should have re-enabled it.
    const cursor = createHistoryDepth()
    cursor.moved(-5)
    expect(cursor.index).toBe(0)
    cursor.pushed()
    expect(cursor.canGoBack).toBe(true)
  })

  test("moving forward past the end clamps to the last entry", () => {
    const cursor = createHistoryDepth()
    cursor.pushed()
    cursor.moved(-1)
    cursor.moved(10)
    expect(cursor.index).toBe(1)
  })

  test("the router navigating back keeps the cursor in step", () => {
    // navigate(-1) travels through go, not set. Counting only pushes would
    // leave the cursor high, and back would then claim presses that do nothing.
    const cursor = createHistoryDepth()
    cursor.pushed()
    cursor.pushed()
    cursor.moved(-2)
    expect(cursor.canGoBack).toBe(false)
  })

  test("a push after going back discards the forward entries", () => {
    // The history splices them away, so forward is no longer reachable and the
    // new entry is the last one.
    const cursor = createHistoryDepth()
    cursor.pushed()
    cursor.pushed()
    cursor.moved(-1)
    cursor.pushed()
    expect(cursor.index).toBe(2)
    cursor.moved(5)
    expect(cursor.index).toBe(2)
  })
})

/** A DOM stub: enough for the dialog query and dispatch, without a browser. */
function fakeDocument(dialogs: string[]) {
  const dispatched: Array<{ on: string; key: string }> = []
  const nodes = dialogs.map((name) => {
    const node = {
      name,
      contains: () => false,
      dispatchEvent(event: KeyboardEvent) {
        dispatched.push({ on: name, key: event.key })
        return true
      },
    }
    return node as unknown as HTMLElement & { name: string }
  })
  // A stand-in for the realm's KeyboardEvent, carrying only what is read back.
  class FakeKeyboardEvent {
    key: string
    constructor(
      readonly type: string,
      init: { key: string },
    ) {
      this.key = init.key
    }
  }
  const doc = {
    activeElement: null,
    querySelectorAll: () => nodes,
    defaultView: { KeyboardEvent: FakeKeyboardEvent },
  } as unknown as Document
  return { doc, dispatched }
}

describe("dismissing dialogs", () => {
  test("no dialog means back is not consumed", () => {
    const { doc } = fakeDocument([])
    expect(dismissTopDialog(doc)).toBe(false)
  })

  test("an open dialog consumes back", () => {
    const { doc } = fakeDocument(["only"])
    expect(dismissTopDialog(doc)).toBe(true)
  })

  test("the topmost dialog is the one dismissed", () => {
    // Stacked dialogs unwind one press at a time rather than all at once.
    const { doc, dispatched } = fakeDocument(["under", "over"])
    dismissTopDialog(doc)
    expect(dispatched).toEqual([{ on: "over", key: "Escape" }])
  })

  test("a document that cannot build the event declines instead of claiming the press", () => {
    // Reporting a dismissal that did not happen would eat the press and leave
    // the dialog open, with no way back.
    const { doc } = fakeDocument(["only"])
    Object.defineProperty(doc, "defaultView", { value: null })
    expect(dismissTopDialog(doc)).toBe(false)
  })
})
