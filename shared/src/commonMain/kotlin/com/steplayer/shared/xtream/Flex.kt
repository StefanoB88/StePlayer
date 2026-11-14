package com.steplayer.shared.xtream

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Xtream-API servers are notoriously inconsistent: the same field can be a number,
 * a numeric string, an empty string, null, false, or even an array depending on the
 * server version. These serializers accept anything and never throw.
 */

internal fun JsonElement?.flexString(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> content.takeIf { it.isNotBlank() && it != "null" }
    is JsonArray -> firstOrNull()?.flexString()
    is JsonObject -> null
}

internal fun JsonElement?.flexLong(): Long? = flexString()?.let { s ->
    s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
}

/** Out-of-range values (garbage from some servers) become null instead of silently wrapping around. */
internal fun JsonElement?.flexInt(): Int? = flexLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

internal fun JsonElement?.flexDouble(): Double? = flexString()?.replace(',', '.')?.toDoubleOrNull()

internal fun JsonElement?.flexBool(): Boolean = when (val s = flexString()?.lowercase()) {
    null -> false
    "1", "true", "yes" -> true
    else -> s.toDoubleOrNull()?.let { it != 0.0 } ?: false
}

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

private abstract class FlexSerializer<T : Any>(name: String) : KSerializer<T?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("flex.$name", PrimitiveKind.STRING)
    abstract fun convert(element: JsonElement): T?
    override fun deserialize(decoder: Decoder): T? {
        val json = decoder as? JsonDecoder ?: return null
        return convert(json.decodeJsonElement())
    }
    override fun serialize(encoder: Encoder, value: T?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.toString())
    }
}

internal object FlexString : KSerializer<String?> by object : FlexSerializer<String>("String") {
    override fun convert(element: JsonElement) = element.flexString()
}

internal object FlexLong : KSerializer<Long?> by object : FlexSerializer<Long>("Long") {
    override fun convert(element: JsonElement) = element.flexLong()
}

internal object FlexInt : KSerializer<Int?> by object : FlexSerializer<Int>("Int") {
    override fun convert(element: JsonElement) = element.flexInt()
}

internal object FlexDouble : KSerializer<Double?> by object : FlexSerializer<Double>("Double") {
    override fun convert(element: JsonElement) = element.flexDouble()
}

internal object FlexBool : KSerializer<Boolean?> by object : FlexSerializer<Boolean>("Bool") {
    override fun convert(element: JsonElement) = element.flexBool()
}
