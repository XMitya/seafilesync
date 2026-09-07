package com.xmitya.seafilesync.sync

import com.xmitya.seafilesync.data.api.SeafHttpApi
import com.xmitya.seafilesync.data.fs.ObjectId
import java.io.File
import java.io.IOException

/**
 * Reassembles files from blocks.
 *
 * Two properties matter more than speed here:
 *
 *  - A file is never visible half-written. Blocks land in a temporary file next to the target and
 *    the result is moved into place, so a process killed mid-download leaves the previous version
 *    intact rather than a truncated one.
 *  - Every block is verified against its id. The id is the SHA-1 of the content, so a truncated
 *    or corrupted transfer is caught here instead of surfacing later as a file that silently
 *    differs from the server.
 */
class Downloader(private val api: SeafHttpApi) {

    fun interface ProgressSink {
        fun onBytes(count: Long)
    }

    suspend fun download(
        token: String,
        repoId: String,
        remote: RemoteFile,
        target: File,
        progress: ProgressSink = ProgressSink { },
    ) {
        target.parentFile?.mkdirs()

        // Same directory as the target so the move is a rename rather than a copy across
        // filesystems, which would not be atomic.
        val temporary = File(target.parentFile, ".${target.name}.seafile-part")
        try {
            temporary.outputStream().buffered().use { out ->
                for (blockId in remote.blockIds) {
                    val bytes = api.downloadBlock(token, repoId, blockId) { it.readBytes() }
                    val actual = ObjectId.ofBytes(bytes)
                    if (actual != blockId) {
                        throw IOException(
                            "Block $blockId for ${remote.path} hashed to $actual; transfer was corrupted"
                        )
                    }
                    out.write(bytes)
                    progress.onBytes(bytes.size.toLong())
                }
            }

            if (temporary.length() != remote.sizeBytes) {
                throw IOException(
                    "Reassembled ${remote.path} is ${temporary.length()} bytes, expected ${remote.sizeBytes}"
                )
            }

            if (!temporary.renameTo(target)) {
                // renameTo refuses to replace on some filesystems, so fall back to an explicit
                // delete. Still safe: by this point the replacement is complete and verified.
                if (!target.delete() || !temporary.renameTo(target)) {
                    throw IOException("Could not move ${temporary.path} into place")
                }
            }

            // Best effort: the server's mtime is what makes a later scan able to tell "this is
            // what we downloaded" from "the user edited it", but not every filesystem allows it.
            target.setLastModified(remote.modifiedSeconds * 1000)
        } finally {
            temporary.delete()
        }
    }
}
