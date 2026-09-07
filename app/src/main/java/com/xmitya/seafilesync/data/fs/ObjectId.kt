package com.xmitya.seafilesync.data.fs

import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest

/** Seafile object ids are 40 lowercase hex characters. */
const val OBJECT_ID_LENGTH = 40

/** The id every Seafile store uses to mean "nothing here". */
const val EMPTY_OBJECT_ID = "0000000000000000000000000000000000000000"

object ObjectId {

    /** A block's id is the SHA-1 of the bytes as stored, after encryption for encrypted libraries. */
    fun ofBytes(bytes: ByteArray): String = sha1Hex(bytes)

    /** An fs object's id is the SHA-1 of its canonical JSON, not of the compressed payload. */
    fun ofFsObject(element: JsonElement): String =
        sha1Hex(SeafJson.canonicalize(element).toByteArray(Charsets.UTF_8))

    fun isValid(id: String): Boolean =
        id.length == OBJECT_ID_LENGTH && id.all { it in '0'..'9' || it in 'a'..'f' }

    private fun sha1Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).toHex()

    internal fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
