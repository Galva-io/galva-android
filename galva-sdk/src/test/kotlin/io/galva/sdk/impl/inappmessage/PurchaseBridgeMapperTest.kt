package io.galva.sdk.impl.inappmessage

import com.android.billingclient.api.BillingClient.BillingResponseCode
import io.galva.billing.model.BillingLaunchState
import io.galva.billing.model.BillingLaunchState.FailureKind
import io.galva.iam.bridge.BridgeError
import io.galva.iam.bridge.BridgeException
import io.galva.iam.bridge.PurchaseRequest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PurchaseBridgeMapperTest {

    private val withOffer = PurchaseRequest("pro", "monthly", "intro")
    private val basePlan = PurchaseRequest("pro", "monthly", null)

    private fun failed(kind: FailureKind = FailureKind.BILLING, code: Int? = null) =
        BillingLaunchState.Failed("pro", "token", "reason", responseCode = code, kind = kind)

    private fun codeOf(state: BillingLaunchState.Failed, request: PurchaseRequest = withOffer) =
        PurchaseBridgeMapper.error(state, request).code

    @Test
    fun `flow outcomes are results`() {
        assertNull(PurchaseBridgeMapper.map(BillingLaunchState.Launching("pro", null), withOffer))
        assertNull(PurchaseBridgeMapper.map(BillingLaunchState.Showing("pro", null), withOffer))
        assertEquals(
            JsonPrimitive("pending"),
            PurchaseBridgeMapper.map(BillingLaunchState.Pending("pro", null), withOffer)!!.getOrThrow()["outcome"],
        )
        assertEquals(
            JsonPrimitive("cancelled"),
            PurchaseBridgeMapper.map(BillingLaunchState.Cancelled("pro", null), withOffer)!!.getOrThrow()["outcome"],
        )
    }

    @Test
    fun `completed purchase carries the transaction and account token`() {
        val state = BillingLaunchState.Completed("pro", "t", "purchase-token", "GPA.1", 0L, true, "anon-1")

        val result = PurchaseBridgeMapper.map(state, withOffer)!!.getOrThrow()

        assertEquals(JsonPrimitive("completed"), result["outcome"])
        val transaction = result["transaction"]!!.jsonObject
        assertEquals(JsonPrimitive("GPA.1"), transaction["id"])
        assertEquals(JsonPrimitive("intro"), transaction["offerId"])
        assertEquals(JsonPrimitive("anon-1"), transaction["appAccountToken"])
    }

    @Test
    fun `failures are errors, never cancelled`() {
        val error = PurchaseBridgeMapper.map(failed(code = BillingResponseCode.ERROR), withOffer)!!.exceptionOrNull()

        assertEquals(BridgeError.Code.PURCHASE_FAILED, (error as BridgeException).error.code)
    }

    @Test
    fun `failure kinds map to the iOS error codes`() {
        assertEquals(BridgeError.Code.PRODUCT_UNAVAILABLE, codeOf(failed(FailureKind.PRODUCT_NOT_FOUND)))
        assertEquals(BridgeError.Code.INELIGIBLE_FOR_OFFER, codeOf(failed(FailureKind.OFFER_NOT_FOUND)))
        assertEquals(BridgeError.Code.PRODUCT_UNAVAILABLE, codeOf(failed(FailureKind.OFFER_NOT_FOUND), basePlan))
        assertEquals(BridgeError.Code.NETWORK_ERROR, codeOf(failed(FailureKind.NOT_CONNECTED)))
        assertEquals(BridgeError.Code.TIMEOUT, codeOf(failed(FailureKind.TIMEOUT)))
    }

    @Test
    fun `play response codes map to the iOS error codes`() {
        assertEquals(BridgeError.Code.PRODUCT_UNAVAILABLE, codeOf(failed(code = BillingResponseCode.ITEM_UNAVAILABLE)))
        assertEquals(BridgeError.Code.PURCHASE_NOT_ALLOWED, codeOf(failed(code = BillingResponseCode.BILLING_UNAVAILABLE)))
        assertEquals(BridgeError.Code.PURCHASE_NOT_ALLOWED, codeOf(failed(code = BillingResponseCode.FEATURE_NOT_SUPPORTED)))
        assertEquals(BridgeError.Code.NETWORK_ERROR, codeOf(failed(code = BillingResponseCode.NETWORK_ERROR)))
        assertEquals(BridgeError.Code.NETWORK_ERROR, codeOf(failed(code = BillingResponseCode.SERVICE_UNAVAILABLE)))
        assertEquals(BridgeError.Code.INVALID_OFFER, codeOf(failed(code = BillingResponseCode.DEVELOPER_ERROR)))
        assertEquals(BridgeError.Code.PURCHASE_FAILED, codeOf(failed(code = BillingResponseCode.DEVELOPER_ERROR), basePlan))
        assertEquals(BridgeError.Code.PURCHASE_FAILED, codeOf(failed(code = BillingResponseCode.ITEM_ALREADY_OWNED)))
    }
}
