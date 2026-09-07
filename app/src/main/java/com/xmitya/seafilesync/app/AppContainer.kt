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
import com.xmitya.seafilesync.data.prefs.KeystoreTokenCipher
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
            // Blocks reach 4 MiB and the server does not honour Range, so an interrupted read
            // costs a whole block. Timeouts are generous enough not to abandon a slow mobile
            // connection that is still making progress.
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .addInterceptor(UserAgentInterceptor(userAgent))
            .addInterceptor(RetryInterceptor())
            .build()
    }

    val accountStore: AccountStore by lazy {
        AccountStore(applicationContext.accountDataStore, KeystoreTokenCipher())
    }

    val deviceId: String by lazy { DeviceIdentity.deviceId(applicationContext) }

    /** Matches the shape the desktop daemon reports, so sessions are recognisable server-side. */
    val userAgent: String = "Seafile/${BuildConfig.VERSION_NAME} (Android)"

    fun seafileApi(serverUrl: String) = SeafileApi(serverUrl, httpClient)

    fun seafHttpApi(serverUrl: String) = SeafHttpApi(serverUrl, httpClient)
}
