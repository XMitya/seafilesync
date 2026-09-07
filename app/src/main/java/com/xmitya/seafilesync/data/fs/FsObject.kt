package com.xmitya.seafilesync.data.fs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Values of the `type` field in an fs object, from SEAF_METADATA_TYPE_* on the server. */
const val TYPE_FILE = 1
const val TYPE_LINK = 2
const val TYPE_DIR = 3

/** Current object format for repos reporting `version: 1`. */
const val FS_OBJECT_VERSION = 1

private const val S_IFMT = 0xF000
private const val S_IFREG = 0x8000
private const val S_IFDIR = 0x4000

/** Mode the server writes for a plain file (0100644) and for a directory (040000). */
const val MODE_FILE = S_IFREG or 0x1A4
const val MODE_DIR = S_IFDIR

fun isRegular(mode: Int): Boolean = (mode and S_IFMT) == S_IFREG
fun isDirectory(mode: Int): Boolean = (mode and S_IFMT) == S_IFDIR

/**
 * A node in a library's Merkle tree. Its [id] is derived from the canonical JSON, so anything
 * that changes the JSON changes the identity of the object.
 */
sealed interface FsObject {
    val version: Int

    fun toJson(): JsonObject

    val id: String get() = ObjectId.ofFsObject(toJson())

    companion object {
        fun parse(json: JsonObject): FsObject =
            when (val type = json.getValue("type").jsonPrimitive.int()) {
                TYPE_FILE -> SeafFile.parse(json)
                TYPE_DIR -> SeafDir.parse(json)
                else -> error("Unsupported fs object type $type")
            }

        fun parse(bytes: ByteArray): FsObject =
            parse(SeafJson.parser.parseToJsonElement(bytes.decodeToString()).jsonObject)
    }
}

/** A file: an ordered list of block ids plus the total size those blocks reconstruct. */
data class SeafFile(
    val blockIds: List<String>,
    val size: Long,
    override val version: Int = FS_OBJECT_VERSION,
) : FsObject {

    override fun toJson(): JsonObject = buildJsonObject {
        put("block_ids", buildJsonArray { blockIds.forEach { add(JsonPrimitive(it)) } })
        put("size", size)
        put("type", TYPE_FILE)
        put("version", version)
    }

    companion object {
        fun parse(json: JsonObject) = SeafFile(
            blockIds = json.getValue("block_ids").jsonArray.map { it.jsonPrimitive.content },
            size = json.getValue("size").jsonPrimitive.long,
            version = json.getValue("version").jsonPrimitive.int(),
        )
    }
}

/**
 * A directory. Entries are held in the order the server stores them, which is descending by
 * name; [sorted] restores that order after entries are added or changed.
 */
data class SeafDir(
    val entries: List<SeafDirent>,
    override val version: Int = FS_OBJECT_VERSION,
) : FsObject {

    override fun toJson(): JsonObject = buildJsonObject {
        put("dirents", buildJsonArray { entries.forEach { add(it.toJson()) } })
        put("type", TYPE_DIR)
        put("version", version)
    }

    /**
     * Seafile keeps dirents in descending name order, compared with strcmp over the raw UTF-8
     * bytes. Kotlin's natural String order is over UTF-16 code units, which disagrees for
     * anything outside the BMP, so the comparison goes through the encoded bytes.
     */
    fun sorted(): SeafDir = copy(entries = entries.sortedWith(DESCENDING_BY_UTF8_NAME))

    companion object {
        val DESCENDING_BY_UTF8_NAME: Comparator<SeafDirent> =
            Comparator { a, b -> compareUtf8(b.name, a.name) }

        fun parse(json: JsonObject) = SeafDir(
            entries = json.getValue("dirents").jsonArray.map { SeafDirent.parse(it.jsonObject) },
            version = json.getValue("version").jsonPrimitive.int(),
        )

        private fun compareUtf8(left: String, right: String): Int {
            val a = left.toByteArray(Charsets.UTF_8)
            val b = right.toByteArray(Charsets.UTF_8)
            for (i in 0 until minOf(a.size, b.size)) {
                val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (diff != 0) return diff
            }
            return a.size - b.size
        }
    }
}

/**
 * One entry in a directory.
 *
 * [modifier] and [size] are written only for regular files. The server omits both for
 * subdirectory entries, and emitting them anyway would change the parent directory's id.
 */
data class SeafDirent(
    val id: String,
    val mode: Int,
    val name: String,
    val mtime: Long,
    val modifier: String? = null,
    val size: Long = 0,
) {

    val isFile: Boolean get() = isRegular(mode)
    val isDirectory: Boolean get() = isDirectory(mode)

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("mode", mode)
        if (isFile) put("modifier", modifier.orEmpty())
        put("mtime", mtime)
        put("name", name)
        if (isFile) put("size", size)
    }

    companion object {
        fun file(id: String, name: String, mtime: Long, size: Long, modifier: String) =
            SeafDirent(id = id, mode = MODE_FILE, name = name, mtime = mtime, modifier = modifier, size = size)

        fun directory(id: String, name: String, mtime: Long) =
            SeafDirent(id = id, mode = MODE_DIR, name = name, mtime = mtime)

        fun parse(json: JsonObject) = SeafDirent(
            id = json.getValue("id").jsonPrimitive.content,
            mode = json.getValue("mode").jsonPrimitive.int(),
            name = json.getValue("name").jsonPrimitive.content,
            mtime = json["mtime"]?.jsonPrimitive?.long ?: 0,
            modifier = json["modifier"]?.jsonPrimitive?.contentOrNull,
            size = json["size"]?.jsonPrimitive?.long ?: 0,
        )
    }
}

private fun JsonPrimitive.int(): Int = content.toInt()
