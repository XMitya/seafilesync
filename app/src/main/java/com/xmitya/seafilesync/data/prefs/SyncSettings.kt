package com.xmitya.seafilesync.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class SyncPreferences(
    /** Pause transfers on a metered connection. On by default: mobile data is the user's money. */
    val wifiOnly: Boolean = true,
    /** How often to ask the server whether anything changed. */
    val pollIntervalSeconds: Long = DEFAULT_POLL_SECONDS,
) {
    companion object {
        const val DEFAULT_POLL_SECONDS = 30L

        /**
         * The account token is rate limited to 3000 requests a minute, and one poll covers every
         * library in a single request, so even the shortest interval here is far inside it.
         */
        val POLL_CHOICES = listOf(15L, 30L, 60L, 300L, 900L)
    }
}

class SyncSettings(
    private val dataStore: DataStore<Preferences>,
) {

    val preferences: Flow<SyncPreferences> = dataStore.data.map {
        SyncPreferences(
            wifiOnly = it[WIFI_ONLY] ?: true,
            pollIntervalSeconds = it[POLL_INTERVAL] ?: SyncPreferences.DEFAULT_POLL_SECONDS,
        )
    }

    suspend fun current(): SyncPreferences = preferences.first()

    suspend fun setWifiOnly(value: Boolean) {
        dataStore.edit { it[WIFI_ONLY] = value }
    }

    suspend fun setPollInterval(seconds: Long) {
        dataStore.edit { it[POLL_INTERVAL] = seconds }
    }

    private companion object {
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val POLL_INTERVAL = longPreferencesKey("poll_interval_seconds")
    }
}
