package ai.opencode.android.platform

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drafts are what the user typed and has not sent. Losing them to process death
 * is the failure this class exists to prevent, and blob ids come back through
 * the bridge from web content, so they are validated rather than trusted.
 */
@RunWith(RobolectricTestRunner::class)
class DraftStoreTest {

    private lateinit var drafts: DraftStore

    private fun b64(text: String) = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)

    @Before
    fun setUp() {
        drafts = DraftStore(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun `draft text round trips`() {
        drafts.set("session-1", "half a prompt")
        assertEquals("half a prompt", drafts.get("session-1"))
    }

    @Test
    fun `a draft survives a new store over the same files`() {
        // Stands in for process death: the app is killed while backgrounded and
        // the next launch builds a fresh DraftStore over the same directory.
        drafts.set("session-1", "unsent")
        val reopened = DraftStore(ApplicationProvider.getApplicationContext<Context>())
        assertEquals("unsent", reopened.get("session-1"))
    }

    @Test
    fun `removing a draft clears it`() {
        drafts.set("session-1", "text")
        drafts.remove("session-1")
        assertNull(drafts.get("session-1"))
    }

    @Test
    fun `a blob round trips with its type`() {
        val id = drafts.putBlob(b64("image bytes"), "image/png")
        val (base64, type) = drafts.getBlob(id)!!
        assertEquals("image bytes", String(Base64.decode(base64, Base64.DEFAULT)))
        assertEquals("image/png", type)
    }

    @Test
    fun `identical bytes share one id`() {
        // Content addressing is why the same pasted screenshot referenced from
        // several drafts costs one file rather than several.
        val first = drafts.putBlob(b64("same"), "image/png")
        val second = drafts.putBlob(b64("same"), "image/png")
        assertEquals(first, second)
    }

    @Test
    fun `different bytes get different ids`() {
        assertNotEquals(drafts.putBlob(b64("a"), ""), drafts.putBlob(b64("b"), ""))
    }

    @Test
    fun `a blob with no declared type falls back rather than returning empty`() {
        val id = drafts.putBlob(b64("bytes"), "")
        assertEquals("application/octet-stream", drafts.getBlob(id)!!.second)
    }

    @Test
    fun `an unknown but well formed id reads as absent`() {
        assertNull(drafts.getBlob("0".repeat(64)))
    }

    @Test
    fun `ids that are not content hashes are refused before touching the disk`() {
        // These come back from web content. Joining any of them onto the blob
        // directory would read a file the page has no business reading.
        val rejected = listOf(
            "",
            "../../../../etc/passwd",
            "/etc/passwd",
            "abc",                        // too short
            "0".repeat(63),
            "0".repeat(65),
            "A".repeat(64),               // uppercase is not what sha256 emits here
            "../" + "0".repeat(61),
        )
        for (id in rejected) assertNull("expected refusal for: $id", drafts.getBlob(id))
    }

    @Test
    fun `blob ids are lowercase sha256 hex`() {
        val id = drafts.putBlob(b64("anything"), "")
        assertEquals(64, id.length)
        assertEquals(id, id.lowercase())
        assertEquals(true, id.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `an empty blob is storable`() {
        val id = drafts.putBlob("", "text/plain")
        assertEquals("", drafts.getBlob(id)!!.first)
    }
}
