package com.xmitya.seafilesync.sync

import com.xmitya.seafilesync.data.api.SeafHttpApi
import com.xmitya.seafilesync.data.crypto.LibraryCipher
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
class Downloader(
    private val api: SeafHttpApi,
) {

    fun interface ProgressSink {
        fun onBytes(count: Long)
    }

    suspend fun download(
        token: String,
        repoId: String,
        remote: RemoteFile,
        target: File,
        /** Set for encrypted libraries; blocks arrive as ciphertext and are decrypted here. */
        cipher: LibraryCipher? = null,
        progress: ProgressSink = ProgressSink { },
    ) {
        target.parentFile?.mkdirs()

        // Same directory as the target so the move is a rename rather than a copy across
        // filesystems, which would not be atomic.
        val temporary = File(target.parentFile, ".${target.name}.seafile-part")
        try {
            temporary.outputStream().buffered().use { out ->
                for (blockId in remote.blockIds) {
                    val stored = api.downloadBlock(token, repoId, blockId) { it.readBytes() }
                    // The id is the hash of what the server stores, so integrity is checked
                    // against the ciphertext, before any attempt to decrypt it.
                    val actual = ObjectId.ofBytes(stored)
                    if (actual != blockId) {
                        throw IOException(
                            "Block $blockId for ${remote.path} hashed to $actual; transfer was corrupted",
                        )
                    }
                    progress.onBytes(stored.size.toLong())
                    out.write(cipher?.decrypt(stored) ?: stored)
                }
            }

            // The recorded size is of the plaintext, which is what has just been written.
            if (temporary.length() != remote.sizeBytes) {
                throw IOException(
                    "Reassembled ${remote.path} is ${temporary.length()} bytes, expected ${remote.sizeBytes}",
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
