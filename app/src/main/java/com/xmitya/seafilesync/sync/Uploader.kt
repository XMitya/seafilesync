package com.xmitya.seafilesync.sync

import com.xmitya.seafilesync.data.api.ObjectPack
import com.xmitya.seafilesync.data.api.SeafHttpApi
import com.xmitya.seafilesync.data.api.SeafileException
import com.xmitya.seafilesync.data.api.model.CommitDto
import com.xmitya.seafilesync.data.fs.EMPTY_OBJECT_ID
import com.xmitya.seafilesync.data.fs.ObjectId
import com.xmitya.seafilesync.data.fs.SeafJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Publishes a local tree as a new commit.
 *
 * The order is forced by what the server will accept: blocks first, then the fs objects that
 * reference them, then the commit that references the root, then the head pointer. Publishing the
 * head before its contents exist gives other clients a commit they cannot read.
 */
class Uploader(
    private val api: SeafHttpApi,
) {

    data class Result(
        val commitId: String,
        val blocksUploaded: Int,
        val objectsUploaded: Int,
    )

    fun interface ProgressSink {
        fun onBytes(count: Long)
    }

    suspend fun push(
        token: String,
        repoId: String,
        repoName: String,
        tree: LocalTree,
        parentCommitId: String?,
        creatorName: String,
        deviceName: String,
        clientVersion: String,
        description: String,
        now: Long,
        progress: ProgressSink = ProgressSink { },
    ): Result {
        // Ask before transferring rather than after: a rejected push that already moved a
        // gigabyte is a bad way to learn the account is full.
        val newBytes = tree.blocks.values.sumOf { it.length.toLong() }
        api.quotaCheck(token, repoId, newBytes)

        val blocksUploaded = uploadBlocks(token, repoId, tree, progress)
        val objectsUploaded = uploadObjects(token, repoId, tree)

        val commit = CommitDto(
            commitId = "",
            rootId = tree.rootId,
            repoId = repoId,
            creatorName = creatorName,
            creator = EMPTY_OBJECT_ID,
            description = description,
            ctime = now / 1000,
            parentId = parentCommitId,
            repoName = repoName,
            repoDesc = repoName,
            version = 1,
            deviceName = deviceName,
            clientVersion = clientVersion,
        )
        // Unlike fs objects, a commit's id is assigned by its creator rather than derived from
        // its content, so it just has to be unique and well formed.
        val commitId = commitIdFor(commit)
        val stored = commit.copy(commitId = commitId)

        api.putCommit(token, repoId, stored)

        try {
            api.updateHead(token, repoId, commitId)
        } catch (missing: SeafileException.BlocksMissing) {
            // The server checked and found blocks it does not have. Recoverable: send them and
            // publish again rather than failing the whole sync.
            retryMissingBlocks(token, repoId, tree, missing, progress)
            api.updateHead(token, repoId, commitId)
        }

        return Result(commitId, blocksUploaded, objectsUploaded)
    }

    private suspend fun uploadBlocks(
        token: String,
        repoId: String,
        tree: LocalTree,
        progress: ProgressSink,
    ): Int {
        var uploaded = 0
        // check-blocks is where deduplication happens: content the server already holds, whether
        // from this device or another, is never sent again.
        tree.blocks.keys.chunked(CHECK_BATCH).forEach { batch ->
            val missing = api.missingBlocks(token, repoId, batch)
            for (blockId in missing) {
                val block = tree.blocks[blockId] ?: continue
                val bytes = block.read()
                if (ObjectId.ofBytes(bytes) != blockId) {
                    throw IOException("${block.file.name} changed while it was being uploaded")
                }
                api.uploadBlock(token, repoId, blockId, bytes.toRequestBody(OCTET_STREAM))
                progress.onBytes(bytes.size.toLong())
                uploaded++
            }
        }
        return uploaded
    }

    private suspend fun uploadObjects(token: String, repoId: String, tree: LocalTree): Int {
        var uploaded = 0
        tree.objects.keys.chunked(CHECK_BATCH).forEach { batch ->
            val missing = api.missingFs(token, repoId, batch)
            missing
                .mapNotNull { id ->
                    tree.objects[id]?.let { obj ->
                        ObjectPack.Entry(id, SeafJson.canonicalize(obj.toJson()).toByteArray(Charsets.UTF_8))
                    }
                }.chunked(SEND_BATCH)
                .forEach { entries ->
                    api.sendFs(token, repoId, entries)
                    uploaded += entries.size
                }
        }
        return uploaded
    }

    private suspend fun retryMissingBlocks(
        token: String,
        repoId: String,
        tree: LocalTree,
        failure: SeafileException.BlocksMissing,
        progress: ProgressSink,
    ) {
        val ids = OBJECT_ID
            .findAll(failure.body)
            .map { it.value }
            .toList()
            .ifEmpty { tree.blocks.keys.toList() }
        for (blockId in api.missingBlocks(token, repoId, ids)) {
            val block = tree.blocks[blockId] ?: continue
            val bytes = block.read()
            api.uploadBlock(token, repoId, blockId, bytes.toRequestBody(OCTET_STREAM))
            progress.onBytes(bytes.size.toLong())
        }
    }

    private fun commitIdFor(commit: CommitDto): String =
        ObjectId.ofBytes(
            "${commit.repoId}:${commit.rootId}:${commit.parentId}:${commit.ctime}:${commit.description}"
                .toByteArray(Charsets.UTF_8),
        )

    private companion object {
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val OBJECT_ID = Regex("[0-9a-f]{40}")

        /** Well inside the 1 MiB response cap while keeping round trips down. */
        const val CHECK_BATCH = 500

        /** recv-fs bodies are built in memory, so they are kept modest. */
        const val SEND_BATCH = 100
    }
}

/**
 * Commit descriptions in the style the desktop client writes, because they are what the web UI
 * shows in a library's history.
 */
object CommitDescription {

    fun describe(added: List<String>, modified: List<String>, removed: List<String>): String {
        val parts = buildList {
            summarize("Added", added)?.let { add(it) }
            summarize("Modified", modified)?.let { add(it) }
            summarize("Deleted", removed)?.let { add(it) }
        }
        return parts.joinToString("\n").ifEmpty { "Updated files" }
    }

    private fun summarize(verb: String, paths: List<String>): String? = when {
        paths.isEmpty() -> null
        paths.size == 1 -> "$verb \"${paths.single().substringAfterLast('/')}\""
        else -> "$verb \"${paths.first().substringAfterLast('/')}\" and ${paths.size - 1} more files"
    }
}
