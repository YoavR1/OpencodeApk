package ai.opencode.android.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bridge is the only way web content can reach native code, so its parser is
 * the app's trust boundary. Every message it sees is attacker-shaped by
 * definition: a compromised page, or an injected script, posts whatever it likes
 * into the port. These tests pin that malformed input is refused rather than
 * half-interpreted.
 *
 * Robolectric because org.json is a stub in the plain unit-test android.jar.
 */
@RunWith(RobolectricTestRunner::class)
class BridgeContractTest {

    @Test
    fun `a well formed request parses`() {
        val request = BridgeRequest.parse("""{"id":7,"method":"store.get","params":{"name":"a","key":"b"}}""")
        assertNotNull(request)
        assertEquals(7, request!!.id)
        assertEquals("store.get", request.method)
        assertEquals("a", request.str("name"))
        assertEquals("b", request.str("key"))
    }

    @Test
    fun `params default to empty rather than null`() {
        // Methods that take no params send none; handlers should not have to
        // null-check before reading.
        val request = BridgeRequest.parse("""{"id":1,"method":"restart"}""")
        assertNotNull(request)
        assertEquals(0, request!!.params.length())
        assertNull(request.str("anything"))
    }

    @Test
    fun `malformed messages are refused`() {
        // Not exhaustive, but each of these is a shape the port can actually
        // receive, and none may produce a partially-populated request.
        val rejected = listOf(
            "",
            "not json",
            "[]",
            "null",
            """{"method":"restart"}""",                  // no id
            """{"id":-1,"method":"restart"}""",          // negative id
            """{"id":"one","method":"restart"}""",       // non-numeric id
            """{"id":1}""",                              // no method
            """{"id":1,"method":""}""",                  // empty method
        )
        for (raw in rejected) assertNull("expected refusal for: $raw", BridgeRequest.parse(raw))
    }

    @Test
    fun `a params value of the wrong type does not crash the parser`() {
        val request = BridgeRequest.parse("""{"id":1,"method":"share","params":"a string"}""")
        assertNotNull(request)
        assertEquals(0, request!!.params.length())
    }

    @Test
    fun `an explicitly null parameter reads as absent`() {
        val request = BridgeRequest.parse("""{"id":1,"method":"defaultServer.set","params":{"url":null}}""")
        assertNull(request!!.str("url"))
    }

    @Test
    fun `unknown methods parse but are not in the answered set`() {
        // Parsing and authorising are separate steps: the host refuses by name,
        // so an unknown method must still parse cleanly enough to be refused
        // with its id attached.
        val request = BridgeRequest.parse("""{"id":3,"method":"exec","params":{}}""")
        assertNotNull(request)
        assertTrue(request!!.method !in BridgeContract.METHODS)
    }

    @Test
    fun `success carries the id and result`() {
        val json = JSONObject(BridgeContract.success(4, "value"))
        assertEquals(4, json.getInt("id"))
        assertTrue(json.getBoolean("ok"))
        assertEquals("value", json.getString("result"))
    }

    @Test
    fun `a null result is sent explicitly rather than omitted`() {
        // The renderer resolves on `ok`, and an absent key is indistinguishable
        // from a dropped field, so null is encoded.
        val json = JSONObject(BridgeContract.success(5, null))
        assertTrue(json.getBoolean("ok"))
        assertTrue(json.isNull("result"))
    }

    @Test
    fun `failure carries a code and message`() {
        val json = JSONObject(BridgeContract.failure(6, BridgeContract.Errors.UNKNOWN_METHOD, "no such method"))
        assertEquals(6, json.getInt("id"))
        assertTrue(!json.getBoolean("ok"))
        assertEquals("UnknownMethod", json.getJSONObject("error").getString("code"))
        assertEquals("no such method", json.getJSONObject("error").getString("message"))
    }

    @Test
    fun `events have no id so the renderer never resolves a promise on them`() {
        val json = JSONObject(BridgeContract.event("lifecycle", mapOf("state" to "resumed")))
        assertTrue(!json.has("id"))
        assertEquals("lifecycle", json.getString("event"))
        assertEquals("resumed", json.getString("state"))
    }
}
