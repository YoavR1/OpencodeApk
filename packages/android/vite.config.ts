import { defineConfig } from "vite"
import appPlugin from "@opencode-ai/app/vite"

/**
 * Build config for the Android renderer.
 *
 * This mirrors `packages/desktop`'s renderer config deliberately: same
 * `@opencode-ai/app/vite` plugin, its own `index.html` root, and the shared
 * `packages/app/public` directory. Anything that diverges here is a place the
 * Android build could drift from the desktop build without anyone noticing.
 */
/**
 * Records which release channel this bundle was built for.
 *
 * Upstream resolves the channel from `OPENCODE_CHANNEL` and **defaults to
 * "dev"** (`packages/app/vite.js`), which draws a DEV badge in the titlebar and
 * enables debug tooling. A release APK built without setting it therefore ships
 * looking like a development build - which is exactly what M11 found on a
 * device.
 *
 * The channel is baked into the bundle by `define`, so it cannot be read back
 * out reliably once the minifier has folded the comparisons away. Emitting it
 * as a file means the Gradle build can *check* what it is packaging instead of
 * assuming whoever ran the renderer build set the right variable.
 */
function recordChannel() {
  const raw = process.env.OPENCODE_CHANNEL
  const channel = raw === "dev" || raw === "beta" || raw === "prod" ? raw : raw === "latest" ? "prod" : "dev"
  return {
    name: "opencode-android:record-channel",
    generateBundle(this: { emitFile: (file: { type: "asset"; fileName: string; source: string }) => void }) {
      this.emitFile({
        type: "asset",
        fileName: "build-info.json",
        // No timestamp: it would make two identical inputs produce different
        // outputs, and reproducibility is a release requirement (docs/RELEASE.md).
        source: JSON.stringify({ channel }, null, 2) + "\n",
      })
    },
  }
}

export default defineConfig({
  plugins: [appPlugin, recordChannel()],

  root: "src",
  publicDir: "../../app/public",

  // Left at vite's default of "/", matching the web build exactly.
  //
  // The shared stylesheet references bundled fonts with root-absolute URLs
  // (`url("/assets/Inter.ttf")` in packages/app/src/index.css), so the app has to
  // be served from an origin root. WebViewAssetLoader is configured accordingly
  // (see WebOrigin.ASSET_PATH). Setting a relative base here would break those
  // font URLs while appearing to work, because a missing font falls back
  // silently.

  build: {
    outDir: "../dist",
    emptyOutDir: true,
    target: "esnext",
    // Sourcemaps would roughly double the APK's web payload. Re-enable behind a
    // flag if renderer debugging on device needs them.
    sourcemap: false,
  },

  server: {
    host: "0.0.0.0",
    port: 3100,
  },
})
