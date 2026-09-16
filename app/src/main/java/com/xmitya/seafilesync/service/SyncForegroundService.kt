package com.xmitya.seafilesync.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.xmitya.seafilesync.app.appContainer
import com.xmitya.seafilesync.sync.LocalChangeWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

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
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

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
            watcher = LocalChangeWatcher(scope, appContainer.log) { repoId -> syncOne(repoId) }
            // Locks are held only while bytes are actually moving. Holding them for the whole
            // life of the service would keep the CPU and radio awake through every idle poll.
            scope.launch {
                appContainer.syncEngine.status.collect { status ->
                    if (status.isTransferring) acquireLocks() else releaseLocks()
                }
            }
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
            val preferences = container.settings.current()
            if (container.networkPolicy.current().allowsSync(preferences.wifiOnly)) {
                runCatching {
                    val due = container.syncEngine.reposNeedingSync(account)
                    due.forEach { container.syncEngine.sync(account, it) }
                    // Watches are re-established after each pass: directories created by the
                    // sync itself are not covered by a watch set up before they existed.
                    due.forEach { watcher?.watch(it.repoId, java.io.File(it.localPath)) }
                }
            }
            delay(preferences.pollIntervalSeconds * 1000)
        }
    }

    /**
     * Android 15 and later cap a dataSync foreground service at roughly six hours a day, and
     * when the budget runs out the system calls this and expects the service to stop. Failing to
     * do so kills the process with ForegroundServiceDidNotStopInTimeException.
     *
     * Stopping is not the end of syncing: state lives in the database, so the watchdog restarts
     * the service once the budget resets, which it does whenever the app is in the foreground.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        appContainer.log.info("Foreground service budget exhausted; stopping, watchdog will restart it")
        SyncWatchdogWorker.schedule(this)
        stopSelf(startId)
    }

    /**
     * Swiping the app away from Recents stops the service on many builds even though it is a
     * foreground service, so it is scheduled to come back. An inexact alarm is deliberate: this
     * is not time-critical and an exact one would need a permission users have to grant.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restart = PendingIntent.getForegroundService(
            this,
            0,
            Intent(this, SyncForegroundService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        getSystemService(AlarmManager::class.java).set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + RESTART_DELAY_MILLIS,
            restart,
        )
        super.onTaskRemoved(rootIntent)
    }

    /** Runs a single library out of turn, after a local edit was noticed. */
    private suspend fun syncOne(repoId: String) {
        val container = appContainer
        val account = container.accountStore.current() ?: return
        if (!container.networkPolicy.current().allowsSync(container.settings.current().wifiOnly)) return
        val repo = container.database.syncedRepos().byId(repoId) ?: return
        container.syncEngine.sync(account, repo)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$LOCK_TAG:transfer")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            }
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "$LOCK_TAG:transfer")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
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
        private const val NOTIFICATION_THROTTLE_MILLIS = 1_000L
        private const val RESTART_DELAY_MILLIS = 5_000L

        /** Shows up in battery attribution, so it names the app rather than the class. */
        private const val LOCK_TAG = "SeafileSync"

        /**
         * A timeout on the wake lock so a bug in the sync loop cannot drain the battery
         * indefinitely; a transfer that legitimately runs longer re-acquires it.
         */
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60 * 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncForegroundService::class.java))
        }
    }
}
