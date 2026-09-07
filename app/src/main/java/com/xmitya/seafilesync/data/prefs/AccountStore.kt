package com.xmitya.seafilesync.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The signed-in account. Only one is supported, which is why this is a single record rather than
 * a table.
 *
 * [Account.token] is held encrypted on disk and only decrypted on read, so a device backup or a
 * stray adb pull does not hand over a working credential.
 */
data class Account(
    val serverUrl: String,
    val email: String,
    val token: String,
    /** Absolute path of the directory libraries are mirrored into. */
    val syncRoot: String,
    /** Stable per-install id sent at login; the server uses it to list and wipe devices. */
    val deviceId: String,
)

class AccountStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    /** Records the one failure here that is otherwise indistinguishable from a normal sign-out. */
    private val onTokenUnreadable: (Throwable) -> Unit = {},
) {

    val account: Flow<Account?> = dataStore.data.map { it.toAccount() }

    suspend fun current(): Account? = account.first()

    suspend fun save(account: Account) {
        dataStore.edit { prefs ->
            prefs[SERVER_URL] = account.serverUrl
            prefs[EMAIL] = account.email
            prefs[TOKEN] = cipher.encrypt(account.token)
            prefs[SYNC_ROOT] = account.syncRoot
            prefs[DEVICE_ID] = account.deviceId
        }
    }

    suspend fun updateSyncRoot(path: String) {
        dataStore.edit { it[SYNC_ROOT] = path }
    }

    /**
     * Forgets the account. Used both for an explicit sign-out and after the server reports the
     * device was remotely wiped.
     */
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    /**
     * A token that cannot be decrypted is treated as no account at all: the Keystore key can be
     * dropped by a device restore or a lock-screen change, and there is nothing to do about it
     * but ask the user to sign in again.
     */
    private fun Preferences.toAccount(): Account? {
        val encrypted = this[TOKEN] ?: return null
        val token = runCatching { cipher.decrypt(encrypted) }
            .onFailure(onTokenUnreadable)
            .getOrNull() ?: return null
        return Account(
            serverUrl = this[SERVER_URL] ?: return null,
            email = this[EMAIL] ?: return null,
            token = token,
            syncRoot = this[SYNC_ROOT] ?: return null,
            deviceId = this[DEVICE_ID] ?: return null,
        )
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val EMAIL = stringPreferencesKey("email")
        val TOKEN = stringPreferencesKey("token")
        val SYNC_ROOT = stringPreferencesKey("sync_root")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }
}
