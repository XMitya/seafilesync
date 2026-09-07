package com.xmitya.seafilesync.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.xmitya.seafilesync.BuildConfig
import com.xmitya.seafilesync.data.api.RetryInterceptor
import com.xmitya.seafilesync.data.api.SeafHttpApi
import com.xmitya.seafilesync.data.api.SeafileApi
import com.xmitya.seafilesync.data.api.UserAgentInterceptor
import com.xmitya.seafilesync.data.prefs.AccountStore
import com.xmitya.seafilesync.data.db.SyncDatabase
import com.xmitya.seafilesync.data.prefs.KeystoreTokenCipher
import com.xmitya.seafilesync.data.prefs.SyncSettings
import com.xmitya.seafilesync.service.NetworkPolicy
import com.xmitya.seafilesync.sync.SyncEngine
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.accountDataStore: DataStore<Preferences> by preferencesDataStore(name = "account")

/**
 * Manual dependency container. Deliberately hand-rolled instead of using Hilt: AGP 9 is new
 * enough that keeping annotation processors off the critical path is worth more than the
 * boilerplate they would save at this size.
 *
 * Dependencies are lazy so that nothing but the process itself is built on cold start.
 */
class AppContainer(private val applicationContext: Context) {

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Kept short on purpose. A host with several A records where only some answer is
            // common, and OkHttp only tries the next address once this expires, so a long
            // connect timeout turns a working server into a two-minute hang.
            .connectTimeout(10, TimeUnit.SECONDS)
            // Reads and writes stay generous: blocks reach 4 MiB, the server does not honour
            // Range, and abandoning a slow but progressing transfer costs the whole block.
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .addInterceptor(UserAgentInterceptor(userAgent))
            .addInterceptor(RetryInterceptor())
            .build()
    }

    private val tokenCipher: KeystoreTokenCipher by lazy { KeystoreTokenCipher() }

    val accountStore: AccountStore by lazy {
        AccountStore(applicationContext.accountDataStore, tokenCipher)
    }

    val settings: SyncSettings by lazy { SyncSettings(applicationContext.accountDataStore) }

    val networkPolicy: NetworkPolicy by lazy { NetworkPolicy(applicationContext) }

    val deviceId: String by lazy { DeviceIdentity.deviceId(applicationContext) }

    val appVersion: String = BuildConfig.VERSION_NAME

    /** Matches the shape the desktop daemon reports, so sessions are recognisable server-side. */
    val userAgent: String = "Seafile/$appVersion (Android)"

    val database: SyncDatabase by lazy { SyncDatabase.open(applicationContext) }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            repos = database.syncedRepos(),
            fileIndex = database.fileIndex(),
            apiFor = ::seafileApi,
            seafHttpFor = ::seafHttpApi,
            cipher = tokenCipher,
            deviceName = DeviceIdentity.deviceName(),
            clientVersion = appVersion,
        )
    }

    fun seafileApi(serverUrl: String) = SeafileApi(serverUrl, httpClient)

    fun seafHttpApi(serverUrl: String) = SeafHttpApi(serverUrl, httpClient)
}
