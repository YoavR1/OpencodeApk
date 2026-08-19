package ai.opencode.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Supplies [GcmCipher] with an AES-256 key held in the Android Keystore.
 *
 * The key is generated on the device, is not exportable, and never enters this
 * process - only cipher operations cross the boundary. Where the hardware
 * supports it the key lives in the TEE or a secure element, so a copy of the
 * app's data directory is not enough to read anything out of it.
 *
 * **Why not `EncryptedSharedPreferences`.** It was the obvious choice and it is
 * deprecated: `androidx.security:security-crypto` ships it annotated
 * `@Deprecated` even in the stable 1.1.0 release (verified by decompiling the
 * artifact, M5). Founding this project's credential path - the one M10 inherits
 * - on an already-dead API would be a liability, and the platform primitive it
 * wrapped is available directly at minSdk 26 with no dependency at all.
 *
 * Deliberately NOT `setUserAuthenticationRequired(true)`: that demands a
 * lock-screen or biometric prompt per operation, which cannot work while a
 * background agent turn is streaming. Re-visit in M10 if a "lock the app"
 * feature is wanted, where it belongs as a user choice.
 */
class KeystoreCipher(private val alias: String = DEFAULT_ALIAS) :
    ValueCipher by GcmCipher({ keyFor(alias) }) {

    companion object {
        const val DEFAULT_ALIAS = "ai.opencode.android.store.v1"

        private const val PROVIDER = "AndroidKeyStore"

        /** Cached because each lookup is a round trip to the keystore daemon. */
        private val cache = HashMap<String, SecretKey>()

        @Synchronized
        private fun keyFor(alias: String): SecretKey =
            cache.getOrPut(alias) { existing(alias) ?: generate(alias) }

        private fun existing(alias: String): SecretKey? = runCatching {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        }.getOrNull()

        private fun generate(alias: String): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
