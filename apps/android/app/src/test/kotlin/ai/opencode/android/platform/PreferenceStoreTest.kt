package ai.opencode.android.platform

import android.content.Context
import ai.opencode.android.security.testCipher
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Store names arrive from web content, so the mapping from name to preferences
 * file is a boundary, not an implementation detail.
 *
 * The cipher is a real AES-GCM one over an in-memory key rather than a stub, so
 * these exercise the same path production uses; only the key source differs,
 * because `AndroidKeyStore` does not exist off-device.
 */
@RunWith(RobolectricTestRunner::class)
class PreferenceStoreTest {

    private lateinit var store: PreferenceStore

    @Before
    fun setUp() {
        store = PreferenceStore(ApplicationProvider.getApplicationContext<Context>(), testCipher())
    }

    @Test
    fun `values round trip`() {
        store.set("default.dat", "theme", "dark")
        assertEquals("dark", store.get("default.dat", "theme"))
    }

    @Test
    fun `stored values are not readable on disk`() {
        // The property M5 actually needs. Upstream persists a whole
        // ServerConnection - password included - into this store, so leaving it
        // as plain SharedPreferences would put a credential in the clear.
        val context = ApplicationProvider.getApplicationContext<Context>()
        store.set("server.v3", "state", """{"password":"hunter2"}""")

        val onDisk = context.getSharedPreferences("oc_server.v3", Context.MODE_PRIVATE)
            .all.values.joinToString(" ") { it.toString() }

        assertFalse("the password must not be readable", onDisk.contains("hunter2"))
        assertFalse("nor the surrounding document", onDisk.contains("password"))
        // ...and it is genuinely still retrievable through the store.
        assertEquals("""{"password":"hunter2"}""", store.get("server.v3", "state"))
    }

    @Test
    fun `key names stay readable so listing still works`() {
        // Deliberate: names are structural, values carry the content.
        val context = ApplicationProvider.getApplicationContext<Context>()
        store.set("s", "a-known-name", "value")
        assertTrue(context.getSharedPreferences("oc_s", Context.MODE_PRIVATE).all.keys.contains("a-known-name"))
    }

    @Test
    fun `a value written before encryption reads as absent rather than crashing`() {
        // The M4-to-M5 upgrade path on a device that already holds data.
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("oc_legacy", Context.MODE_PRIVATE)
            .edit()
            .putString("key", "written in the clear by M4")
            .commit()
        assertNull(store.get("legacy", "key"))
    }

    @Test
    fun `a missing key reads as null`() {
        assertNull(store.get("default.dat", "never-written"))
    }

    @Test
    fun `stores with different names do not share keys`() {
        // The shared UI keeps global and per-window state in separate stores;
        // collapsing them would silently cross-contaminate window layout.
        store.set("opencode.global.dat", "key", "global")
        store.set("window-1.dat", "key", "window")
        assertEquals("global", store.get("opencode.global.dat", "key"))
        assertEquals("window", store.get("window-1.dat", "key"))
    }

    @Test
    fun `remove deletes one key and leaves the rest`() {
        store.set("s", "a", "1")
        store.set("s", "b", "2")
        store.remove("s", "a")
        assertNull(store.get("s", "a"))
        assertEquals("2", store.get("s", "b"))
    }

    @Test
    fun `clear empties one store without touching another`() {
        store.set("s1", "a", "1")
        store.set("s2", "a", "2")
        store.clear("s1")
        assertEquals(emptyList<String>(), store.keys("s1"))
        assertEquals("2", store.get("s2", "a"))
    }

    @Test
    fun `keys are returned sorted so iteration order is stable`() {
        store.set("s", "c", "3")
        store.set("s", "a", "1")
        store.set("s", "b", "2")
        assertEquals(listOf("a", "b", "c"), store.keys("s"))
    }

    @Test
    fun `path traversal in a store name cannot escape the preferences directory`() {
        // Dots survive sanitising on purpose - real store names contain them
        // ("default.dat"). What makes traversal impossible is that separators do
        // not: "../../evil" and ".._.._evil" address the same store, so no name
        // web content can supply resolves to another directory.
        store.set("../../evil", "key", "value")
        assertEquals("value", store.get("../../evil", "key"))
        assertEquals("value", store.get(".._.._evil", "key"))
        assertEquals("value", store.get("..\\..\\evil", "key"))
    }

    @Test
    fun `a name of only dots is still a plain file`() {
        // The oc_ prefix means even ".." names a file rather than the parent
        // directory.
        store.set("..", "key", "value")
        assertEquals("value", store.get("..", "key"))
    }

    @Test
    fun `names differing outside the sanitised set stay distinct`() {
        store.set("a.b", "key", "dot")
        store.set("a-b", "key", "dash")
        assertEquals("dot", store.get("a.b", "key"))
        assertEquals("dash", store.get("a-b", "key"))
    }

    @Test
    fun `an empty name is still usable`() {
        store.set("", "key", "value")
        assertEquals("value", store.get("", "key"))
    }

    @Test
    fun `an overlong name does not blow up the filename`() {
        val long = "x".repeat(500)
        store.set(long, "key", "value")
        assertEquals("value", store.get(long, "key"))
    }
}
