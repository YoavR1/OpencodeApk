package ai.opencode.android.web

import java.security.MessageDigest

/**
 * The Content-Security-Policy served with the app document.
 *
 * **Why this exists.** The bridge is reachable by any script running at the app
 * origin, and it can read the encrypted store, the clipboard and the draft
 * blobs. The UI renders model output, file contents and diffs - text the user
 * did not write and the model does not control either. One injection at the app
 * origin is therefore not a defaced page, it is credential disclosure.
 *
 * The policy is attached natively, in the response `WebViewHost` serves, rather
 * than as a `<meta>` tag in the HTML. A meta tag is content, and content is what
 * an injection controls; a response header is not reachable from the page at all.
 *
 * Kept as pure functions so the policy can be asserted directly in a unit test
 * rather than inferred from a running WebView.
 */
object ContentSecurityPolicy {

    /**
     * The policy, given the hashes of the inline scripts the document legitimately
     * carries.
     *
     * Notes on the directives that are not obvious:
     *
     *  - `script-src` names each inline script by hash rather than allowing
     *    `'unsafe-inline'`. Upstream's theme preload runs before the bundle to
     *    avoid a flash of the wrong colour scheme, so it cannot be moved to a
     *    file; naming it by hash permits exactly that script and no other.
     *  - `'wasm-unsafe-eval'` permits compiling WebAssembly and nothing else. It
     *    does **not** re-enable `eval`.
     *  - `style-src` does allow `'unsafe-inline'`. The theme preload sets an
     *    inline style attribute and injects a `<style>` element whose content
     *    varies with the user's theme, so it cannot be hashed. Injected CSS is a
     *    far weaker primitive than injected script, and it cannot reach the
     *    bridge.
     *  - `connect-src` allows `https:` broadly, which is the weakest part of this
     *    policy. Remote-server mode (ADR-0021) points the UI at a host the user
     *    types in, so the origin is not known when this is built. Loopback is
     *    named explicitly because the on-device server's port is ephemeral.
     *    When remote mode is retired this should become `'self'` plus loopback.
     *  - `img-src` deliberately omits remote hosts. A remote image URL in model
     *    output is the cheapest exfiltration channel there is, and the cost of
     *    refusing it is a broken image rather than a broken app.
     */
    fun forDocument(scriptHashes: List<String>): String {
        val scripts = (listOf("'self'", "'wasm-unsafe-eval'") + scriptHashes).joinToString(" ")
        return listOf(
            "default-src 'self'",
            "script-src $scripts",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "font-src 'self' data:",
            "media-src 'self' data: blob:",
            "worker-src 'self' blob:",
            "connect-src 'self' http://127.0.0.1:* http://localhost:* https:",
            "frame-src 'none'",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'none'",
        ).joinToString("; ")
    }

    /**
     * The CSP source expressions for every inline `<script>` in a document.
     *
     * Computed from the packaged asset at runtime rather than pinned at build
     * time. The APK is signed, so the file this reads is the file the WebView
     * parses; deriving the hash from it means an upstream change to the preload
     * script cannot silently produce a policy that blocks the app's own code.
     *
     * A `<script>` carrying `src` is not inline and is covered by `'self'`.
     */
    fun inlineScriptHashes(html: String): List<String> =
        SCRIPT.findAll(html)
            .filter { !it.groupValues[1].contains("src=", ignoreCase = true) }
            .map { sha256(it.groupValues[2]) }
            .toList()

    /** The exact base64 SHA-256 of the bytes, as CSP requires it to be written. */
    private fun sha256(source: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        return "'sha256-${android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)}'"
    }

    /**
     * Matches a script element and captures its attributes and its body.
     *
     * DOT_MATCHES_ALL because inline scripts span lines; non-greedy so two
     * scripts in one document are two matches rather than one that swallows
     * everything between them.
     */
    private val SCRIPT = Regex(
        """<script([^>]*)>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
}
