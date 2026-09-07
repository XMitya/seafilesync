package com.xmitya.seafilesync.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xmitya.seafilesync.app.appContainer
import java.util.concurrent.TimeUnit

/**
 * Restarts the sync service when it is not running.
 *
 * Deliberately a backstop rather than the main mechanism. In the RESTRICTED App Standby bucket
 * periodic work does not run on its own at all -- it needs another app's job to piggyback on --
 * and the effective minimum interval is longer than 15 minutes on several vendors' builds. What
 * actually keeps syncing alive is the battery-optimisation exemption; this only catches the case
 * where the service died and nothing else would notice.
 */
class SyncWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        if (container.accountStore.current() == null) {
            // Signed out: nothing to keep alive, and starting a service here would be a bug.
            WorkManager.getInstance(applicationContext).cancelUniqueWork(WORK_NAME)
            return Result.success()
        }
        if (container.database.syncedRepos().all().isEmpty()) return Result.success()

        SyncForegroundService.start(applicationContext)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "sync-watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // UPDATE, never KEEP. KEEP is a no-op whenever work with this name already
                // exists, so a watchdog re-enqueued on every app start would silently ignore
                // every change shipped afterwards -- new interval, new constraints, new class --
                // with no error and no log, for as long as the old work survives.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
