package com.xmitya.seafilesync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xmitya.seafilesync.data.fs.DirectoryBrowser
import com.xmitya.seafilesync.ui.folder.SyncFolderScreen
import com.xmitya.seafilesync.ui.libraries.LibrariesScreen
import com.xmitya.seafilesync.ui.login.LoginScreen
import com.xmitya.seafilesync.ui.permissions.AskForNotificationPermission
import com.xmitya.seafilesync.ui.permissions.BatteryOptimizationBanner
import com.xmitya.seafilesync.ui.settings.SettingsScreen
import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.xmitya.seafilesync.R
import com.xmitya.seafilesync.app.appContainer
import com.xmitya.seafilesync.ui.permissions.RequireAllFilesAccess

@Composable
fun MainScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val destination by viewModel.destination.collectAsStateWithLifecycle()

    when (destination) {
        Destination.Loading -> Box(modifier.fillMaxSize()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        Destination.Login -> {
            val state by viewModel.login.collectAsStateWithLifecycle()
            LoginScreen(
                state = state,
                onServerUrlChanged = viewModel::onServerUrlChanged,
                onEmailChanged = viewModel::onEmailChanged,
                onPasswordChanged = viewModel::onPasswordChanged,
                onOtpChanged = viewModel::onOtpChanged,
                onSubmit = viewModel::signIn,
                modifier = modifier,
            )
        }

        Destination.ChooseSyncFolder -> {
            // Storage access is requested here rather than at launch, so the permission prompt
            // arrives with a reason the user has already seen: they just asked to connect an
            // account and pick a folder.
            RequireAllFilesAccess(modifier) {
                val browser = remember { DirectoryBrowser() }
                SyncFolderScreen(browser = browser, onFolderChosen = viewModel::onSyncFolderChosen)
            }
        }

        Destination.Libraries -> {
            AskForNotificationPermission()
            val state by viewModel.libraries.collectAsStateWithLifecycle()
            LibrariesScreen(
                state = state,
                onSync = viewModel::startSyncing,
                onStopSyncing = viewModel::stopSyncing,
                onRetry = { viewModel.startSyncing(it) },
                onRefresh = viewModel::refreshLibraries,
                onOpenSettings = viewModel::openSettings,
                onDismissError = viewModel::dismissError,
                modifier = modifier,
                header = { BatteryOptimizationBanner() },
            )
        }

        Destination.Settings -> {
            val preferences by viewModel.settings.collectAsStateWithLifecycle()
            val account by viewModel.account.collectAsStateWithLifecycle()
            val logSize by viewModel.logSize.collectAsStateWithLifecycle()
            val context = LocalContext.current
            SettingsScreen(
                preferences = preferences,
                accountEmail = account?.email.orEmpty(),
                serverUrl = account?.serverUrl.orEmpty(),
                syncRoot = account?.syncRoot.orEmpty(),
                onWifiOnlyChanged = viewModel::setWifiOnly,
                onPollIntervalChanged = viewModel::setPollInterval,
                onSignOut = viewModel::signOut,
                onBack = viewModel::closeSettings,
                logSizeBytes = logSize,
                onShareLog = { shareLog(context, viewModel) },
                onClearLog = viewModel::clearLog,
                modifier = modifier,
            )
        }
    }
}

/**
 * Hands the log to whatever the user picks. Shared through a FileProvider grant rather than a
 * file path, which is the only way another app is allowed to read it, and from the cache so the
 * copy is not kept around.
 */
private fun shareLog(context: Context, viewModel: MainViewModel) {
    val exported = context.appContainer.log.exportTo(context.cacheDir)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", exported)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_log_share_title))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(share, context.getString(R.string.settings_log_share_title)))
}
