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
  plugins: [appPlugin as never],

  root: "src",
  publicDir: "../../app/public",

  // Assets are served by WebViewAssetLoader from
  // https://appassets.androidplatform.net/assets/ - a sub-path, not the origin
  // root - so every emitted URL must be relative. An absolute "/assets/..." would
  // resolve against the origin root and 404.
  base: "./",

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
