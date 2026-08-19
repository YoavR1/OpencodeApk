// A stand-in for the one native module the OpenCode Node build needs.
//
// The bundle imports @lydell/node-pty statically, so the server cannot even be
// loaded without it - not because a terminal is being opened, but because ESM
// resolves the import eagerly. No prebuilt exists for Android/Bionic on arm64.
//
// This shim satisfies the import and throws only if a PTY is actually spawned,
// which isolates the question: does everything ELSE work without it?
//
// It is a measuring instrument, not a fallback. Nothing ships with this.
const unavailable = () => {
  throw new Error("node-pty is not available in this build (M6 spike shim): terminal features are unsupported")
}

module.exports = {
  spawn: unavailable,
  open: unavailable,
  createTerminal: unavailable,
  get native() {
    return unavailable()
  },
}
