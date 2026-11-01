package com.xmitya.seafilesync.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xmitya.seafilesync.app.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Brings syncing back after a restart.
 *
 * Without this the app only resumes when the user next opens it, which for a background sync
 * client means it quietly stops working after every reboot.
 *
 * On Android 15+ the start only succeeds for an app exempt from battery optimisation: a dataSync
 * service started on the strength of BOOT_COMPLETED alone is refused. The service handles that
 * refusal itself. Deferring the start to a job does not help, since a job is refused the same way
 * without the exemption and is not needed with it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return

        // The receiver must return quickly, so the check happens in a goAsync block rather than
        // blocking the main thread on a DataStore read.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (context.appContainer.syncDemand.current()) {
                    SyncForegroundService.start(context)
                    SyncWatchdogWorker.schedule(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // Several vendors send this instead of, or before, BOOT_COMPLETED on a fast boot.
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
