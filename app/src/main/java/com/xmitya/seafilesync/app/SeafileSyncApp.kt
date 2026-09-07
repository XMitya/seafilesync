package com.xmitya.seafilesync.app

import android.app.Application

class SeafileSyncApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}

/** Convenience accessor for the container from any [android.content.Context]. */
val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as SeafileSyncApp).container
