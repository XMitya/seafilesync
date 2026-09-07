package com.xmitya.seafilesync.ui.libraries

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xmitya.seafilesync.R

const val LIBRARY_ROW_TAG = "library-row"
const val LIBRARY_ACTIONS_TAG = "library-actions"

/**
 * The main screen: every library on the account, each with its sync status, and a sheet of
 * actions when one is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrariesScreen(
    state: LibrariesUiState,
    onSync: (LibraryUi) -> Unit,
    onStopSyncing: (LibraryUi) -> Unit,
    onRetry: (LibraryUi) -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<LibraryUi?>(null) }
    var confirmingStopFor by remember { mutableStateOf<LibraryUi?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.libraries_title))
                        SubtitleForState(state)
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            painter = painterResource(R.drawable.ic_sync),
                            contentDescription = stringResource(R.string.libraries_refresh),
                        )
                    }
                    TextButton(onClick = onSignOut) {
                        Text(stringResource(R.string.libraries_sign_out))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isRefreshing && state.libraries.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.libraries.isEmpty() ->
                    Text(
                        text = stringResource(R.string.libraries_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.libraries, key = { it.id }) { library ->
                        LibraryRow(library, onClick = { selected = library })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    selected?.let { library ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(),
            modifier = Modifier.testTag(LIBRARY_ACTIONS_TAG),
        ) {
            LibraryActions(
                library = library,
                onSync = { selected = null; onSync(library) },
                // Stopping is the one action that can look destructive, so it is confirmed.
                onStopSyncing = { selected = null; confirmingStopFor = library },
                onRetry = { selected = null; onRetry(library) },
            )
        }
    }

    confirmingStopFor?.let { library ->
        AlertDialog(
            onDismissRequest = { confirmingStopFor = null },
            title = { Text(stringResource(R.string.library_stop_confirm_title, library.name)) },
            text = { Text(stringResource(R.string.library_stop_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { confirmingStopFor = null; onStopSyncing(library) }) {
                    Text(stringResource(R.string.library_stop_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingStopFor = null }) {
                    Text(stringResource(R.string.library_action_dismiss))
                }
            },
        )
    }
}

@Composable
private fun SubtitleForState(state: LibrariesUiState) {
    val subtitle = when {
        state.failedCount > 0 -> stringResource(R.string.sync_state_error)
        state.transferringCount > 0 -> stringResource(R.string.sync_state_syncing)
        else -> state.accountEmail
    }
    if (subtitle.isNotEmpty()) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = if (state.failedCount > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun LibraryRow(library: LibraryUi, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(LIBRARY_ROW_TAG)
            .clickable(enabled = library.isSupported, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = library.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = librarySubtitle(library),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SyncStatusIcon(library.state, Modifier.padding(start = 12.dp))
        }

        if (library.state == SyncState.Syncing) {
            val progress = library.progress
            if (progress == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun librarySubtitle(library: LibraryUi): String = when {
    !library.isSupported -> stringResource(R.string.libraries_encrypted_unsupported)
    library.state == SyncState.Error -> library.errorMessage ?: stringResource(R.string.sync_state_error)
    library.localPath != null -> library.localPath
    !library.isWritable -> stringResource(R.string.libraries_read_only)
    else -> formatSize(library.sizeBytes)
}

@Composable
private fun LibraryActions(
    library: LibraryUi,
    onSync: () -> Unit,
    onStopSyncing: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = library.name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        // Which actions exist depends on the current state, so the sheet never offers a
        // no-op (requirement 7).
        if (library.canRetry) SheetAction(R.string.library_action_retry, R.drawable.ic_sync, onRetry)
        if (library.canSync) SheetAction(R.string.library_action_sync, R.drawable.ic_cloud, onSync)
        if (library.canStopSyncing) {
            SheetAction(R.string.library_action_stop, R.drawable.ic_sync_paused, onStopSyncing)
        }
    }
}

@Composable
private fun SheetAction(labelRes: Int, iconRes: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

internal fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 10) "${value.toLong()} ${units[unit]}" else String.format("%.1f %s", value, units[unit])
}
