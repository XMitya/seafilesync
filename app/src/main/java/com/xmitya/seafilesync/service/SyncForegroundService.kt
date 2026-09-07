package com.xmitya.seafilesync.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.xmitya.seafilesync.app.appContainer
import com.xmitya.seafilesync.sync.LocalChangeWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.Dispatchers

/**
 * Runs the sync loop outside the activity.
 *
 * Intentionally minimal for this milestone: it exists so the vertical slice is complete and the
 * integration problems of running in the background show up early. Restart-on-kill, boot
 * handling, doze, the daily dataSync budget and the watchdog all belong to the resilience
 * milestone and are not attempted here.
 */
class SyncForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notifications: SyncNotifications
    private var syncLoop: Job? = null
    private var watcher: LocalChangeWatcher? = null

    override fun onCreate() {
        super.onCreate()
        notifications = SyncNotifications(this)
        notifications.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen promptly after start or the system kills the process, so it goes before
        // any work rather than after the first sync pass.
        ServiceCompat.startForeground(
            this,
            SyncNotifications.NOTIFICATION_ID,
            notifications.build(appContainer.syncEngine.status.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        if (syncLoop == null) {
            syncLoop = scope.launch { runSyncLoop() }
            watcher = LocalChangeWatcher(scope) { repoId -> syncOne(repoId) }
            scope.launch {
                appContainer.syncEngine.status.collectLatest { status ->
                    notifications.update(status)
                    // The notification is redrawn on every progress tick, so it is throttled to
                    // roughly once a second; updating per block would burn battery and risk ANRs.
                    delay(NOTIFICATION_THROTTLE_MILLIS)
                }
            }
        }

        // The system should bring the service back if it is killed for memory. Real resilience
        // needs more than this and arrives with the resilience milestone.
        return START_STICKY
    }

    private suspend fun runSyncLoop() {
        val container = appContainer
        while (scope.isActive) {
            val account = container.accountStore.current()
            if (account == null) {
                stopSelf()
                return
            }
            runCatching {
                val due = container.syncEngine.reposNeedingSync(account)
                due.forEach { container.syncEngine.sync(account, it) }
                // Watches are re-established after each pass: directories created by the sync
                // itself are not covered by an inotify watch set up before they existed.
                due.forEach { watcher?.watch(it.repoId, java.io.File(it.localPath)) }
            }
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    /** Runs a single library out of turn, after a local edit was noticed. */
    private suspend fun syncOne(repoId: String) {
        val container = appContainer
        val account = container.accountStore.current() ?: return
        val repo = container.database.syncedRepos().byId(repoId) ?: return
        container.syncEngine.sync(account, repo)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watcher?.stopAll()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /**
         * The server has no push channel unless a notification service is deployed, so remote
         * changes are only noticed by asking. Thirty seconds is a compromise between latency and
         * battery; the resilience milestone makes it adaptive.
         */
        private const val POLL_INTERVAL_MILLIS = 30_000L
        private const val NOTIFICATION_THROTTLE_MILLIS = 1_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncForegroundService::class.java))
        }
    }
}
