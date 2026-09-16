package com.xmitya.seafilesync.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Protects the account token at rest. Split behind an interface because the real implementation
 * needs AndroidKeyStore, which JVM unit tests cannot provide.
 */
interface TokenCipher {
    fun encrypt(plaintext: String): String

    fun decrypt(ciphertext: String): String
}

/**
 * AES-GCM with a key that never leaves AndroidKeyStore.
 *
 * androidx.security:security-crypto would be the obvious choice but it is deprecated, and this
 * is a single string rather than a whole preference file, so a direct Keystore key is simpler
 * and has no migration story to inherit.
 *
 * The IV is generated per encryption and prepended to the ciphertext; reusing an IV with GCM
 * would be a real break, so it is never supplied by the caller.
 */
class KeystoreTokenCipher(
    private val keyAlias: String = DEFAULT_ALIAS,
) : TokenCipher {

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String {
        val payload = Base64.decode(ciphertext, Base64.NO_WRAP)
        require(payload.size > IV_LENGTH) { "Stored token is too short to contain an IV" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, payload, 0, IV_LENGTH),
        )
        return cipher.doFinal(payload, IV_LENGTH, payload.size - IV_LENGTH).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // Syncing runs in the background, so the key must be usable with the screen locked.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_ALIAS = "seafile-sync-account-token"
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
        const val IV_LENGTH = 12
    }
}
