/**
 * Keeping the thing you are typing into on screen.
 *
 * `MainActivity` pads the WebView by the IME inset, so the layout viewport
 * genuinely shrinks when the keyboard opens. That fixes the layout but not the
 * scroll position: a field near the bottom can end up behind the keyboard, and
 * on a phone that reads as the app having eaten your input.
 *
 * WebView does scroll the focused field into view by itself in simple cases,
 * but not reliably once the field sits inside its own scroll container - which
 * is exactly where the OpenCode prompt lives.
 */

/** Elements where a keyboard is actually being used to type. */
export function isEditable(element: Element | null | undefined): boolean {
  if (!element) return false
  const tag = element.tagName?.toLowerCase()
  if (tag === "textarea") return true
  if (tag === "input") {
    // Buttons and checkboxes are focusable inputs that no keyboard serves.
    const type = (element.getAttribute("type") ?? "text").toLowerCase()
    return !NON_TEXT_INPUTS.has(type)
  }
  const editable = element.getAttribute?.("contenteditable")
  return editable === "" || editable === "true" || editable === "plaintext-only"
}

const NON_TEXT_INPUTS = new Set([
  "button",
  "checkbox",
  "color",
  "file",
  "hidden",
  "image",
  "radio",
  "range",
  "reset",
  "submit",
])

/**
 * Scrolls the focused editable back into view. Returns whether it acted, so the
 * caller can be tested without a layout engine.
 */
export function revealFocusedInput(doc: Document = document): boolean {
  const active = doc.activeElement
  if (!isEditable(active)) return false
  const target = active as Element & { scrollIntoView?: (options?: ScrollIntoViewOptions) => void }
  if (typeof target.scrollIntoView !== "function") return false
  // "nearest" scrolls the minimum needed rather than yanking the field to the
  // middle of the screen, which would move the text out from under the cursor.
  target.scrollIntoView({ block: "nearest", inline: "nearest" })
  return true
}
