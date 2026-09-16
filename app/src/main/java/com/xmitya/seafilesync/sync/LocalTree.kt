package com.xmitya.seafilesync.sync

import com.xmitya.seafilesync.data.crypto.LibraryCipher
import com.xmitya.seafilesync.data.fs.CdcChunker
import com.xmitya.seafilesync.data.fs.FsObject
import com.xmitya.seafilesync.data.fs.ObjectId
import com.xmitya.seafilesync.data.fs.SeafDir
import com.xmitya.seafilesync.data.fs.SeafDirent
import com.xmitya.seafilesync.data.fs.SeafFile
import java.io.File

/**
 * A block that exists locally, identified but not read into memory. Blocks are up to 8 MiB and a
 * library can hold thousands, so they are described by where they live and read only when the
 * server says it is missing that one.
 */
data class LocalBlock(
    val id: String,
    val file: File,
    val offset: Long,
    val length: Int,
    /**
     * Set for encrypted libraries. The block is re-encrypted on read rather than kept around:
     * blocks reach 8 MiB and a library holds thousands, so holding ciphertext in memory for the
     * whole tree is not an option, and AES on this path is hardware-accelerated.
     */
    private val cipher: LibraryCipher? = null,
) {

    fun read(): ByteArray = readPlaintext().let { cipher?.encrypt(it) ?: it }

    private fun readPlaintext(): ByteArray = file.inputStream().use { stream ->
        stream.skip(offset)
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = stream.read(buffer, read, length - read)
            if (n < 0) break
            read += n
        }
        if (read == length) buffer else buffer.copyOf(read)
    }
}

data class LocalFileEntry(
    val path: String,
    val fileId: String,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val blocks: List<LocalBlock>,
)

/**
 * The local tree expressed the way the server stores it: content-addressed objects plus the
 * blocks they reference.
 */
data class LocalTree(
    val rootId: String,
    val objects: Map<String, FsObject>,
    val files: Map<String, LocalFileEntry>,
    val blocks: Map<String, LocalBlock>,
)

/**
 * Turns a directory on disk into Seafile objects.
 *
 * Blocks are fixed size rather than content-defined. The server deduplicates purely by the SHA-1
 * of a block's contents and never checks where the boundaries fell, so fixed splitting is fully
 * correct on the wire; it only gives up dedup against blocks the desktop client cut differently.
 * Rabin chunking is a later optimisation, not a correctness requirement.
 */
class LocalTreeBuilder(
    private val chunker: CdcChunker = CdcChunker(),
) {

    fun build(
        root: File,
        modifier: String,
        rules: IgnoreRules = IgnoreRules.load(root),
        cipher: LibraryCipher? = null,
    ): LocalTree {
        val objects = mutableMapOf<String, FsObject>()
        val files = mutableMapOf<String, LocalFileEntry>()
        val blocks = mutableMapOf<String, LocalBlock>()
        val rootId = buildDirectory(root, "", modifier, rules, cipher, objects, files, blocks)
        return LocalTree(rootId, objects, files, blocks)
    }

    private fun buildDirectory(
        directory: File,
        prefix: String,
        modifier: String,
        rules: IgnoreRules,
        cipher: LibraryCipher?,
        objects: MutableMap<String, FsObject>,
        files: MutableMap<String, LocalFileEntry>,
        blocks: MutableMap<String, LocalBlock>,
    ): String {
        val entries = mutableListOf<SeafDirent>()

        for (child in directory.listFiles().orEmpty().sortedBy { it.name }) {
            val path = "$prefix/${child.name}"
            if (isAlwaysIgnored(child) || rules.isIgnored(path, child.isDirectory)) continue

            if (child.isDirectory) {
                val id = buildDirectory(child, path, modifier, rules, cipher, objects, files, blocks)
                entries += SeafDirent.directory(
                    id = id,
                    name = child.name,
                    mtime = child.lastModified() / 1000,
                )
            } else {
                val entry = buildFile(child, path, cipher, objects, blocks)
                files[path] = entry
                entries += SeafDirent.file(
                    id = entry.fileId,
                    name = child.name,
                    mtime = entry.modifiedSeconds,
                    size = entry.sizeBytes,
                    modifier = modifier,
                )
            }
        }

        // Descending name order over raw UTF-8 bytes, matching how the server stores dirents.
        // Any other order produces a different directory id for identical content.
        val dir = SeafDir(entries).sorted()
        val id = dir.id
        objects[id] = dir
        return id
    }

    /**
     * The content id of a single file, without walking or allocating the rest of the tree. Used
     * to answer "is this already the server's copy?" before deciding a file is in conflict.
     */
    fun fileId(file: File, cipher: LibraryCipher? = null): String =
        buildFile(file, "/${file.name}", cipher, mutableMapOf(), mutableMapOf()).fileId

    private fun buildFile(
        file: File,
        path: String,
        cipher: LibraryCipher?,
        objects: MutableMap<String, FsObject>,
        blocks: MutableMap<String, LocalBlock>,
    ): LocalFileEntry {
        val blockList = mutableListOf<LocalBlock>()
        val size = file.length()

        file.inputStream().use { stream ->
            chunker.chunk(stream) { chunk, data ->
                // Boundaries come from the plaintext, so an encrypted library still deduplicates
                // the way an unencrypted one does; only the stored bytes differ.
                val plaintext = data.copyOf(chunk.length)
                // The id must be the hash of what the server will store, so for an encrypted
                // library it is the hash of the ciphertext, not of the file's own bytes.
                val stored = cipher?.encrypt(plaintext) ?: plaintext
                val block = LocalBlock(ObjectId.ofBytes(stored), file, chunk.offset, chunk.length, cipher)
                blockList += block
                blocks[block.id] = block
            }
        }

        val seafFile = SeafFile(blockIds = blockList.map { it.id }, size = size)
        val fileId = seafFile.id
        objects[fileId] = seafFile

        return LocalFileEntry(
            path = path,
            fileId = fileId,
            sizeBytes = size,
            modifiedSeconds = file.lastModified() / 1000,
            blocks = blockList,
        )
    }

    companion object {
        /**
         * Never uploaded regardless of the library's own rules: platform droppings, editor
         * scratch files, this app's partial downloads, and the ignore file itself.
         *
         * Partial downloads matter most. Uploading one would publish a half-written file under a
         * hidden name and then keep it forever.
         */
        fun isAlwaysIgnored(file: File): Boolean {
            val name = file.name
            return name == ".DS_Store" ||
                name == "Thumbs.db" ||
                name == IgnoreRules.FILE_NAME ||
                name.endsWith(".seafile-part") ||
                name.startsWith(".~") ||
                name.startsWith("~$")
        }
    }
}
