import { describe, expect, test } from "bun:test"
import { isEditable, revealFocusedInput } from "./focus"

function element(tag: string, attributes: Record<string, string> = {}) {
  return {
    tagName: tag.toUpperCase(),
    getAttribute: (name: string) => attributes[name] ?? null,
  } as unknown as Element
}

describe("isEditable", () => {
  test("a textarea takes a keyboard", () => {
    expect(isEditable(element("textarea"))).toBe(true)
  })

  test("a bare input takes a keyboard", () => {
    // No type attribute means text.
    expect(isEditable(element("input"))).toBe(true)
  })

  test("text-like input types take a keyboard", () => {
    for (const type of ["text", "search", "email", "url", "tel", "password", "number"]) {
      expect(isEditable(element("input", { type }))).toBe(true)
    }
  })

  test("inputs no keyboard serves are not editable", () => {
    // Scrolling on focus here would move the page for a tap on a checkbox.
    for (const type of ["button", "checkbox", "radio", "submit", "reset", "file", "range", "color", "hidden"]) {
      expect(isEditable(element("input", { type }))).toBe(false)
    }
  })

  test("input types are matched case-insensitively", () => {
    expect(isEditable(element("input", { type: "CHECKBOX" }))).toBe(false)
    expect(isEditable(element("INPUT", { type: "Text" }))).toBe(true)
  })

  test("contenteditable in each of its accepted spellings", () => {
    expect(isEditable(element("div", { contenteditable: "" }))).toBe(true)
    expect(isEditable(element("div", { contenteditable: "true" }))).toBe(true)
    expect(isEditable(element("div", { contenteditable: "plaintext-only" }))).toBe(true)
  })

  test("contenteditable false is not editable", () => {
    expect(isEditable(element("div", { contenteditable: "false" }))).toBe(false)
  })

  test("ordinary elements are not editable", () => {
    expect(isEditable(element("div"))).toBe(false)
    expect(isEditable(element("button"))).toBe(false)
  })

  test("nothing focused is not editable", () => {
    expect(isEditable(null)).toBe(false)
    expect(isEditable(undefined)).toBe(false)
  })
})

describe("revealFocusedInput", () => {
  function documentWith(active: Element | null, onScroll?: (o: unknown) => void) {
    if (active && onScroll) Object.assign(active, { scrollIntoView: onScroll })
    return { activeElement: active } as unknown as Document
  }

  test("does nothing when nothing is focused", () => {
    expect(revealFocusedInput(documentWith(null))).toBe(false)
  })

  test("does nothing when the focused element takes no keyboard", () => {
    const button = element("button")
    let called = false
    expect(revealFocusedInput(documentWith(button, () => (called = true)))).toBe(false)
    expect(called).toBe(false)
  })

  test("scrolls the focused field into view", () => {
    const input = element("textarea")
    let options: unknown
    expect(revealFocusedInput(documentWith(input, (o) => (options = o)))).toBe(true)
    expect(options).toEqual({ block: "nearest", inline: "nearest" })
  })

  test("survives an element that cannot scroll", () => {
    // Not every focusable node implements scrollIntoView; this must not throw
    // on the keyboard-open path.
    const input = element("input")
    expect(revealFocusedInput(documentWith(input))).toBe(false)
  })
})
