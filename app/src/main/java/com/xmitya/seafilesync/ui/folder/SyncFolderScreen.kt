package com.xmitya.seafilesync.ui.folder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xmitya.seafilesync.R
import com.xmitya.seafilesync.data.fs.DirectoryBrowser

const val FOLDER_ENTRY_TAG = "folder-entry"
const val FOLDER_CONFIRM_TAG = "folder-confirm"

/**
 * Picks the directory libraries are mirrored into (requirement 2 and 3).
 *
 * A plain directory browser rather than ACTION_OPEN_DOCUMENT_TREE: the sync engine works in real
 * paths, and turning a tree Uri back into one is guesswork that breaks on secondary volumes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncFolderScreen(
    browser: DirectoryBrowser,
    onFolderChosen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var current by remember { mutableStateOf(browser.initialDirectory()) }
    var creatingFolder by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val listing = remember(current, creatingFolder) { browser.list(current) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.folder_title))
                        Text(
                            text = listing.current.path,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.folder_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { creatingFolder = true }) {
                        Text(stringResource(R.string.folder_new))
                    }
                    Button(
                        onClick = {
                            if (browser.canUseAsSyncRoot(listing.current)) {
                                onFolderChosen(listing.current.path)
                            } else {
                                errorMessage = "Cannot write to ${listing.current.path}"
                            }
                        },
                        modifier = Modifier.weight(1f).testTag(FOLDER_CONFIRM_TAG),
                    ) {
                        Text(stringResource(R.string.folder_select))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            listing.parent?.let { parent ->
                item {
                    FolderRow(
                        name = stringResource(R.string.folder_up),
                        subtitle = parent.path,
                        onClick = {
                            current = parent
                            errorMessage = null
                        },
                    )
                    HorizontalDivider()
                }
            }
            items(listing.entries, key = { it.file.path }) { entry ->
                FolderRow(
                    name = entry.name,
                    subtitle = entry.childDirectoryCount
                        .takeIf { it > 0 }
                        ?.let { pluralStringResource(R.plurals.folder_child_count, it, it) },
                    onClick = {
                        current = entry.file
                        errorMessage = null
                    },
                )
                HorizontalDivider()
            }
        }
    }

    if (creatingFolder) {
        NewFolderDialog(
            onDismiss = { creatingFolder = false },
            onCreate = { name ->
                creatingFolder = false
                browser
                    .createDirectory(listing.current, name)
                    .onSuccess {
                        current = it
                        errorMessage = null
                    }.onFailure { errorMessage = it.message }
            },
        )
    }
}

@Composable
private fun FolderRow(name: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(FOLDER_ENTRY_TAG)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("Seafile") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.folder_new))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_action_dismiss)) }
        },
    )
}
