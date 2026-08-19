// Entry point for the on-device OpenCode server.
//
// The Node build of OpenCode is a library, not a CLI: it exports Server, Config,
// bootstrap and Database. This is the equivalent of what
// packages/desktop/src/main/sidecar.ts does on desktop - bind loopback, take the
// per-launch credentials from the environment, allow the shell's origin through
// CORS, and report the resolved address back to the host.
//
// It speaks one line of JSON on stdout per event so the Kotlin side never has to
// parse prose:
//
//   {"event":"ready","url":"http://127.0.0.1:41234","port":41234}
//   {"event":"error","message":"..."}
//
// The password is deliberately absent from that line. Kotlin generated it and
// passed it in; echoing it back would only put it somewhere else.

const argument = (name, fallback) => {
  const hit = process.argv.find((value) => value.startsWith(`--${name}=`))
  return hit ? hit.slice(name.length + 3) : fallback
}

const emit = (payload) => process.stdout.write(`${JSON.stringify(payload)}\n`)

const hostname = argument("hostname", "127.0.0.1")
// 0 hands the choice to upstream, which tries 4096 first and falls back to any
// free port (packages/opencode/src/server/server.ts). Better than choosing here:
// predictable, but still survives a collision with something else on the device.
const port = Number(argument("port", "0"))
// Comma-separated so a second origin can be added without changing the contract.
const cors = argument("cors", "").split(",").filter(Boolean)

process.on("uncaughtException", (error) => {
  emit({ event: "error", message: String(error?.message ?? error) })
  process.exit(1)
})
process.on("unhandledRejection", (error) => {
  emit({ event: "error", message: String(error?.message ?? error) })
  process.exit(1)
})

try {
  const started = Date.now()
  const { Server } = await import("./node.js")

  const listener = await Server.listen({ port, hostname, cors })

  emit({
    event: "ready",
    url: `http://${listener.hostname}:${listener.port}`,
    port: listener.port,
    hostname: listener.hostname,
    startupMs: Date.now() - started,
  })

  // Shut down cleanly when the host asks. Android kills the process group when
  // the app goes away, but a clean stop lets SQLite close its files properly.
  let stopping = false
  const shutdown = async (why) => {
    if (stopping) return
    stopping = true
    emit({ event: "stopping", why })
    try {
      await listener.stop(true)
    } catch {
      // Nothing useful to do; the process is going away regardless.
    } finally {
      process.exit(0)
    }
  }
  process.on("SIGTERM", () => shutdown("SIGTERM"))
  process.on("SIGINT", () => shutdown("SIGINT"))

  // The parent's death is the other way this process ends.
  //
  // Android usually kills the whole process group along with the app, but
  // "usually" is not a guarantee, and a server that outlives its app is one
  // nobody can reach, holding a port and a database nobody can talk to. The host
  // keeps this pipe open for exactly as long as it is alive, so EOF here means
  // the app is gone - the one signal that arrives however the app died.
  process.stdin.on("end", () => shutdown("parent-gone"))
  process.stdin.on("close", () => shutdown("parent-gone"))
  process.stdin.resume()
} catch (error) {
  emit({ event: "error", message: String(error?.stack ?? error?.message ?? error) })
  process.exit(1)
}
