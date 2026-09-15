package com.xmitya.seafilesync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xmitya.seafilesync.app.AppContainer
import com.xmitya.seafilesync.app.DeviceIdentity
import com.xmitya.seafilesync.data.api.SeafileException
import com.xmitya.seafilesync.data.db.SyncedRepoEntity
import com.xmitya.seafilesync.data.prefs.Account
import com.xmitya.seafilesync.data.prefs.SyncPreferences
import com.xmitya.seafilesync.sync.SyncStatus
import com.xmitya.seafilesync.ui.libraries.LibrariesUiState
import com.xmitya.seafilesync.ui.libraries.LibraryUi
import com.xmitya.seafilesync.ui.libraries.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** Where the user currently is. Only three places exist, so this beats a navigation graph. */
sealed interface Destination {
    data object Loading : Destination
    data object Login : Destination

    /**
     * Reached only between a successful sign-in and the account being saved: requirement 3 says
     * the sync folder is chosen as part of connecting an account.
     */
    data object ChooseSyncFolder : Destination

    data object Libraries : Destination

    data object Settings : Destination
}

data class LoginUiState(
    /** The host alone: the scheme lives in [useHttps] so the two cannot disagree. */
    val serverUrl: String = "",
    val email: String = "",
    val password: String = "",
    val otp: String = "",
    /** Shown only after the server has asked for a code, so the field is not there by default. */
    val needsOtp: Boolean = false,
    val useHttps: Boolean = true,
    val allowInsecureTls: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
) {

    /** What actually gets dialled: the field holds the host, the selector holds the scheme. */
    val fullServerUrl: String
        get() = "${if (useHttps) "https" else "http"}://${serverUrl.trim().trimEnd('/')}"

    /**
     * The one place that decides whether certificates are checked. Over http there is no
     * certificate to skip, so the exemption cannot leak out of the state it was meant for.
     */
    val skipCertificateCheck: Boolean get() = useHttps && allowInsecureTls
}

private const val HTTPS_PREFIX = "https://"
private const val HTTP_PREFIX = "http://"

/**
 * Moves a scheme the user typed or pasted into [LoginUiState.useHttps], leaving the field with
 * the host alone. Pasting a whole URL is the normal way to fill this in, and without this the
 * field and the scheme selector would show different things.
 */
internal fun LoginUiState.withTypedServerUrl(typed: String): LoginUiState = when {
    typed.startsWith(HTTPS_PREFIX, ignoreCase = true) ->
        copy(serverUrl = typed.drop(HTTPS_PREFIX.length), useHttps = true)

    typed.startsWith(HTTP_PREFIX, ignoreCase = true) ->
        copy(serverUrl = typed.drop(HTTP_PREFIX.length), useHttps = false, allowInsecureTls = false)

    else -> copy(serverUrl = typed)
}

