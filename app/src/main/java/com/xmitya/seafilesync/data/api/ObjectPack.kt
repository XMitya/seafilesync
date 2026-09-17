package com.xmitya.seafilesync.data.api

import com.xmitya.seafilesync.data.fs.OBJECT_ID_LENGTH
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * The wire format shared by `pack-fs` (download) and `recv-fs` (upload): a bare concatenation of
 *
 *     [40 bytes: object id in hex] [4 bytes: payload length, big endian] [payload: zlib JSON]
 *
 * with no envelope, count or terminator. The stream simply ends.
 *
 * Note the id is the SHA-1 of the *decompressed* JSON, so it cannot be checked without inflating.
 */
object ObjectPack {

    /**
     * A `pack-fs` response is capped at 1 MiB server-side regardless of how many objects were
     * asked for, so a request has to tolerate getting back fewer objects than it listed.
     */
    const val MAX_RESPONSE_BYTES = 1 shl 20

    data class Entry(
        val id: String,
        val json: ByteArray,
    ) {
        // Generated equals/hashCode would compare the array by identity.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Entry && id == other.id && json.contentEquals(other.json))

        override fun hashCode(): Int = 31 * id.hashCode() + json.contentHashCode()
    }

    /** Reads until the stream ends. A truncated trailing record is an error, not a silent stop. */
    fun read(input: InputStream): List<Entry> {
        val entries = mutableListOf<Entry>()
        while (true) {
            val id = input.readFullyOrNull(OBJECT_ID_LENGTH) ?: return entries
            val lengthBytes = input.readFully(4)
            val length = ((lengthBytes[0].toInt() and 0xFF) shl 24) or
                ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                ((lengthBytes[2].toInt() and 0xFF) shl 8) or
                (lengthBytes[3].toInt() and 0xFF)
            val compressed = input.readFully(length)
            entries += Entry(id.decodeToString(), inflate(compressed))
        }
    }

    fun write(entries: List<Entry>, output: OutputStream) {
        for (entry in entries) {
            require(entry.id.length == OBJECT_ID_LENGTH) { "Bad object id ${entry.id}" }
            val compressed = deflate(entry.json)
            output.write(entry.id.toByteArray(Charsets.US_ASCII))
            output.write(
                byteArrayOf(
                    (compressed.size ushr 24).toByte(),
                    (compressed.size ushr 16).toByte(),
                    (compressed.size ushr 8).toByte(),
                    compressed.size.toByte(),
                ),
            )
            output.write(compressed)
        }
    }

    fun encode(entries: List<Entry>): ByteArray =
        ByteArrayOutputStream().also { write(entries, it) }.toByteArray()

    private fun inflate(compressed: ByteArray): ByteArray =
        InflaterInputStream(compressed.inputStream()).use { it.readBytes() }

    private fun deflate(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION)).use { it.write(raw) }
        return out.toByteArray()
    }

    /** Returns null only at a clean record boundary; a partial read means the stream was cut. */
    private fun InputStream.readFullyOrNull(count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(buffer, read, count - read)
            if (n < 0) {
                if (read == 0) return null
                throw EOFException("Object pack truncated after $read of $count bytes")
            }
            read += n
        }
        return buffer
    }

    private fun InputStream.readFully(count: Int): ByteArray =
        readFullyOrNull(count) ?: throw EOFException("Object pack ended mid-record")
}
