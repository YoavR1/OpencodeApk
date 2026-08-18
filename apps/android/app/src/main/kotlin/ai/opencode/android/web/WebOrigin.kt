package ai.opencode.android.web

/**
 * The origin the shared OpenCode UI is served from inside the app.
 *
 * The desktop app registers a privileged custom scheme (`oc://renderer`) with
 * `{ secure, standard, supportFetchAPI, stream }` and passes that exact origin to
 * the OpenCode server's CORS allowlist. `WebViewAssetLoader` is the Android
 * equivalent: it serves APK assets over `https://`, which gives a secure,
 * standard, fetch- and stream-capable origin.
 *
 * This object is the single source of truth for that origin. From M7 the same
 * value is handed to `Server.listen({ cors: [...] })`, so it must not drift.
 *
 * See docs/ARCHITECTURE.md 2.7 and ADR-0008.
 */
object WebOrigin {

    /**
     * The reserved domain `WebViewAssetLoader` uses by default. It never resolves
     * in DNS, so a request can only be satisfied by the in-app loader.
     */
    const val DOMAIN: String = "appassets.androidplatform.net"

    /** Path prefix the asset handler is registered under. */
    const val ASSET_PATH: String = "/assets/"

    /** Scheme-qualified origin, e.g. `https://appassets.androidplatform.net`. */
    const val ORIGIN: String = "https://$DOMAIN"

    /** Entry point of the bundled web application. */
    val INDEX_URL: String = url("index.html")

    /**
     * Builds an absolute URL for an asset packaged under `src/main/assets/web`.
     *
     * Leading slashes on [path] are ignored so that both `"index.html"` and
     * `"/index.html"` resolve identically.
     */
    fun url(path: String): String = ORIGIN + ASSET_PATH + path.trimStart('/')

    /**
     * True when [url] belongs to this app's asset origin.
     *
     * Used to decide whether a navigation stays in the WebView or is handed to
     * the system browser. Matching is on scheme and host only: a URL that merely
     * contains the domain elsewhere (in a path, in userinfo, or as a suffix of a
     * lookalike host) must not match.
     */
    fun isAppOrigin(url: String?): Boolean {
        if (url == null) return false
        val withoutScheme = url.removePrefix("https://")
        if (withoutScheme.length == url.length) return false // scheme was not https
        if (withoutScheme.startsWith(DOMAIN).not()) return false
        val next = withoutScheme.getOrNull(DOMAIN.length)
        // Host ends here, or is followed by a path/query/fragment delimiter.
        return next == null || next == '/' || next == '?' || next == '#'
    }
}
