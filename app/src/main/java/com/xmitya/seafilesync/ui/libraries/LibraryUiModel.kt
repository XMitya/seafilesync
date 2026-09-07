package com.xmitya.seafilesync.ui.libraries

/**
 * How a library is currently being synchronised. The list shows one icon per state, which is
 * the whole of requirements 4 to 6.
 */
enum class SyncState {
    /** Not selected for syncing. No icon at all, so synced libraries stand out. */
    NotSynced,

    /** Selected and up to date. */
    Synced,

    /** Transferring right now. */
    Syncing,

    /** Selected, but held back deliberately: no network, metered connection, battery saver. */
    Paused,

    /** The last attempt failed and will not retry on its own. */
    Error,
}

/**
 * A library as the list renders it. Deliberately flat and free of API types so the screen can be
 * previewed and tested without a server.
 */
data class LibraryUi(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAtSeconds: Long,
    val state: SyncState,
    val isEncrypted: Boolean = false,
    val isWritable: Boolean = true,
    /** 0..1 while [state] is [SyncState.Syncing], null otherwise. */
    val progress: Float? = null,
    /** Populated only in [SyncState.Error]; shown when the user taps the error icon. */
    val errorMessage: String? = null,
    /** Where the library is mirrored, once syncing has been enabled. */
    val localPath: String? = null,
) {
    /** Encrypted libraries need a password before anything can be transferred. */
    val needsPassword: Boolean get() = isEncrypted && state == SyncState.NotSynced

    val canSync: Boolean get() = state == SyncState.NotSynced

    val canStopSyncing: Boolean get() = state != SyncState.NotSynced

    val canRetry: Boolean get() = state == SyncState.Error
}

data class LibrariesUiState(
    val libraries: List<LibraryUi> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val syncRoot: String = "",
    val accountEmail: String = "",
) {
    val transferringCount: Int get() = libraries.count { it.state == SyncState.Syncing }
    val failedCount: Int get() = libraries.count { it.state == SyncState.Error }
}
