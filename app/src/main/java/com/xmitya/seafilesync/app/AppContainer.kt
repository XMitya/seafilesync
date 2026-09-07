package com.xmitya.seafilesync.app

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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
            // Blocks can reach 4 MiB and the server does not honour Range requests, so a
            // stalled read has to be given room before it is worth abandoning.
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }
}