class MainViewModel(
    private val container: AppContainer,
    private val onSyncingStarted: () -> Unit = {},
) : ViewModel() {

    private val _destination = MutableStateFlow<Destination>(Destination.Loading)
    val destination: StateFlow<Destination> = _destination.asStateFlow()

    private val _login = MutableStateFlow(LoginUiState())
    val login: StateFlow<LoginUiState> = _login.asStateFlow()

    private val _libraries = MutableStateFlow(LibrariesUiState())
    val libraries: StateFlow<LibrariesUiState> = _libraries.asStateFlow()

    /** Credentials that are verified but not yet committed, pending a sync folder. */
    private var pendingAccount: Account? = null

    /**
     * The three inputs the list is derived from. They change independently -- the API list on
     * refresh, the tracked set when the user enables a library, the status on every progress
     * tick -- so each is kept separately and merged in one place. Deriving the list in more than
     * one place is how a refresh ends up wiping the sync state off every row.
     */
    private val remoteLibraries = MutableStateFlow<List<LibraryUi>>(emptyList())

    init {
        viewModelScope.launch {
            val account = container.accountStore.current()
            _destination.value = if (account == null) Destination.Login else Destination.Libraries
            if (account != null) refreshLibraries()
        }
        // Sync state lives in the database and the engine, not in this view model, so the list
        // keeps reflecting reality while the service works in the background.
        viewModelScope.launch {
            combine(
                remoteLibraries,
                container.database.syncedRepos().observeAll(),
                container.syncEngine.status,
            ) { remote, tracked, status -> merge(remote, tracked, status) }
                .collect { merged -> _libraries.update { it.copy(libraries = merged) } }
        }
    }

    private fun merge(
        remote: List<LibraryUi>,
        tracked: List<SyncedRepoEntity>,
        status: SyncStatus,
    ): List<LibraryUi> {
        val byId = tracked.associateBy { it.repoId }
        return remote.map { library ->
            val entity = byId[library.id] ?: return@map library
            val progress = status.activeRepos[library.id]
            when {
                progress != null -> library.copy(
                    state = SyncState.Syncing,
                    localPath = entity.localPath,
                    progress = progress.fraction,
                )
                entity.status == SyncedRepoEntity.STATUS_ERROR -> library.copy(
                    state = SyncState.Error,
                    localPath = entity.localPath,
                    errorMessage = entity.errorMessage,
                )
                else -> library.copy(state = SyncState.Synced, localPath = entity.localPath)
            }
        }
    }

    fun startSyncing(library: LibraryUi, password: String? = null) {
        viewModelScope.launch {
            val account = container.accountStore.current() ?: return@launch
            try {
                container.log.info("Enabling ${library.name}, password supplied: ${password != null}")
                container.syncEngine.enable(account, library.id, library.name, library.isWritable, password)
                onSyncingStarted()
            } catch (failure: Exception) {
                container.log.warn("Could not enable ${library.name}", failure)
                _libraries.update { it.copy(errorMessage = failure.message ?: "Could not start syncing") }
            }
        }
    }

    fun dismissError() = _libraries.update { it.copy(errorMessage = null) }

    fun stopSyncing(library: LibraryUi) {
        viewModelScope.launch { container.syncEngine.disable(library.id) }
    }

    val settings: StateFlow<SyncPreferences> = container.settings.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncPreferences())

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    fun openSettings() {
        viewModelScope.launch {
            _account.value = container.accountStore.current()
            refreshLogSize()
            _destination.value = Destination.Settings
        }
    }

    fun closeSettings() {
        _destination.value = Destination.Libraries
    }

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch { container.settings.setWifiOnly(value) }
    }

    fun setInsecureTls(value: Boolean) {
        viewModelScope.launch {
            container.accountStore.updateInsecureTls(value)
            // _account is only filled in openSettings, so without this the switch would show the
            // old value until the screen was opened again.
            _account.update { it?.copy(allowInsecureTls = value) }
        }
    }

    fun setPollInterval(seconds: Long) {
        viewModelScope.launch { container.settings.setPollInterval(seconds) }
    }

    private val _logSize = MutableStateFlow(0L)
    val logSize: StateFlow<Long> = _logSize.asStateFlow()

    fun refreshLogSize() {
        viewModelScope.launch { _logSize.value = container.log.read().length.toLong() }
    }

    fun clearLog() {
        viewModelScope.launch {
            container.log.clear()
            _logSize.value = 0
        }
    }

    fun onServerUrlChanged(value: String) =
        _login.update { it.withTypedServerUrl(value).copy(errorMessage = null) }

    fun onUseHttpsChanged(value: Boolean) = _login.update {
        // Dropping the exemption on the way to http stops a later switch back to https from
        // silently restoring a setting that was not on screen while it changed.
        it.copy(useHttps = value, allowInsecureTls = it.allowInsecureTls && value, errorMessage = null)
    }

    fun onAllowInsecureTlsChanged(value: Boolean) =
        _login.update { it.copy(allowInsecureTls = value, errorMessage = null) }

    fun onEmailChanged(value: String) = _login.update { it.copy(email = value, errorMessage = null) }
    fun onPasswordChanged(value: String) = _login.update { it.copy(password = value, errorMessage = null) }
    fun onOtpChanged(value: String) = _login.update { it.copy(otp = value, errorMessage = null) }

    fun signIn() {
        val form = _login.value
        if (form.isSubmitting) return
        _login.update { it.copy(isSubmitting = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val serverUrl = form.fullServerUrl
                // Only the parse is guarded. SeafileApi turns the address into an HttpUrl in its
                // constructor and rejects anything that is not one -- a space, a typo, a stray
                // character -- and okhttp's own message reads like a parser error. Widening this
                // to the whole call would also swallow the device-id require() inside login(),
                // which is a bug in this app rather than something the user typed.
                val api = try {
                    container.seafileApi(serverUrl, form.skipCertificateCheck)
                } catch (malformed: IllegalArgumentException) {
                    _login.update {
                        it.copy(isSubmitting = false, errorMessage = "Check the server address")
                    }
                    return@launch
                }
                val token = api.login(
                    username = form.email.trim(),
                    password = form.password,
                    deviceId = container.deviceId,
                    deviceName = DeviceIdentity.deviceName(),
                    clientVersion = container.appVersion,
                    platformVersion = DeviceIdentity.platformVersion(),
                    otp = form.otp.takeIf { form.needsOtp && it.isNotBlank() },
                )
                pendingAccount = Account(
                    serverUrl = serverUrl,
                    email = form.email.trim(),
                    token = token,
                    syncRoot = "",
                    deviceId = container.deviceId,
                    allowInsecureTls = form.skipCertificateCheck,
                )
                _login.update { it.copy(isSubmitting = false, password = "") }
                _destination.value = Destination.ChooseSyncFolder
            } catch (twoFactor: SeafileException.TwoFactorRequired) {
                // Not an error the user caused; just ask for the code and keep what they typed.
                _login.update { it.copy(isSubmitting = false, needsOtp = true, errorMessage = null) }
            } catch (failure: IOException) {
                _login.update { it.copy(isSubmitting = false, errorMessage = failure.readableMessage()) }
            }
        }
    }

    fun onSyncFolderChosen(path: String) {
        val account = pendingAccount?.copy(syncRoot = path) ?: return
        viewModelScope.launch {
            container.accountStore.save(account)
            pendingAccount = null
            _login.value = LoginUiState()
            _destination.value = Destination.Libraries
            refreshLibraries()
        }
    }

    fun refreshLibraries() {
        viewModelScope.launch {
            val account = container.accountStore.current() ?: run {
                _destination.value = Destination.Login
                return@launch
            }
            _libraries.update {
                it.copy(isRefreshing = true, errorMessage = null,
                    accountEmail = account.email, syncRoot = account.syncRoot)
            }
            try {
                val repos = container.seafileApi(account.serverUrl, account.allowInsecureTls)
                    .repos(account.token)
                remoteLibraries.value = repos.map { repo ->
                    LibraryUi(
                        id = repo.id,
                        name = repo.name,
                        sizeBytes = repo.size,
                        modifiedAtSeconds = repo.mtime,
                        // Merged with the tracked set and live progress by [merge].
                        state = SyncState.NotSynced,
                        isEncrypted = repo.encrypted,
                        isWritable = repo.isWritable,
                    )
                }
                _libraries.update { it.copy(isRefreshing = false) }
            } catch (wiped: SeafileException.DeviceWiped) {
                // The account is gone server-side; keeping local state would be pretending.
                container.log.warn("Server reports this device was wiped; clearing the account")
                container.accountStore.clear()
                _destination.value = Destination.Login
            } catch (rejected: SeafileException.TokenRejected) {
                // Logged because the app otherwise returns to the sign-in screen with no
                // explanation, which is indistinguishable from having been signed out on purpose.
                // Seafile keeps one token per (user, platform, device id), so signing in again
                // from anywhere with the same device id invalidates this one.
                container.log.warn("Token rejected by the server; signing out")
                container.accountStore.clear()
                _destination.value = Destination.Login
            } catch (failure: IOException) {
                _libraries.update {
                    it.copy(isRefreshing = false, errorMessage = failure.readableMessage())
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            container.log.info("Signed out at the user's request")
            container.accountStore.clear()
            remoteLibraries.value = emptyList()
            _libraries.value = LibrariesUiState()
            _destination.value = Destination.Login
        }
    }

    private fun IOException.readableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Could not reach the server"

    class Factory(
        private val container: AppContainer,
        private val onSyncingStarted: () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(container, onSyncingStarted) as T
    }
}
