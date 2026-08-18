package ai.opencode.android

import ai.opencode.android.web.WebOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The asset origin is handed to the OpenCode server's CORS allowlist from M7, so
 * a silent change here would break the connection rather than fail a build.
 * These tests pin the contract.
 */
class WebOriginTest {

    @Test
    fun `origin is https on the reserved asset domain`() {
        assertEquals("https://appassets.androidplatform.net", WebOrigin.ORIGIN)
    }

    @Test
    fun `index url points at the bundled entry point`() {
        assertEquals("https://appassets.androidplatform.net/index.html", WebOrigin.INDEX_URL)
    }

    @Test
    fun `the app is served from the origin root`() {
        // Not cosmetic: the shared stylesheet references fonts as
        // url("/assets/Inter.ttf"), and vite emits its chunks under /assets/.
        // Serving from a sub-path would 404 all of them, and a missing font
        // fails silently, so this is pinned.
        assertEquals("/", WebOrigin.ASSET_PATH)
        assertEquals(
            "https://appassets.androidplatform.net/assets/Inter.ttf",
            WebOrigin.url("assets/Inter.ttf"),
        )
    }

    @Test
    fun `url tolerates a leading slash`() {
        assertEquals(WebOrigin.url("index.html"), WebOrigin.url("/index.html"))
    }

    @Test
    fun `url builds nested asset paths`() {
        assertEquals(
            "https://appassets.androidplatform.net/assets/main.js",
            WebOrigin.url("assets/main.js"),
        )
    }

    @Test
    fun `app origin is recognised with and without a path`() {
        assertTrue(WebOrigin.isAppOrigin("https://appassets.androidplatform.net"))
        assertTrue(WebOrigin.isAppOrigin("https://appassets.androidplatform.net/"))
        assertTrue(WebOrigin.isAppOrigin(WebOrigin.INDEX_URL))
        assertTrue(WebOrigin.isAppOrigin("https://appassets.androidplatform.net/a?b=1#c"))
    }

    @Test
    fun `http is not the app origin`() {
        assertFalse(WebOrigin.isAppOrigin("http://appassets.androidplatform.net/"))
    }

    @Test
    fun `lookalike hosts are rejected`() {
        // A suffix match would wrongly accept an attacker-controlled host.
        assertFalse(WebOrigin.isAppOrigin("https://appassets.androidplatform.net.evil.com/"))
        assertFalse(WebOrigin.isAppOrigin("https://evil.com/appassets.androidplatform.net"))
        assertFalse(WebOrigin.isAppOrigin("https://notappassets.androidplatform.net/"))
    }

    @Test
    fun `null and unrelated urls are rejected`() {
        assertFalse(WebOrigin.isAppOrigin(null))
        assertFalse(WebOrigin.isAppOrigin(""))
        assertFalse(WebOrigin.isAppOrigin("file:///android_asset/index.html"))
        assertFalse(WebOrigin.isAppOrigin("https://opencode.ai"))
    }
}
