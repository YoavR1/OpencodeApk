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
export default defineConfig({
  plugins: [appPlugin],

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
