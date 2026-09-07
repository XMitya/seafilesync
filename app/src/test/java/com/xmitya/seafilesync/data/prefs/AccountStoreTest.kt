package com.xmitya.seafilesync.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Stands in for the Keystore, which JVM tests cannot reach. */
    private class ReversingCipher(var failing: Boolean = false) : TokenCipher {
        override fun encrypt(plaintext: String) = plaintext.reversed()
        override fun decrypt(ciphertext: String): String {
            if (failing) throw IllegalStateException("key is gone")
            return ciphertext.reversed()
        }
    }

    private val cipher = ReversingCipher()

    private fun store(): Pair<AccountStore, DataStore<Preferences>> {
        val file = folder.newFile("account.preferences_pb").also { it.delete() }
        val dataStore = PreferenceDataStoreFactory.create { file }
        return AccountStore(dataStore, cipher) to dataStore
    }

    private val account = Account(
        serverUrl = "https://seafile.example.com/",
        email = "test@example.com",
        token = "0123456789abcdef0123456789abcdef01234567",
        syncRoot = "/storage/emulated/0/Seafile",
        deviceId = "a1b2c3d4e5f60718",
    )

    @Test
    fun `an account survives a round trip`() = runTest {
        val (accountStore, _) = store()

        accountStore.save(account)

        assertEquals(account, accountStore.current())
    }

    @Test
    fun `nothing is stored before a login`() = runTest {
        assertNull(store().first.current())
    }

    @Test
    fun `the token is not written in the clear`() = runTest {
        val (accountStore, dataStore) = store()

        accountStore.save(account)

        val raw = dataStore.data.first().asMap().entries.single { it.key.name == "token" }.value as String
        assertNotEquals(account.token, raw)
        assertTrue(raw.isNotEmpty())
    }

    @Test
    fun `a token that cannot be decrypted reads as no account`() = runTest {
        // The Keystore key can disappear on a device restore or a lock-screen change. There is
        // nothing to recover, so the account has to look absent and prompt a fresh login rather
        // than crash the sync loop on every request.
        val (accountStore, _) = store()
        accountStore.save(account)

        cipher.failing = true

        assertNull(accountStore.current())
    }

    @Test
    fun `the sync root can be moved without logging in again`() = runTest {
        val (accountStore, _) = store()
        accountStore.save(account)

        accountStore.updateSyncRoot("/storage/emulated/0/Documents/Seafile")

        val updated = accountStore.current()!!
        assertEquals("/storage/emulated/0/Documents/Seafile", updated.syncRoot)
        assertEquals(account.token, updated.token)
    }

    @Test
    fun `signing out leaves nothing behind`() = runTest {
        val (accountStore, _) = store()
        accountStore.save(account)

        accountStore.clear()

        assertNull(accountStore.current())
    }
}
