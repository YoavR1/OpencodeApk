package ai.opencode.android.security

import android.util.Base64
import ai.opencode.android.util.SafeLog
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM framing over a key someone else supplies.
 *
 * Split from [KeystoreCipher] so the part that can be wrong is the part that can
 * be tested. Where the key comes from is a policy decision and needs a device to
 * exercise; how a record is packed, and what happens to one that cannot be read,
 * is ordinary logic that should not need an emulator to verify.
 *
 * Record layout, base64-encoded:
 *
 *     [version:1][ivLength:1][iv:ivLength][ciphertext+tag:...]
 *
 * The version byte means a future scheme can be introduced without this build
 * misreading old records - an unrecognised version is reported absent rather
 * than decrypted with the wrong parameters.
 */
class GcmCipher(private val key: () -> SecretKey) : ValueCipher {

    override fun encrypt(plaintext: String): String {
        // Deliberately not caught: a write that cannot be encrypted must fail
        // loudly rather than fall back to storing a credential in the clear.
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val packed = ByteArray(HEADER_BYTES + iv.size + ciphertext.size)
        packed[0] = VERSION
        packed[1] = iv.size.toByte()
        iv.copyInto(packed, HEADER_BYTES)
        ciphertext.copyInto(packed, HEADER_BYTES + iv.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String? {
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size < HEADER_BYTES) return null
            if (packed[0] != VERSION) return null

            val ivSize = packed[1].toInt()
            // Anything shorter cannot hold an IV plus a GCM tag, so reject it
            // before asking the cipher to.
            if (ivSize <= 0 || packed.size < HEADER_BYTES + ivSize + GCM_TAG_BYTES) return null

            val iv = packed.copyOfRange(HEADER_BYTES, HEADER_BYTES + ivSize)
            val ciphertext = packed.copyOfRange(HEADER_BYTES + ivSize, packed.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (error: Exception) {
            // The exception type only. Some providers put a prefix of the input
            // in the message, and this input is a credential.
            SafeLog.w("could not decrypt a stored value (${error.javaClass.simpleName}); treating it as absent")
            null
        }
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128

        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val HEADER_BYTES = 2
        private const val VERSION: Byte = 1
    }
}
