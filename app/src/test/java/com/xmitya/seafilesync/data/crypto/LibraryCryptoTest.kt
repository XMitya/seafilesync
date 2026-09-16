package com.xmitya.seafilesync.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * The reference values come from a library created on a real Seafile 11.0.13 server, so the magic
 * below is the one that server computed rather than one this code produced. That matters more
 * here than anywhere else: every parameter in this derivation, if wrong, yields bytes that look
 * like a key and decrypt every block into rubbish without any error being raised.
 */
class LibraryCryptoTest {

    private val repoId = "6f650d86-b4f3-4c7c-bec1-44bc1ce0aa0c"
    private val password = "probe-password-123"
    private val encVersion = 2

    private val serverMagic = "48dfb631cf18f7fc02d88ae5ab06b99f73df88a65efc7d5835a791a0170d436a"
    private val serverRandomKey =
        "67d704fb3fb38bb483bcef3b5fff99da988fad37cc41d6bc2fd22755a01277837b93998b0891dd2fbf429fe8f6b3c70a"

    @Test
    fun `magic matches the value the server computed`() {
        assertEquals(serverMagic, LibraryCrypto.magic(repoId, password, encVersion, repoSaltHex = ""))
    }

    @Test
    fun `the password is verified locally without contacting the server`() {
        assertTrue(LibraryCrypto.verifyPassword(repoId, password, encVersion, "", serverMagic))
        assertFalse(LibraryCrypto.verifyPassword(repoId, "wrong", encVersion, "", serverMagic))
    }

    @Test
    fun `the file key unwraps from the random key`() {
        val key = LibraryCrypto.fileKey(password, serverRandomKey, encVersion, repoSaltHex = "")

        assertEquals(32, key.key.size)
        assertEquals(16, key.iv.size)
    }

    @Test
    fun `a wrong password is reported as such rather than as corrupt data`() {
        assertFailsWith<WrongLibraryPasswordException> {
            LibraryCrypto.fileKey("not the password", serverRandomKey, encVersion, "")
        }
    }

    @Test
    fun `a block survives an encrypt-decrypt round trip`() {
        val key = LibraryCrypto.fileKey(password, serverRandomKey, encVersion, "")
        val plaintext = ByteArray(5000) { (it * 7).toByte() }

        val encrypted = LibraryCrypto.encrypt(plaintext, key, encVersion)

        // Padded to the cipher block size, so a length check alone would not prove much.
        assertTrue(encrypted.size > plaintext.size)
        assertArrayEquals(plaintext, LibraryCrypto.decrypt(encrypted, key, encVersion))
    }

    @Test
    fun `an empty block round-trips too`() {
        val key = LibraryCrypto.fileKey(password, serverRandomKey, encVersion, "")

        assertArrayEquals(
            ByteArray(0),
            LibraryCrypto.decrypt(LibraryCrypto.encrypt(ByteArray(0), key, encVersion), key, encVersion),
        )
    }

    @Test
    fun `versions that cannot be done safely are refused rather than half-supported`() {
        // 1 derives keys with EVP_BytesToKey rather than PBKDF2, and 3 encrypts with AES-ECB.
        // The desktop client's GPL build refuses both for the same reasons.
        assertFailsWith<UnsupportedEncryptionException> { LibraryCrypto.magic(repoId, password, 1, "") }
        assertFailsWith<UnsupportedEncryptionException> { LibraryCrypto.magic(repoId, password, 3, "a".repeat(64)) }
    }

    @Test
    fun `version 4 uses the library's own salt rather than the fixed one`() {
        val salt = "b".repeat(64)
        val withSalt = LibraryCrypto.magic(repoId, password, 4, salt)
        val withOther = LibraryCrypto.magic(repoId, password, 4, "c".repeat(64))

        assertFalse("a per-library salt must change the result", withSalt == withOther)
        assertFalse("and must differ from the legacy fixed salt", withSalt == serverMagic)
    }
}
