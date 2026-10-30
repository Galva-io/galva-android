package iam

import io.galva.iam.bridge.AlertAction
import io.galva.iam.bridge.BridgeError
import io.galva.iam.bridge.BridgeException
import io.galva.iam.bridge.BridgeRequests
import io.galva.iam.bridge.PurchaseRequest
import io.galva.network.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BridgeRequestsTest {

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("expected invalidPayload")
        } catch (e: BridgeException) {
            assertEquals(BridgeError.Code.INVALID_PAYLOAD, e.error.code)
        }
    }

    // ── requestPurchase ────────────────────────────────────────────────

    @Test
    fun `purchase accepts the offer id as a string or an object`() {
        assertEquals(
            PurchaseRequest("pro", "monthly", "intro"),
            BridgeRequests.parsePurchase(json("""{"productId":"pro","basePlanId":"monthly","promotionalOffer":"intro"}""")),
        )
        assertEquals(
            PurchaseRequest("pro", "monthly", "intro"),
            BridgeRequests.parsePurchase(json("""{"productId":"pro","basePlanId":"monthly","promotionalOffer":{"offerId":"intro"}}""")),
        )
    }

    @Test
    fun `purchase without promotional offer buys the base plan`() {
        assertNull(BridgeRequests.parsePurchase(json("""{"productId":"pro","basePlanId":"monthly"}""")).offerId)
        assertNull(BridgeRequests.parsePurchase(json("""{"productId":"pro","basePlanId":"monthly","promotionalOffer":null}""")).offerId)
    }

    @Test
    fun `purchase rejects missing fields and malformed offers`() {
        assertInvalid { BridgeRequests.parsePurchase(null) }
        assertInvalid { BridgeRequests.parsePurchase(json("""{"basePlanId":"monthly"}""")) }
        assertInvalid { BridgeRequests.parsePurchase(json("""{"productId":"pro"}""")) }
        assertInvalid { BridgeRequests.parsePurchase(json("""{"productId":"pro","basePlanId":"m","promotionalOffer":{}}""")) }
        assertInvalid { BridgeRequests.parsePurchase(json("""{"productId":"pro","basePlanId":"m","promotionalOffer":[1]}""")) }
    }

    // ── open URL ───────────────────────────────────────────────────────

    @Test
    fun `url must be present and absolute`() {
        assertEquals("https://play.google.com/store/account/subscriptions",
            BridgeRequests.parseUrl(json("""{"url":"https://play.google.com/store/account/subscriptions"}""")))
        assertInvalid { BridgeRequests.parseUrl(json("""{}""")) }
        assertInvalid { BridgeRequests.parseUrl(json("""{"url":"no-scheme"}""")) }
    }

    // ── apiFetch ───────────────────────────────────────────────────────

    @Test
    fun `apiFetch defaults to GET without retry`() {
        val request = BridgeRequests.parseApiFetch(json("""{"path":"/identities/x"}"""))

        assertEquals(HttpMethod.GET, request.method)
        assertNull(request.body)
        assertFalse(request.shouldRetry)
    }

    @Test
    fun `apiFetch JSON body is serialized with a default content type`() {
        val request = BridgeRequests.parseApiFetch(
            json("""{"path":"/x","method":"patch","body":{"a":1},"shouldRetry":true,"headers":{"Accept":"application/json","n":1}}""")
        )

        assertEquals(HttpMethod.PATCH, request.method)
        assertEquals("""{"a":1}""", request.body)
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("application/json", request.headers["Accept"])
        assertFalse("non-string header values are dropped", request.headers.containsKey("n"))
        assertTrue(request.shouldRetry)
    }

    @Test
    fun `apiFetch string body is sent as is without forcing a content type`() {
        val request = BridgeRequests.parseApiFetch(json("""{"path":"/x","method":"POST","body":"raw"}"""))

        assertEquals("raw", request.body)
        assertFalse(request.headers.containsKey("Content-Type"))
    }

    @Test
    fun `apiFetch rejects empty path, unknown method and bodies on GET`() {
        assertInvalid { BridgeRequests.parseApiFetch(json("""{"path":"  "}""")) }
        assertInvalid { BridgeRequests.parseApiFetch(json("""{"path":"/x","method":"TRACE"}""")) }
        assertInvalid { BridgeRequests.parseApiFetch(json("""{"path":"/x","body":{"a":1}}""")) }
    }

    // ── showAlert ──────────────────────────────────────────────────────

    @Test
    fun `alert parses actions and defaults unknown styles`() {
        val options = BridgeRequests.parseShowAlert(
            json("""{"title":"Delete?","actions":[{"id":"d","title":"Delete","style":"destructive"},{"id":"c","title":"Cancel","style":"CANCEL"},{"id":"o","title":"Other","style":"weird"}]}""")
        )

        assertEquals("Delete?", options.title)
        assertNull(options.message)
        assertEquals(
            listOf(AlertAction.Style.DESTRUCTIVE, AlertAction.Style.CANCEL, AlertAction.Style.DEFAULT),
            options.actions.map { it.style },
        )
    }

    @Test
    fun `alert rejects empty actions, two cancels and more than three actions`() {
        assertInvalid { BridgeRequests.parseShowAlert(json("""{"actions":[]}""")) }
        assertInvalid {
            BridgeRequests.parseShowAlert(json("""{"actions":[{"id":"a","title":"A","style":"cancel"},{"id":"b","title":"B","style":"cancel"}]}"""))
        }
        assertInvalid {
            BridgeRequests.parseShowAlert(
                json("""{"actions":[{"id":"a","title":"A"},{"id":"b","title":"B"},{"id":"c","title":"C"},{"id":"d","title":"D"}]}""")
            )
        }
        assertInvalid { BridgeRequests.parseShowAlert(json("""{"actions":[{"id":"","title":"A"}]}""")) }
    }
}
