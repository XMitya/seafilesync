package com.xmitya.seafilesync.service

import com.xmitya.seafilesync.data.db.SyncedRepoEntity
import com.xmitya.seafilesync.data.prefs.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync service stops itself the moment this turns false, so it has to turn false on every
 * way the work can disappear, and only then.
 */
class SyncDemandTest {

    private val account = Account(
        serverUrl = "https://seafile.example.com/",
        email = "test@example.com",
        token = "token",
        syncRoot = "/storage/emulated/0/Seafile",
        deviceId = "a1b2c3d4e5f60718",
    )

    private fun library(id: String) = SyncedRepoEntity(repoId = id, name = id, localPath = "/sync/$id")

    @Test
    fun `switching off the last library ends the work but switching off one of two does not`() = runTest {
        val libraries = MutableStateFlow(listOf(library("a"), library("b")))
        val demand = SyncDemand(MutableStateFlow(account), libraries)
        assertTrue(demand.current())

        libraries.value = listOf(library("b"))
        assertTrue(demand.current())

        libraries.value = emptyList()
        assertFalse(demand.current())
    }

    @Test
    fun `signing out ends the work even though the libraries stay switched on`() = runTest {
        // Sign-out clears the account but keeps the library rows for the next sign-in.
        val signedIn = MutableStateFlow<Account?>(account)
        val demand = SyncDemand(signedIn, MutableStateFlow(listOf(library("a"))))
        assertTrue(demand.current())

        signedIn.value = null
        assertFalse(demand.current())
    }
}
