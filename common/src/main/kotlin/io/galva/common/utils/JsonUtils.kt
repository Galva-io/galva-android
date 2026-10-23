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
    fun convertObjectToJsonElement(valueObject :Any?): JsonElement {
        return when (valueObject) {
            null -> JsonNull

            is JsonElement -> valueObject

            is String -> JsonPrimitive(valueObject)

            is Number -> JsonPrimitive(valueObject)

            is Boolean -> JsonPrimitive(valueObject)

            is Map<*, *> -> JsonObject(
                valueObject.entries.associate { (k, v) ->
                    k.toString() to convertObjectToJsonElement(v)
                }
            )

            is Iterable<*> -> JsonArray(
                valueObject.map { convertObjectToJsonElement(it) }
            )

            is Array<*> -> JsonArray(
                valueObject.map { convertObjectToJsonElement(it) }
            )

            is Enum<*> -> JsonPrimitive(valueObject.name)

            else -> JsonPrimitive(valueObject.toString())
        }
    }
    val defaultJson =
        Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
}