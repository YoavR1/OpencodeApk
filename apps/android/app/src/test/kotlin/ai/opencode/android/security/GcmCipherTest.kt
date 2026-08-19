package ai.opencode.android.security

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Establishes that the record framing round-trips and, more importantly, that it
 * fails *closed*: anything it cannot read is reported absent rather than guessed
 * at.
 *
 * These run against an ordinary in-memory AES key. `AndroidKeyStore` is not
 * available off-device - Robolectric has no such provider - which is why
 * `KeystoreCipher` does nothing but supply the key, and every decision that
 * could be wrong lives here where it can be tested. That the key is really
 * hardware-backed is a device claim, made by an instrumented test rather than
 * this file.
 *
 * Robolectric is still needed for `android.util.Base64`.
 */
@RunWith(RobolectricTestRunner::class)
class GcmCipherTest {

    private fun freshKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()

    private fun cipherOn(key: SecretKey) = GcmCipher { key }

    private val key = freshKey()
    private val cipher = cipherOn(key)

    private fun repack(encoded: String, edit: (ByteArray) -> ByteArray): String =
        Base64.encodeToString(edit(Base64.decode(encoded, Base64.NO_WRAP)), Base64.NO_WRAP)

    @Test
    fun `a value round-trips`() {
        assertEquals("hunter2", cipher.decrypt(cipher.encrypt("hunter2")))
    }

    @Test
    fun `the stored form does not contain the plaintext`() {
        assertFalse(cipher.encrypt("super-secret-password").contains("super-secret-password"))
    }

    @Test
    fun `encrypting the same value twice gives different ciphertexts`() {
        // A fresh IV per operation. Without it, two servers sharing a password
        // would be visibly identical on disk.
        val first = cipher.encrypt("same")
        val second = cipher.encrypt("same")
        assertNotEquals(first, second)
        assertEquals("same", cipher.decrypt(first))
        assertEquals("same", cipher.decrypt(second))
    }

    @Test
    fun `an empty value round-trips`() {
        assertEquals("", cipher.decrypt(cipher.encrypt("")))
    }

    @Test
    fun `unicode survives the round trip`() {
        val value = "pásswörd — 🔐 — 密码"
        assertEquals(value, cipher.decrypt(cipher.encrypt(value)))
    }

    @Test
    fun `a large value round-trips`() {
        // Persisted stores hold whole serialised documents, not short strings.
        val value = "x".repeat(200_000)
        assertEquals(value, cipher.decrypt(cipher.encrypt(value)))
    }

    @Test
    fun `unreadable input reads as absent rather than throwing`() {
        // This is the M4-to-M5 upgrade path. Values written before this store was
        // encrypted are plain text; they must degrade to "not set" rather than
        // crash the app on launch.
        for (value in listOf("", "not base64 at all", "{\"json\":true}", "AAAA")) {
            assertNull("expected null for: $value", cipher.decrypt(value))
        }
    }

    @Test
    fun `a tampered ciphertext is rejected`() {
        // GCM authenticates, so a flipped bit must not decrypt to anything.
        val encoded = cipher.encrypt("authentic")
        assertNull(
            cipher.decrypt(
                repack(encoded) { bytes -> bytes.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() } },
            ),
        )
    }

    @Test
    fun `a tampered iv is rejected`() {
        val encoded = cipher.encrypt("authentic")
        assertNull(cipher.decrypt(repack(encoded) { bytes -> bytes.also { it[2] = (it[2].toInt() xor 1).toByte() } }))
    }

    @Test
    fun `a record from an unknown scheme version is refused`() {
        val encoded = cipher.encrypt("value")
        assertNull(cipher.decrypt(repack(encoded) { bytes -> bytes.also { it[0] = 99 } }))
    }

    @Test
    fun `a truncated record is refused`() {
        val encoded = cipher.encrypt("value")
        assertNull(cipher.decrypt(repack(encoded) { it.copyOfRange(0, 6) }))
    }

    @Test
    fun `a nonsense iv length is refused before reaching the cipher`() {
        val encoded = cipher.encrypt("value")
        assertNull(cipher.decrypt(repack(encoded) { bytes -> bytes.also { it[1] = 0 } }))
        assertNull(cipher.decrypt(repack(encoded) { bytes -> bytes.also { it[1] = 127 } }))
        assertNull(cipher.decrypt(repack(encoded) { bytes -> bytes.also { it[1] = -1 } }))
    }

    @Test
    fun `another key cannot read the value`() {
        // What makes the keystore worth using: the record is inert without the
        // key that wrote it.
        val encoded = cipher.encrypt("secret")
        assertNull(cipherOn(freshKey()).decrypt(encoded))
    }

    @Test
    fun `a new cipher over the same key reads the value`() {
        // Values survive process death, so long as the key does.
        assertEquals("durable", cipherOn(key).decrypt(cipher.encrypt("durable")))
    }
}
