package com.xmitya.seafilesync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xmitya.seafilesync.app.AppContainer
import com.xmitya.seafilesync.app.DeviceIdentity
import com.xmitya.seafilesync.data.api.SeafileException
import com.xmitya.seafilesync.data.prefs.Account
import com.xmitya.seafilesync.ui.libraries.LibrariesUiState
import com.xmitya.seafilesync.ui.libraries.LibraryUi
import com.xmitya.seafilesync.ui.libraries.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
}

data class LoginUiState(
    val serverUrl: String = "",
    val email: String = "",
    val password: String = "",
    val otp: String = "",
    /** Shown only after the server has asked for a code, so the field is not there by default. */
    val needsOtp: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val _destination = MutableStateFlow<Destination>(Destination.Loading)
    val destination: StateFlow<Destination> = _destination.asStateFlow()

    private val _login = MutableStateFlow(LoginUiState())
    val login: StateFlow<LoginUiState> = _login.asStateFlow()

    private val _libraries = MutableStateFlow(LibrariesUiState())
    val libraries: StateFlow<LibrariesUiState> = _libraries.asStateFlow()

    /** Credentials that are verified but not yet committed, pending a sync folder. */
    private var pendingAccount: Account? = null

    init {
        viewModelScope.launch {
            val account = container.accountStore.current()
            _destination.value = if (account == null) Destination.Login else Destination.Libraries
            if (account != null) refreshLibraries()
        }
    }

    fun onServerUrlChanged(value: String) = _login.update { it.copy(serverUrl = value, errorMessage = null) }
    fun onEmailChanged(value: String) = _login.update { it.copy(email = value, errorMessage = null) }
    fun onPasswordChanged(value: String) = _login.update { it.copy(password = value, errorMessage = null) }
    fun onOtpChanged(value: String) = _login.update { it.copy(otp = value, errorMessage = null) }

    fun signIn() {
        val form = _login.value
        if (form.isSubmitting) return
        _login.update { it.copy(isSubmitting = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val api = container.seafileApi(form.serverUrl)
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
                    serverUrl = form.serverUrl.trim(),
                    email = form.email.trim(),
                    token = token,
                    syncRoot = "",
                    deviceId = container.deviceId,
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
                val repos = container.seafileApi(account.serverUrl).repos(account.token)
                _libraries.update { state ->
                    state.copy(
                        isRefreshing = false,
                        libraries = repos.map { repo ->
                            LibraryUi(
                                id = repo.id,
                                name = repo.name,
                                sizeBytes = repo.size,
                                modifiedAtSeconds = repo.mtime,
                                // Nothing syncs yet; the engine fills this in from M3 on.
                                state = SyncState.NotSynced,
                                isEncrypted = repo.encrypted,
                                isWritable = repo.isWritable,
                            )
                        },
                    )
                }
            } catch (wiped: SeafileException.DeviceWiped) {
                // The account is gone server-side; keeping local state would be pretending.
                container.accountStore.clear()
                _destination.value = Destination.Login
            } catch (rejected: SeafileException.TokenRejected) {
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
            container.accountStore.clear()
            _libraries.value = LibrariesUiState()
            _destination.value = Destination.Login
        }
    }

    private fun IOException.readableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Could not reach the server"

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
    }
}
