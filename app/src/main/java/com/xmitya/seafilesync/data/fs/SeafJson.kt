package com.xmitya.seafilesync.data.fs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical JSON for Seafile filesystem objects.
 *
 * The id of an fs object is the SHA-1 of its serialised JSON, so these bytes have to match the
 * server's byte for byte, or every object we upload gets an id nobody else agrees with. The
 * server produces them with jansson's `json_dumps(object, JSON_SORT_KEYS)`, which means: keys
 * sorted, `", "` between members and `": "` after each key. That is deliberately not what
 * kotlinx.serialization emits, hence this writer.
 *
 * Verified against real objects from a Seafile 11 server; see SeafJsonTest.
 */
object SeafJson {

    /** Lenient enough to read whatever the server sends. Parsing only. */
    val parser = Json { ignoreUnknownKeys = true }

    fun canonicalize(element: JsonElement): String = buildString { write(element, this) }

    private fun write(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                element.entries
                    .sortedBy { it.key }
                    .forEachIndexed { index, entry ->
                        if (index > 0) out.append(", ")
                        writeString(entry.key, out)
                        out.append(": ")
                        write(entry.value, out)
                    }
                out.append('}')
            }

            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, value ->
                    if (index > 0) out.append(", ")
                    write(value, out)
                }
                out.append(']')
            }

            JsonNull -> out.append("null")

            is JsonPrimitive ->
                if (element.isString) writeString(element.content, out) else out.append(element.content)
        }
    }

    /**
     * Escapes the way jansson does: quote, backslash and control characters only. Non-ASCII is
     * emitted as raw UTF-8, so a Cyrillic filename stays a Cyrillic filename rather than turning
     * into escape sequences that would hash differently.
     */
    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (char in value) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\b' -> out.append("\\b")
                char == '\u000C' -> out.append("\\f")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char < ' ' -> out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> out.append(char)
            }
        }
        out.append('"')
    }
}
