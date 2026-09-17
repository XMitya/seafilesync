package com.xmitya.seafilesync.sync

import com.xmitya.seafilesync.data.db.FileIndexEntity

/** One thing that has to happen to bring the local tree in line with the server. */
sealed interface SyncOperation {
    val path: String

    data class CreateDirectory(
        override val path: String,
    ) : SyncOperation

    data class DownloadFile(
        override val path: String,
        val remote: RemoteFile,
    ) : SyncOperation

    /** The server deleted it and the local copy is untouched, so removing it loses nothing. */
    data class DeleteFile(
        override val path: String,
    ) : SyncOperation

    /**
     * Both sides changed the same file. The server's version takes the path and the local one is
     * moved aside under a conflict name, so neither edit is lost and the user can see both.
     */
    data class ResolveConflict(
        override val path: String,
        val remote: RemoteFile,
        val keepLocalAs: String,
    ) : SyncOperation

    /**
     * The local file already holds exactly the server's content, so nothing transfers; it just
     * needs recording in the index as synced.
     */
    data class AdoptLocal(
        override val path: String,
        val remote: RemoteFile,
    ) : SyncOperation

    /** Something that cannot be resolved automatically and is reported instead. */
    data class ConflictSkipped(
        override val path: String,
        val reason: String,
    ) : SyncOperation
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
        /** Used to name conflict copies; matches what the server writes for merges. */
        modifier: String = "",
        nowMillis: Long = System.currentTimeMillis(),
        /**
         * Whether the file on disk already holds exactly the server's content. Checked only when
         * there is no index entry to go by, because it costs a hash of the file.
         */
        contentMatches: (path: String, remote: RemoteFile) -> Boolean = { _, _ -> false },
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

                // A file exists locally with no record of this app having written it. That is
                // the normal state after a reinstall, or when a library is re-added to a folder
                // that already holds it, so identical content is adopted rather than duplicated.
                // Only genuinely different content is a conflict.
                recorded == null && contentMatches(path, file) ->
                    operations += SyncOperation.AdoptLocal(path, file)

                recorded == null -> {
                    operations += SyncOperation.ResolveConflict(
                        path,
                        file,
                        ConflictNaming.conflictPath(path, modifier, nowMillis),
                    )
                    bytes += file.sizeBytes
                }

                // Already at the server's version and untouched since.
                recorded.fileId == file.fileId && local == LocalState.Unchanged -> Unit

                // Deleted locally, but the index says it was synced, so fetch it back.
                local == LocalState.Missing -> {
                    operations += SyncOperation.DownloadFile(path, file)
                    bytes += file.sizeBytes
                }

                // Changed on both sides. The server's version takes the path, matching what the
                // server itself does when it merges commits, and the local edit is preserved
                // beside it rather than discarded.
                local == LocalState.Modified && recorded.fileId != file.fileId -> {
                    operations += SyncOperation.ResolveConflict(
                        path,
                        file,
                        ConflictNaming.conflictPath(path, modifier, nowMillis),
                    )
                    bytes += file.sizeBytes
                }

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
                // Deleted remotely but edited locally. The edit is kept and the upload pass will
                // put it back on the server, which is the safer of the two possible surprises.
                LocalState.Modified -> operations += SyncOperation.ConflictSkipped(
                    recorded.path,
                    "deleted on the server but changed locally, keeping the local copy",
                )
            }
        }

        return SyncPlan(operations, bytes)
    }
}
