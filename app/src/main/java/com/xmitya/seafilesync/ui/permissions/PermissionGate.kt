package com.xmitya.seafilesync.ui.permissions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xmitya.seafilesync.R

const val PERMISSION_GRANT_TAG = "permission-grant"

/**
 * Blocks [content] until the app can write to the user's chosen folder.
 *
 * All-files access cannot be granted from a dialog: it is a settings screen the user has to walk
 * through, and the app only learns the outcome by re-checking after it comes back to the
 * foreground. Hence the lifecycle observer rather than an activity result.
 */
@Composable
fun RequireAllFilesAccess(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = Environment.isExternalStorageManager()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) {
        content()
        return
    }

    Explanation(
        title = stringResource(R.string.permission_storage_title),
        body = stringResource(R.string.permission_storage_explanation),
        action = stringResource(R.string.permission_storage_grant),
        modifier = modifier,
        onAction = {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        },
    )
}

/**
 * Asks for notification permission once. Not a hard gate: syncing should not be blocked by it,
 * but without a notification Android will not let the foreground service run for long, so the
 * ask happens up front rather than at the first transfer.
 */
@Composable
fun AskForNotificationPermission() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Declining is allowed; the service degrades rather than fails. */ }

    // Must be launched from an effect, not from the composable body. The launcher is only
    // registered once composition finishes, so calling it inline throws "Launcher has not been
    // initialized" -- and writing state during composition would be wrong regardless.
    LaunchedEffect(Unit) {
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun Explanation(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = onAction,
            modifier = Modifier.fillMaxWidth().testTag(PERMISSION_GRANT_TAG),
        ) {
            Text(action)
        }
    }
}
