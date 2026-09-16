package com.xmitya.seafilesync.ui.libraries

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xmitya.seafilesync.R

/** Lets tests find the indicator without depending on which state is drawn. */
const val SYNC_STATUS_ICON_TAG = "sync-status-icon"

/**
 * The per-library status indicator: a cloud when synced, spinning arrows while transferring, a
 * red exclamation mark on failure, and nothing at all when the library is not synced, so that
 * synced libraries are the ones that stand out.
 *
 * The rotation runs off an infinite transition, which Compose stops on its own once the screen
 * leaves the composition, so it costs nothing while the app sits in the background.
 */
@Composable
fun SyncStatusIcon(state: SyncState, modifier: Modifier = Modifier) {
    if (state == SyncState.NotSynced) return

    val icon = when (state) {
        SyncState.Synced -> R.drawable.ic_cloud
        SyncState.Syncing -> R.drawable.ic_sync
        SyncState.Paused -> R.drawable.ic_sync_paused
        SyncState.Error -> R.drawable.ic_sync_error
        SyncState.NotSynced -> return
    }

    val description = stringResource(
        when (state) {
            SyncState.Synced -> R.string.sync_state_synced
            SyncState.Syncing -> R.string.sync_state_syncing
            SyncState.Paused -> R.string.sync_state_paused
            SyncState.Error -> R.string.sync_state_error
            SyncState.NotSynced -> return
        },
    )

    val tint = when (state) {
        SyncState.Error -> MaterialTheme.colorScheme.error
        SyncState.Syncing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Icon(
        painter = painterResource(icon),
        contentDescription = description,
        tint = tint,
        modifier = modifier
            .testTag(SYNC_STATUS_ICON_TAG)
            .size(20.dp)
            .let { if (state == SyncState.Syncing) it.spin() else it },
    )
}

@Composable
private fun Modifier.spin(): Modifier {
    val transition = rememberInfiniteTransition(label = "sync-spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sync-angle",
    )
    return rotate(angle)
}
