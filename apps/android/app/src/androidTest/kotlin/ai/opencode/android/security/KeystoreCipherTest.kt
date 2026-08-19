package ai.opencode.android.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of the credential store that cannot be unit-tested.
 *
 * `AndroidKeyStore` is not available off-device - Robolectric has no such
 * provider - so `GcmCipherTest` covers the record framing with an in-memory key
 * and this covers the claim that actually matters for security: that the key is
 * real, lives in the keystore, and is not extractable.
 *
 * Not currently run by CI; there is no emulator job yet. Registered in
 * docs/TEST_MATRIX.md as written-but-not-run.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCipherTest {

    private val alias = "ai.opencode.android.test.${System.nanoTime()}"

    @Test
    fun aValueRoundTrips() {
        val cipher = KeystoreCipher(alias)
        assertEquals("hunter2", cipher.decrypt(cipher.encrypt("hunter2")))
    }

    @Test
    fun theKeyIsStoredInTheAndroidKeystore() {
        KeystoreCipher(alias).encrypt("anything")

        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue("the key should have been created on first use", store.containsAlias(alias))
        assertNotNull(store.getKey(alias, null))
    }

    @Test
    fun theKeyMaterialCannotBeExported() {
        // The point of using the keystore at all: even this process cannot read
        // the bytes back out, so a copy of the data directory is not enough.
        KeystoreCipher(alias).encrypt("anything")

        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull("keystore keys must not expose their material", store.getKey(alias, null)?.encoded)
    }

    @Test
    fun aValueSurvivesANewCipherInstance() {
        // Stands in for process death: the key persists, so the value is still
        // readable on the next launch.
        val encoded = KeystoreCipher(alias).encrypt("durable")
        assertEquals("durable", KeystoreCipher(alias).decrypt(encoded))
    }

    @Test
    fun anotherAliasCannotReadTheValue() {
        val encoded = KeystoreCipher(alias).encrypt("secret")
        assertNull(KeystoreCipher("$alias.other").decrypt(encoded))
    }
}
