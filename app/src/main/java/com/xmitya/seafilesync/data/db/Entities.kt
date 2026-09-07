package com.xmitya.seafilesync.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A library the user chose to sync.
 *
 * [lastSyncedCommitId] is the anchor for everything: it is passed to the server as `client-head`
 * so only the delta comes back, and it is the base for deciding what changed locally. It is
 * advanced only once the working tree actually matches that commit, so an interrupted sync
 * resumes rather than restarting.
 */
@Entity(tableName = "synced_repo")
data class SyncedRepoEntity(
    @PrimaryKey val repoId: String,
    val name: String,
    val localPath: String,
    val lastSyncedCommitId: String? = null,
    /** Per-library sync token for /seafhttp. Re-fetched when the server rejects it. */
    val syncToken: String? = null,
    val status: String = STATUS_IDLE,
    val errorMessage: String? = null,
    val isWritable: Boolean = true,
    val lastSyncedAtMillis: Long = 0,
    /** 0 for a plain library. */
    val encVersion: Int = 0,
    /** Wrapped library key, as published by the server. Useless without the password. */
    val randomKey: String = "",
    /** Per-library salt, present from enc_version 3 on. */
    val encSalt: String = "",
    /**
     * The library password, encrypted with the same Keystore key as the account token. Kept
     * because syncing runs unattended: a background service cannot prompt for it.
     */
    val encryptedPassword: String? = null,
) {
    val isEncrypted: Boolean get() = encVersion > 0

    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_SYNCING = "syncing"
        const val STATUS_PAUSED = "paused"
        const val STATUS_ERROR = "error"
    }
}

/**
 * What the last successful sync put on disk, per file.
 *
 * Without this there is no way to tell "the server deleted it" from "the user created it", since
 * both look like a difference between the server tree and the local tree.
 *
 * [localSizeBytes] and [localModifiedMillis] record the file as this app wrote it, so a later
 * scan can spot user edits cheaply without rehashing every file.
 */
@Entity(
    tableName = "file_index",
    primaryKeys = ["repoId", "path"],
    indices = [Index("repoId")],
)
data class FileIndexEntity(
    val repoId: String,
    /** Library-relative, always starting with "/". */
    val path: String,
    /** Id of the fs object, i.e. the content hash of the file at the last sync. */
    val fileId: String,
    val sizeBytes: Long,
    /** mtime as the server records it, in seconds. */
    val serverModifiedSeconds: Long,
    val localSizeBytes: Long,
    val localModifiedMillis: Long,
    val blockIds: List<String>,
)

/**
 * A block that still has to be fetched. Kept in the database rather than in memory so a killed
 * process resumes where it stopped: the server does not honour Range, so the unit of resumption
 * is a whole block and there is no point tracking anything finer.
 */
@Entity(
    tableName = "pending_block",
    primaryKeys = ["repoId", "path", "blockIndex"],
    indices = [Index("repoId")],
)
data class PendingBlockEntity(
    val repoId: String,
    val path: String,
    val blockIndex: Int,
    val blockId: String,
)
