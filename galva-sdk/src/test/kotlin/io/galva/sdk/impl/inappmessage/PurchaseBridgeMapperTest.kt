package io.galva.sdk.impl.inappmessage

import io.galva.iam.bridge.BridgeError
import io.galva.iam.bridge.PurchaseRequest
import io.galva.sdk.GalvaPurchaseException
import io.galva.sdk.GalvaPurchaseResult
import io.galva.sdk.GalvaTransaction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PurchaseBridgeMapperTest {

    private val request = PurchaseRequest("pro", "monthly", "intro")

    @Test
    fun `completed purchase carries the transaction and account token`() {
        val transaction = GalvaTransaction("GPA.1", "pro", "token", 0L, "acct-1", acknowledged = true)

        val answer = PurchaseBridgeMapper.result(GalvaPurchaseResult.Completed(transaction), request)

        assertEquals(JsonPrimitive("completed"), answer["outcome"])
        val sent = answer["transaction"]!!.jsonObject
        assertEquals(JsonPrimitive("GPA.1"), sent["id"])
        assertEquals(JsonPrimitive("pro"), sent["productId"])
        assertEquals(JsonPrimitive("monthly"), sent["basePlanId"])
        assertEquals(JsonPrimitive("intro"), sent["offerId"])
        assertEquals(JsonPrimitive(true), sent["verified"])
        assertEquals(JsonPrimitive("acct-1"), sent["appAccountToken"])
        assertEquals(JsonPrimitive("1970-01-01T00:00:00.000Z"), sent["purchaseDate"])
    }

    @Test
    fun `a purchase without an account token or order id still encodes`() {
        val answer = PurchaseBridgeMapper.result(
            GalvaPurchaseResult.Completed(GalvaTransaction(null, "pro", "token", 0L)),
            PurchaseRequest("pro", "monthly", null),
        )

        val sent = answer["transaction"]!!.jsonObject
        assertEquals(JsonNull, sent["id"])
        assertEquals(JsonNull, sent["offerId"])
        assertFalse(sent.containsKey("appAccountToken"))
    }

    @Test
    fun `failures map to the iOS bridge codes`() {
        val cases = mapOf(
            GalvaPurchaseException.ProductUnavailable() to BridgeError.Code.PRODUCT_UNAVAILABLE,
            GalvaPurchaseException.PurchaseNotAllowed() to BridgeError.Code.PURCHASE_NOT_ALLOWED,
            GalvaPurchaseException.IneligibleForOffer() to BridgeError.Code.INELIGIBLE_FOR_OFFER,
            GalvaPurchaseException.NotAvailableInStorefront() to BridgeError.Code.NOT_AVAILABLE_IN_STOREFRONT,
            GalvaPurchaseException.InvalidOffer("bad") to BridgeError.Code.INVALID_OFFER,
            GalvaPurchaseException.VerificationFailed("sig") to BridgeError.Code.VERIFICATION_FAILED,
            GalvaPurchaseException.NetworkError("offline") to BridgeError.Code.NETWORK_ERROR,
            GalvaPurchaseException.Timeout() to BridgeError.Code.TIMEOUT,
            GalvaPurchaseException.Failed("other") to BridgeError.Code.PURCHASE_FAILED,
        )

        cases.forEach { (failure, code) -> assertEquals(failure::class.simpleName, code, PurchaseBridgeMapper.error(failure).code) }
        assertEquals("offline", PurchaseBridgeMapper.error(GalvaPurchaseException.NetworkError("offline")).message)
    }
}
