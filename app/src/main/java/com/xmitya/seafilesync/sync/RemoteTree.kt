package com.xmitya.seafilesync.sync

import com.xmitya.seafilesync.data.api.ObjectPack
import com.xmitya.seafilesync.data.api.SeafHttpApi
import com.xmitya.seafilesync.data.fs.EMPTY_OBJECT_ID
import com.xmitya.seafilesync.data.fs.FsObject
import com.xmitya.seafilesync.data.fs.SeafDir
import com.xmitya.seafilesync.data.fs.SeafFile

/** One file in the server's tree, flattened to a library-relative path. */
data class RemoteFile(
    val path: String,
    val fileId: String,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val blockIds: List<String>,
)

/** The server's view of a library at one commit. */
data class RemoteSnapshot(
    val commitId: String,
    val rootId: String,
    val files: Map<String, RemoteFile>,
    val directories: Set<String>,
)

/**
 * Walks a library's Merkle tree and flattens it into paths.
 *
 * Objects are fetched by id through `pack-fs` in batches. The server caps a response at 1 MiB
 * whatever was asked for, so a batch can come back short and the remainder has to be re-requested
 * rather than assumed missing.
 */
class RemoteTreeReader(
    private val api: SeafHttpApi,
    private val batchSize: Int = DEFAULT_BATCH,
) {

    suspend fun read(token: String, repoId: String, commitId: String, rootId: String): RemoteSnapshot {
        val files = mutableMapOf<String, RemoteFile>()
        val directories = mutableSetOf<String>()
        val objects = mutableMapOf<String, FsObject>()

        // A freshly created library has an all-zero root: the id that means "empty directory"
        // rather than an object that exists. Asking pack-fs for it answers 500, so an empty
        // library would fail to sync at all -- and an empty library is exactly what a user has
        // just after creating one.
        if (rootId == EMPTY_OBJECT_ID) {
            return RemoteSnapshot(commitId, rootId, emptyMap(), setOf("/"))
        }

        // Breadth-first so each level can be fetched in one batch rather than one object at a
        // time, which matters on trees that are wide rather than deep.
        var frontier = listOf(rootId to "")
        while (frontier.isNotEmpty()) {
            val needed = frontier
                .map { it.first }
                .filterNot { it == EMPTY_OBJECT_ID || objects.containsKey(it) }
                .distinct()
            fetch(token, repoId, needed).forEach { (id, obj) -> objects[id] = obj }

            val next = mutableListOf<Pair<String, String>>()
            for ((id, prefix) in frontier) {
                val dir = objects[id] as? SeafDir ?: continue
                directories += prefix.ifEmpty { "/" }
                for (entry in dir.entries) {
                    val path = "$prefix/${entry.name}"
                    if (entry.isDirectory) {
                        // An empty subdirectory carries the same all-zero id; it still needs
                        // creating on disk, but there is no object to fetch for it.
                        if (entry.id == EMPTY_OBJECT_ID) directories += path else next += entry.id to path
                    } else {
                        val file = objects[entry.id] as? SeafFile
                        files[path] = RemoteFile(
                            path = path,
                            fileId = entry.id,
                            sizeBytes = entry.size,
                            modifiedSeconds = entry.mtime,
                            // Filled in below for entries whose object was not in this batch.
                            blockIds = file?.blockIds ?: emptyList(),
                        )
                    }
                }
            }

            // File objects are only needed for their block lists, so they are fetched after the
            // directory level that referenced them rather than as part of the walk.
            val missingBlocks = files.values
                .filter { it.blockIds.isEmpty() && it.sizeBytes > 0 && it.fileId != EMPTY_OBJECT_ID }
            if (missingBlocks.isNotEmpty()) {
                fetch(token, repoId, missingBlocks.map { it.fileId }.distinct()).forEach { (id, obj) ->
                    val seafFile = obj as? SeafFile ?: return@forEach
                    objects[id] = obj
                    missingBlocks.filter { it.fileId == id }.forEach { stale ->
                        files[stale.path] = stale.copy(blockIds = seafFile.blockIds)
                    }
                }
            }

            frontier = next
        }

        return RemoteSnapshot(commitId, rootId, files, directories)
    }

    private suspend fun fetch(token: String, repoId: String, ids: List<String>): Map<String, FsObject> {
        val parsed = mutableMapOf<String, FsObject>()
        var remaining = ids
        while (remaining.isNotEmpty()) {
            val batch = remaining.take(batchSize)
            val entries = api.packFs(token, repoId, batch)
            if (entries.isEmpty()) {
                // Nothing came back for a non-empty request: retrying would spin forever, and
                // continuing would silently drop part of the tree.
                error("Server returned no fs objects for ${batch.size} requested ids")
            }
            entries.forEach { entry -> parsed[entry.id] = FsObject.parse(entry.json) }
            // A capped response returns fewer objects than requested, so drop only what arrived.
            val received = entries.map(ObjectPack.Entry::id).toSet()
            remaining = remaining.filterNot { it in received }
        }
        return parsed
    }

    private companion object {
        /**
         * Objects are a few hundred bytes each, so this stays well inside the server's 1 MiB
         * response cap while keeping round trips down.
         */
        const val DEFAULT_BATCH = 500
    }
}
