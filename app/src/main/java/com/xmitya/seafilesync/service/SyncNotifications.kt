package com.xmitya.seafilesync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.xmitya.seafilesync.MainActivity
import com.xmitya.seafilesync.R
import com.xmitya.seafilesync.sync.SyncStatus

/**
 * The persistent notification from requirement 8: the app name plus what is being transferred,
 * opening the main activity when tapped.
 *
 * It is not decoration. A dataSync foreground service that cannot post a notification is stopped
 * by the system, so this is what keeps syncing alive in the background.
 */
class SyncNotifications(
    private val context: Context,
) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_sync),
            // Low: it must be visible and persistent, but it has nothing urgent to say and
            // should never make a sound.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_sync_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(status: SyncStatus): Notification {
        val active = status.activeRepos.values.firstOrNull()
        val builder = NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setColor(context.getColor(R.color.brand_amber))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (active == null) {
            return builder.setContentText(context.getString(R.string.notification_idle)).build()
        }

        val fraction = active.fraction
        return builder
            .setContentText(
                context.getString(
                    R.string.notification_syncing,
                    active.name,
                    active.currentPath?.substringAfterLast('/').orEmpty(),
                ),
            ).apply {
                if (fraction == null) {
                    setProgress(0, 0, true)
                } else {
                    setProgress(PROGRESS_SCALE, (fraction * PROGRESS_SCALE).toInt(), false)
                }
            }.build()
    }

    fun update(status: SyncStatus) {
        manager.notify(NOTIFICATION_ID, build(status))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_ID = "sync"
        const val NOTIFICATION_ID = 1
        private const val PROGRESS_SCALE = 1000
    }
}
