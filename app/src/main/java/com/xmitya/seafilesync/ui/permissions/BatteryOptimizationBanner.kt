package com.xmitya.seafilesync.ui.permissions

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xmitya.seafilesync.R

const val BATTERY_BANNER_TAG = "battery-optimization-banner"

/**
 * Asks to be left out of battery optimisation, which is what actually keeps background syncing
 * alive.
 *
 * This is not a nicety. Under battery optimisation the app eventually lands in the RESTRICTED
 * standby bucket, where periodic work does not run on its own at all -- it has to piggyback on
 * another app's job -- so the watchdog stops being a safety net. There is no API to detect that
 * bucket or escape it; staying exempt is the only reliable answer.
 *
 * Shown as a dismissible banner rather than a blocking gate, because syncing still works while
 * the app is open and refusing to proceed would be disproportionate.
 */
@SuppressLint("BatteryLife")
@Composable
fun BatteryOptimizationBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }

    var exempt by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    var dismissed by remember { mutableStateOf(false) }

    // The user grants this in Settings, so the outcome is only visible on returning.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (exempt || dismissed) return

    Card(
        modifier = modifier.fillMaxWidth().padding(16.dp).testTag(BATTERY_BANNER_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.battery_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.battery_explanation),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                }) {
                    Text(stringResource(R.string.battery_allow))
                }
                TextButton(onClick = { dismissed = true }) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }
    }
}
