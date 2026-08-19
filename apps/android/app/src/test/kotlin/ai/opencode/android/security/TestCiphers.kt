package ai.opencode.android.security

import java.security.SecureRandom
import javax.crypto.KeyGenerator

/**
 * A real AES-GCM cipher over an in-memory key, for tests.
 *
 * Real rather than a fake: a stub that returned its input would let a storage
 * test pass while the value on disk was plain text, which is the exact bug these
 * tests exist to catch. Only the key source differs from production, because
 * `AndroidKeyStore` does not exist off-device.
 */
fun testCipher(): ValueCipher {
    val key = KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()
    return GcmCipher { key }
}
