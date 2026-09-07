package com.xmitya.seafilesync.data.api.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Reads a flag the server does not encode consistently.
 *
 * `download-info` returns `"encrypted": ""` for a plain library and `"encrypted": 1` for an
 * encrypted one -- an empty string in one case and an integer in the other, from the same field
 * of the same endpoint. A strict Boolean or String parser handles exactly one of those and fails
 * on the other, which is how encrypted libraries came to be unusable while plain ones worked.
 */
object LenientBooleanSerializer : KSerializer<Boolean> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientBoolean", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Boolean {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
            ?: return decoder.decodeBoolean()
        element.booleanOrNull?.let { return it }
        element.intOrNull?.let { return it != 0 }
        return element.content.isNotEmpty() && element.content != "0" && element.content != "false"
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}
