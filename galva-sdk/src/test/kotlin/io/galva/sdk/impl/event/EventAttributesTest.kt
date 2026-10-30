package io.galva.sdk.impl.event

import android.net.Uri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.util.Date
import java.util.UUID

/** Same coercion rules as the iOS SDK's `AnyJSONValue.coercing`. */
@RunWith(RobolectricTestRunner::class)
class EventAttributesTest {

    private enum class Plan { PRO }

    @Test
    fun `JSON scalars are kept`() {
        val result = EventAttributes.coerce(
            mapOf("sku" to "pro_yearly", "price" to 9.99, "count" to 2, "big" to 3L, "trial" to true, "none" to null)
        )

        assertEquals(
            JsonObject(
                mapOf(
                    "sku" to JsonPrimitive("pro_yearly"),
                    "price" to JsonPrimitive(9.99),
                    "count" to JsonPrimitive(2),
                    "big" to JsonPrimitive(3L),
                    "trial" to JsonPrimitive(true),
                    "none" to JsonNull,
                )
            ),
            result,
        )
    }

    @Test
    fun `dates, uris, uuids, decimals and enums become strings`() {
        val id = UUID.fromString("0190A3B2-0000-7000-8000-000000000001")
        val result = EventAttributes.coerce(
            mapOf(
                "at" to Date(0),
                "link" to Uri.parse("https://galva.io/x"),
                "id" to id,
                "amount" to BigDecimal("19.990"),
                "plan" to Plan.PRO,
            )
        )

        assertEquals(JsonPrimitive("1970-01-01T00:00:00.000Z"), result["at"])
        assertEquals(JsonPrimitive("https://galva.io/x"), result["link"])
        assertEquals(JsonPrimitive("0190a3b2-0000-7000-8000-000000000001"), result["id"])
        assertEquals(JsonPrimitive("19.990"), result["amount"])
        assertEquals(JsonPrimitive("PRO"), result["plan"])
    }

    @Test
    fun `unsupported values and non-finite numbers are dropped`() {
        val result = EventAttributes.coerce(
            mapOf("ok" to 1, "object" to Any(), "lambda" to { 1 }, "nan" to Double.NaN, "inf" to Float.POSITIVE_INFINITY)
        )

        assertEquals(setOf("ok"), result.keys)
    }

    @Test
    fun `nested maps and collections are filtered recursively`() {
        val result = EventAttributes.coerce(
            mapOf(
                "items" to listOf("a", Any(), 2),
                "scores" to intArrayOf(1, 2),
                "meta" to mapOf("ok" to true, 3 to "non-string key", "drop" to Any()),
            )
        )

        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive(2))), result["items"])
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), result["scores"])
        assertEquals(JsonObject(mapOf("ok" to JsonPrimitive(true))), result["meta"])
        assertFalse((result["meta"] as JsonObject).containsKey("drop"))
    }
}
