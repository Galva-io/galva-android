package io.galva.common.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

            is List<*> -> JsonArray(
                valueObject.map { convertObjectToJsonElement(it) }
            )

            else -> JsonPrimitive(toString())
        }
    }
    val defaultJson =
        Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
}