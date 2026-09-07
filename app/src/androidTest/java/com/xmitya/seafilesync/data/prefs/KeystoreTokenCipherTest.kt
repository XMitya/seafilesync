package com.xmitya.seafilesync.data.prefs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import kotlin.test.assertFailsWith

/**
 * Instrumented because AndroidKeyStore has no JVM implementation, so the unit tests substitute a
 * fake cipher and this is the only place the real one is exercised.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreTokenCipherTest {

    private val alias = "seafile-sync-test-${System.nanoTime()}"
    private val cipher = KeystoreTokenCipher(alias)

    private fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun round_trips_a_token() {
        val token = "0123456789abcdef0123456789abcdef01234567"
        try {
            assertEquals(token, cipher.decrypt(cipher.encrypt(token)))
        } finally {
            deleteKey()
        }
    }

    @Test
    fun ciphertext_does_not_contain_the_token() {
        val token = "0123456789abcdef0123456789abcdef01234567"
        try {
            val encrypted = cipher.encrypt(token)
            assertNotEquals(token, encrypted)
            assert(!encrypted.contains(token))
        } finally {
            deleteKey()
        }
    }

    @Test
    fun each_encryption_uses_a_fresh_iv() {
        // Reusing an IV with GCM leaks plaintext, so identical input must not produce identical
        // output.
        val token = "0123456789abcdef0123456789abcdef01234567"
        try {
            assertNotEquals(cipher.encrypt(token), cipher.encrypt(token))
        } finally {
            deleteKey()
        }
    }

    @Test
    fun a_lost_key_fails_loudly_rather_than_returning_garbage() {
        val encrypted = cipher.encrypt("0123456789abcdef0123456789abcdef01234567")
        deleteKey()

        // AccountStore turns this into "no account", which is what a device restore looks like.
        assertFailsWith<Exception> { cipher.decrypt(encrypted) }
        deleteKey()
    }
}
