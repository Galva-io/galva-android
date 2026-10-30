package io.galva.common.utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class JsonUtilsTest {

    private enum class Plan { PRO }

    @Test
    fun `scalars map to JSON primitives`() {
        assertEquals(JsonPrimitive("a"), JsonUtils.convertObjectToJsonElement("a"))
        assertEquals(JsonPrimitive(13), JsonUtils.convertObjectToJsonElement(13))
        assertEquals(JsonPrimitive(true), JsonUtils.convertObjectToJsonElement(true))
        assertEquals(JsonNull, JsonUtils.convertObjectToJsonElement(null))
    }

    @Test
    fun `unsupported values fall back to their own string form`() {
        val id = UUID.fromString("0190a3b2-0000-7000-8000-000000000001")

        assertEquals(JsonPrimitive(id.toString()), JsonUtils.convertObjectToJsonElement(id))
    }

    @Test
    fun `enums map to their name`() {
        assertEquals(JsonPrimitive("PRO"), JsonUtils.convertObjectToJsonElement(Plan.PRO))
    }

    @Test
    fun `collections and nested maps are converted recursively`() {
        val value = mapOf("tags" to setOf("a", "b"), "scores" to arrayOf(1, 2), "nested" to mapOf("k" to listOf(null)))

        val expected = JsonObject(
            mapOf(
                "tags" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                "scores" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))),
                "nested" to JsonObject(mapOf("k" to JsonArray(listOf(JsonNull)))),
            )
        )
        assertEquals(expected, JsonUtils.convertObjectToJsonElement(value))
    }
}
