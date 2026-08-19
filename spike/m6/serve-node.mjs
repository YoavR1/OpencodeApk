// Smallest possible launcher for the Node build of the OpenCode server.
//
// `packages/opencode/dist/node/node.js` is a library, not a CLI: it exports
// Server, bootstrap, Config and Database. This starts a server on loopback the
// way the desktop sidecar does, so the same bundle can be exercised on a
// developer machine and on a phone.
//
// Usage: node serve-node.mjs [port] [bundlePath]
const port = Number(process.argv[2] ?? 4599)
const bundle = process.argv[3] ?? new URL("../../packages/opencode/dist/node/node.js", import.meta.url).href

const started = Date.now()
const mod = await import(bundle)
const imported = Date.now()

console.log("exports:", Object.keys(mod).join(", "))
console.log("import took:", imported - started, "ms")

const server = await mod.Server.listen({ port, hostname: "127.0.0.1" })
console.log("listening on:", `http://${server.hostname ?? "127.0.0.1"}:${server.port ?? port}`)
console.log("ready after:", Date.now() - started, "ms")
