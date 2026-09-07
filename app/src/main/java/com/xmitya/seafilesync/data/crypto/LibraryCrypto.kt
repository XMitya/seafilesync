package com.xmitya.seafilesync.data.crypto

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The key and IV a library's blocks are encrypted with. */
data class LibraryKey(val key: ByteArray, val iv: ByteArray) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is LibraryKey && key.contentEquals(other.key) && iv.contentEquals(other.iv))

    override fun hashCode(): Int = 31 * key.contentHashCode() + iv.contentHashCode()
}

class UnsupportedEncryptionException(version: Int) :
    GeneralSecurityException("Encrypted library version $version is not supported")

class WrongLibraryPasswordException : GeneralSecurityException("Wrong library password")

/**
 * Client-side encryption for Seafile libraries.
 *
 * The point of doing this here is that the alternative is not encryption at all. The official
 * mobile app posts the library password to the server and receives plaintext, which means the
 * server can read the library and the encryption protects nothing the user thinks it protects.
 * The desktop client decrypts locally; so does this.
 *
 * Parameters are taken from the server's `common/seafile-crypt.c`, where getting any of them
 * wrong produces plausible-looking bytes rather than an error.
 */
object LibraryCrypto {

    /**
     * The fixed salt used before per-library salts existed. Its own source comments that a random
     * salt per repo would be better, which is what version 3 introduced.
     */
    private val LEGACY_SALT = byteArrayOf(
        0xda.toByte(), 0x90.toByte(), 0x45, 0xc3.toByte(), 0x06, 0xc7.toByte(), 0xcc.toByte(), 0x26,
    )

    private const val KEY_ITERATIONS = 1000

    /** The IV is derived from the key with far fewer rounds; it is not a second secret. */
    private const val IV_ITERATIONS = 10

    /**
     * Derives the key and IV for [version] from arbitrary input, which is the password for the
     * library key and the decrypted random key for the file key.
     *
     * Versions 1 and 3 are rejected rather than half-implemented. Version 1 uses OpenSSL's
     * EVP_BytesToKey instead of PBKDF2, and version 3 encrypts with AES-ECB, which leaks
     * structure; the desktop client refuses both in its GPL build for the same reasons.
     */
    fun deriveKey(input: ByteArray, version: Int, repoSaltHex: String): LibraryKey {
        val salt = when (version) {
            2 -> LEGACY_SALT
            4 -> repoSaltHex.hexToBytes()
            else -> throw UnsupportedEncryptionException(version)
        }
        val key = pbkdf2(input, salt, KEY_ITERATIONS, 32)
        val iv = pbkdf2(key, salt, IV_ITERATIONS, 16)
        return LibraryKey(key, iv)
    }

    /**
     * The value the server stores to check a password without being able to derive it. Computed
     * from the library id concatenated with the password, so it is specific to both.
     */
    fun magic(repoId: String, password: String, version: Int, repoSaltHex: String): String =
        deriveKey("$repoId$password".toByteArray(Charsets.UTF_8), version, repoSaltHex)
            .key.toHex()

    /**
     * Checks the password locally, against the magic the server already published. No request,
     * and in particular no sending of the password anywhere.
     */
    fun verifyPassword(
        repoId: String,
        password: String,
        version: Int,
        repoSaltHex: String,
        expectedMagic: String,
    ): Boolean = magic(repoId, password, version, repoSaltHex).equals(expectedMagic, ignoreCase = true)

    /**
     * Unwraps the key the library's blocks are actually encrypted with.
     *
     * The password never encrypts data directly. It derives a wrapping key, which decrypts the
     * random key the library was created with, which is then run through the same derivation
     * again to produce the block key. That indirection is what lets a password change re-wrap the
     * same key instead of re-encrypting every block.
     */
    fun fileKey(
        password: String,
        randomKeyHex: String,
        version: Int,
        repoSaltHex: String,
    ): LibraryKey {
        require(randomKeyHex.isNotEmpty()) { "Library has no random key" }
        val wrapping = deriveKey(password.toByteArray(Charsets.UTF_8), version, repoSaltHex)
        val decrypted = try {
            decrypt(randomKeyHex.hexToBytes(), wrapping, version)
        } catch (failure: GeneralSecurityException) {
            // A wrong password produces padding that does not validate, which is the only signal
            // available; there is nothing else to distinguish it from corrupt data.
            throw WrongLibraryPasswordException()
        }
        return deriveKey(decrypted, version, repoSaltHex)
    }

    fun encrypt(plaintext: ByteArray, key: LibraryKey, version: Int): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, version).doFinal(plaintext)

    fun decrypt(ciphertext: ByteArray, key: LibraryKey, version: Int): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, version).doFinal(ciphertext)

    private fun cipher(mode: Int, key: LibraryKey, version: Int): Cipher {
        if (version != 2 && version != 4) throw UnsupportedEncryptionException(version)
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(key.key, "AES"), IvParameterSpec(key.iv))
        }
    }

    /**
     * PBKDF2-HMAC-SHA256 over raw bytes.
     *
     * Written out rather than using SecretKeyFactory, which only accepts a char array and encodes
     * it as UTF-8. The second derivation here is keyed by the decrypted random key -- 32 arbitrary
     * bytes, most of which are not valid UTF-8 -- so passing it through that conversion would
     * silently produce a different key and decrypt every block to plausible-looking rubbish.
     */
    private fun pbkdf2(input: ByteArray, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(input, "HmacSHA256")) }
        val output = ByteArray(lengthBytes)
        val blockLength = mac.macLength
        var written = 0
        var blockIndex = 1

        while (written < lengthBytes) {
            mac.update(salt)
            mac.update(byteArrayOf(
                (blockIndex ushr 24).toByte(),
                (blockIndex ushr 16).toByte(),
                (blockIndex ushr 8).toByte(),
                blockIndex.toByte(),
            ))
            var u = mac.doFinal()
            val accumulated = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in accumulated.indices) accumulated[i] = (accumulated[i].toInt() xor u[i].toInt()).toByte()
            }
            val take = minOf(blockLength, lengthBytes - written)
            accumulated.copyInto(output, written, 0, take)
            written += take
            blockIndex++
        }
        return output
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Hex string has an odd length" }
        return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
