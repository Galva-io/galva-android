package io.galva.common.utils

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@InternalGalvaApi
object JsonUtils {
    /** Deeper nesting becomes null: it also stops a map that contains itself. */
    private const val MAX_DEPTH = 32

    fun convertObjectToJsonElement(valueObject: Any?): JsonElement = convert(valueObject, depth = 0)

    private fun convert(valueObject: Any?, depth: Int): JsonElement {
        if (depth > MAX_DEPTH) return JsonNull
        return when (valueObject) {
            null -> JsonNull

            is JsonElement -> valueObject

            is String -> JsonPrimitive(valueObject)

            is Number -> JsonPrimitive(valueObject)

            is Boolean -> JsonPrimitive(valueObject)

            is Map<*, *> -> JsonObject(
                valueObject.entries.associate { (k, v) ->
                    k.toString() to convert(v, depth + 1)
                }
            )

            is Iterable<*> -> JsonArray(
                valueObject.map { convert(it, depth + 1) }
            )

            is Array<*> -> JsonArray(
                valueObject.map { convert(it, depth + 1) }
            )

            is Enum<*> -> JsonPrimitive(valueObject.name)

            else -> JsonPrimitive(valueObject.toString())
        }
    }
    val defaultJson =
        Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
}