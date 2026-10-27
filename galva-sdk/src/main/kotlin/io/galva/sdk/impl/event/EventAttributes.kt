package io.galva.sdk.impl.event

import android.net.Uri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Turns a loose attribute map into JSON with the iOS SDK's rules (`AnyJSONValue.coercing`):
 * strings, booleans, numbers and nulls are kept; dates become ISO-8601 (UTC, milliseconds); URIs
 * and UUIDs become strings; decimals become strings; maps with string keys and collections are
 * converted recursively. Anything else — and non-finite numbers, which JSON can't carry — is
 * silently dropped.
 */
internal object EventAttributes {

    /** Deeper nesting is dropped: it also stops a map that contains itself. */
    private const val MAX_DEPTH = 32

    fun coerce(attributes: Map<String, Any?>): JsonObject =
        JsonObject(attributes.mapNotNull { (key, value) -> coerceValue(value)?.let { key to it } }.toMap())

    fun coerceValue(value: Any?): JsonElement? = coerceValue(value, depth = 0)

    private fun coerceValue(value: Any?, depth: Int): JsonElement? = if (depth > MAX_DEPTH) null else when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is CharSequence, is Char -> JsonPrimitive(value.toString())
        is Boolean -> JsonPrimitive(value)
        is Int, is Long, is Short, is Byte -> JsonPrimitive(value as Number)
        is Double -> value.takeIf { it.isFinite() }?.let(::JsonPrimitive)
        is Float -> value.takeIf { it.isFinite() }?.let(::JsonPrimitive)
        is BigDecimal, is BigInteger -> JsonPrimitive(value.toString())
        is Number -> value.toDouble().takeIf { it.isFinite() }?.let { JsonPrimitive(value) }
        is Date -> JsonPrimitive(isoDate(value))
        is Calendar -> JsonPrimitive(isoDate(value.time))
        is Uri, is URI, is URL -> JsonPrimitive(value.toString())
        is UUID -> JsonPrimitive(value.toString().lowercase(Locale.ROOT))
        is Enum<*> -> JsonPrimitive(value.name)
        is Map<*, *> -> JsonObject(
            value.entries.mapNotNull { (key, item) ->
                if (key !is String) return@mapNotNull null
                coerceValue(item, depth + 1)?.let { key to it }
            }.toMap()
        )
        is Iterable<*> -> JsonArray(value.mapNotNull { coerceValue(it, depth + 1) })
        is Array<*> -> JsonArray(value.mapNotNull { coerceValue(it, depth + 1) })
        is IntArray -> JsonArray(value.map(::JsonPrimitive))
        is LongArray -> JsonArray(value.map(::JsonPrimitive))
        is DoubleArray -> JsonArray(value.filter { it.isFinite() }.map(::JsonPrimitive))
        is FloatArray -> JsonArray(value.filter { it.isFinite() }.map(::JsonPrimitive))
        is BooleanArray -> JsonArray(value.map(::JsonPrimitive))
        else -> null
    }

    private fun isoDate(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(date)
}
