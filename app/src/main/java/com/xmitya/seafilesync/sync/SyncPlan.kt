package com.xmitya.seafilesync.sync

import com.xmitya.seafilesync.data.db.FileIndexEntity

/** One thing that has to happen to bring the local tree in line with the server. */
sealed interface SyncOperation {
    val path: String

    data class CreateDirectory(override val path: String) : SyncOperation

    data class DownloadFile(override val path: String, val remote: RemoteFile) : SyncOperation

    /** The server deleted it and the local copy is untouched, so removing it loses nothing. */
    data class DeleteFile(override val path: String) : SyncOperation

    /**
     * The server changed a file the user also changed locally. Download-only sync refuses to
     * overwrite, so the file is skipped and reported; two-way sync resolves it properly.
     */
    data class ConflictSkipped(override val path: String, val reason: String) : SyncOperation
}

data class SyncPlan(
    val operations: List<SyncOperation>,
    val bytesToDownload: Long,
) {
    val isEmpty: Boolean get() = operations.isEmpty()
}

/** How a local file compares to what the last sync wrote. */
enum class LocalState { Missing, Unchanged, Modified }

/**
 * Works out what to do, given the server's tree, what the last sync recorded, and what is
 * actually on disk now.
 *
 * The recorded index is what makes the difference between "the server deleted this" and "the user
 * created this" visible at all: without it both look identical, as a path present on one side and
 * absent on the other.
 */
object SyncPlanner {

    fun plan(
        remote: RemoteSnapshot,
        index: List<FileIndexEntity>,
        localState: (path: String) -> LocalState,
    ): SyncPlan {
        val indexed = index.associateBy { it.path }
        val operations = mutableListOf<SyncOperation>()
        var bytes = 0L

        remote.directories.sorted().forEach { operations += SyncOperation.CreateDirectory(it) }

        for ((path, file) in remote.files) {
            val recorded = indexed[path]
            val local = localState(path)

            when {
                // Nothing local and nothing recorded: a plain download.
                recorded == null && local == LocalState.Missing -> {
                    operations += SyncOperation.DownloadFile(path, file)
                    bytes += file.sizeBytes
                }

                // A file appeared locally that this app never wrote. Overwriting it would
                // destroy data the user put there.
                recorded == null -> operations += SyncOperation.ConflictSkipped(
                    path, "exists locally but was never synced"
                )

                // Already at the server's version and untouched since.
                recorded.fileId == file.fileId && local == LocalState.Unchanged -> Unit

                // Deleted locally, but the index says it was synced, so fetch it back.
                local == LocalState.Missing -> {
                    operations += SyncOperation.DownloadFile(path, file)
                    bytes += file.sizeBytes
                }

                // Changed on both sides. Download-only cannot merge, so the local edit wins by
                // being left alone and the divergence is surfaced.
                local == LocalState.Modified && recorded.fileId != file.fileId ->
                    operations += SyncOperation.ConflictSkipped(path, "changed locally and on the server")

                // Changed locally only: nothing to download, the upload side will deal with it.
                local == LocalState.Modified -> Unit

                // Changed on the server only.
                else -> {
                    operations += SyncOperation.DownloadFile(path, file)
                    bytes += file.sizeBytes
                }
            }
        }

        // Present in the index but gone from the server: deleted remotely. Only removed when the
        // local copy still matches what was synced, so local edits are never silently discarded.
        for (recorded in index) {
            if (remote.files.containsKey(recorded.path)) continue
            when (localState(recorded.path)) {
                LocalState.Unchanged -> operations += SyncOperation.DeleteFile(recorded.path)
                LocalState.Missing -> operations += SyncOperation.DeleteFile(recorded.path)
                LocalState.Modified -> operations += SyncOperation.ConflictSkipped(
                    recorded.path, "deleted on the server but changed locally"
                )
            }
        }

        return SyncPlan(operations, bytes)
    }
}
