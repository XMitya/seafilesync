package com.xmitya.seafilesync.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xmitya.seafilesync.R
import com.xmitya.seafilesync.data.prefs.SyncPreferences

const val SETTINGS_WIFI_ONLY_TAG = "settings-wifi-only"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: SyncPreferences,
    accountEmail: String,
    serverUrl: String,
    syncRoot: String,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onPollIntervalChanged: (Long) -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    logSizeBytes: Long = 0,
    onShareLog: () -> Unit = {},
    onClearLog: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        ) {
            Setting(
                title = stringResource(R.string.settings_account),
                subtitle = "$accountEmail\n$serverUrl",
            )
            HorizontalDivider()

            Setting(
                title = stringResource(R.string.settings_sync_folder),
                subtitle = syncRoot,
            )
            HorizontalDivider()

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onWifiOnlyChanged(!preferences.wifiOnly) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_wifi_only), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = stringResource(R.string.settings_wifi_only_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = preferences.wifiOnly,
                    onCheckedChange = onWifiOnlyChanged,
                    modifier = Modifier.testTag(SETTINGS_WIFI_ONLY_TAG),
                )
            }
            HorizontalDivider()

            Text(
                text = stringResource(R.string.settings_poll_interval),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )
            Text(
                // There is no push channel unless the server runs a notification service, so this
                // interval is the whole of how quickly remote changes are noticed.
                text = stringResource(R.string.settings_poll_interval_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                SyncPreferences.POLL_CHOICES.forEach { seconds ->
                    TextButton(onClick = { onPollIntervalChanged(seconds) }) {
                        Text(
                            text = formatInterval(seconds),
                            color = if (seconds == preferences.pollIntervalSeconds) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            HorizontalDivider()

            Text(
                text = stringResource(R.string.settings_diagnostics),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )
            Text(
                // Worth saying plainly: the interesting failures happen with the screen off,
                // hours before anyone looks, which is why there is a file at all.
                text = if (logSizeBytes > 0) {
                    stringResource(R.string.settings_log_size, formatBytes(logSizeBytes))
                } else {
                    stringResource(R.string.settings_log_empty)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.padding(8.dp)) {
                TextButton(onClick = onShareLog, enabled = logSizeBytes > 0) {
                    Text(stringResource(R.string.settings_log_share))
                }
                TextButton(onClick = onClearLog, enabled = logSizeBytes > 0) {
                    Text(stringResource(R.string.settings_log_clear))
                }
            }
            HorizontalDivider()

            TextButton(onClick = onSignOut, modifier = Modifier.padding(8.dp)) {
                Text(
                    text = stringResource(R.string.libraries_sign_out),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun Setting(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

private fun formatInterval(seconds: Long): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60}m"

private fun formatBytes(bytes: Long): String =
    if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"
