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
            SettingsScreen(
                preferences = preferences,
                accountEmail = account?.email.orEmpty(),
                serverUrl = account?.serverUrl.orEmpty(),
                syncRoot = account?.syncRoot.orEmpty(),
                onWifiOnlyChanged = viewModel::setWifiOnly,
                onPollIntervalChanged = viewModel::setPollInterval,
                onSignOut = viewModel::signOut,
                onBack = viewModel::closeSettings,
                modifier = modifier,
            )
        }
    }
}
