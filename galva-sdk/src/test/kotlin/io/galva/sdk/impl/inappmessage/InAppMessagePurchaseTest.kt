package io.galva.sdk.impl.inappmessage

import android.app.Activity
import io.galva.billing.BillingManager
import io.galva.billing.model.BillingLaunchState
import io.galva.common.logger.NoOpLogger
import io.galva.iam.bridge.BridgeError
import io.galva.iam.bridge.BridgeException
import io.galva.iam.bridge.PurchaseRequest
import io.galva.network.service.IAMService
import io.galva.sdk.GalvaPurchaseController
import io.galva.sdk.GalvaPurchaseException
import io.galva.sdk.GalvaPurchaseRequest
import io.galva.sdk.GalvaPurchaseResult
import io.galva.sdk.GalvaTransaction
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

/** How a message's `requestPurchase` is run: through a purchase controller, or the built-in Play flow. */
@RunWith(RobolectricTestRunner::class)
class InAppMessagePurchaseTest {
    private val activity = mock<Activity>()
    private val request = PurchaseRequest("pro", "monthly", "intro")
    private val transaction = GalvaTransaction("GPA.1", "pro", "token", 0L, "acct")
    private val billing = mock<BillingManager> {
        onBlocking { getStorefrontCountryCode() } doReturn "US"
        on { launchBilling(any(), any(), any(), anyOrNull(), anyOrNull()) } doReturn flowOf(
            BillingLaunchState.Launching("pro", "t"),
            BillingLaunchState.Completed("pro", "t", "play-token", "GPA.play", 0L, false, "acct"),
        )
    }

    private val reported = mutableListOf<GalvaTransaction>()

    private fun viewModel(controller: GalvaPurchaseController?) = InAppMessageViewModel(
        billing, mock<IAMService>(), purchaseController = { controller }, logger = NoOpLogger, onPurchased = { reported += it },
    )

    private fun purchase(controller: GalvaPurchaseController?): Result<JsonObject> = runBlocking {
        viewModel(controller).purchase(activity, request, messageId = "msg-1")
    }

    private fun controller(block: suspend (GalvaPurchaseRequest) -> GalvaPurchaseResult) = object : GalvaPurchaseController {
        var received: GalvaPurchaseRequest? = null
        override suspend fun purchase(request: GalvaPurchaseRequest): GalvaPurchaseResult {
            received = request
            return block(request)
        }
    }

    private fun Result<JsonObject>.errorCode() = ((exceptionOrNull() as BridgeException).error).code

    @Test
    fun `without a controller the built-in Play flow runs`() {
        val answer = purchase(controller = null).getOrThrow()

        assertEquals(JsonPrimitive("completed"), answer["outcome"])
        assertEquals(JsonPrimitive("GPA.play"), answer["transaction"]!!.jsonObject["id"])
    }

    @Test
    fun `an installed controller runs the purchase instead of Play`() {
        val controller = controller { GalvaPurchaseResult.Completed(transaction) }

        val answer = purchase(controller).getOrThrow()

        assertEquals(JsonPrimitive("GPA.1"), answer["transaction"]!!.jsonObject["id"])
        assertEquals(JsonPrimitive("intro"), answer["transaction"]!!.jsonObject["offerId"])
        verify(billing, never()).launchBilling(any(), any(), any(), anyOrNull(), anyOrNull())
        val received = controller.received!!
        assertEquals(listOf("pro", "monthly", "intro", "msg-1"), listOf(received.productId, received.basePlanId, received.offerId, received.messageId))
        assertTrue(received.activity === activity)
    }

    @Test
    fun `a controller can fall back to the built-in Play flow`() {
        val answer = purchase(controller { it.purchaseWithPlayBilling() }).getOrThrow()

        assertEquals(JsonPrimitive("GPA.play"), answer["transaction"]!!.jsonObject["id"])
    }

    @Test
    fun `completed purchases are reported to Galva, whichever billing layer ran them`() {
        purchase(controller { GalvaPurchaseResult.Completed(transaction) })
        purchase(controller = null)
        purchase(controller { GalvaPurchaseResult.Pending })
        purchase(controller { GalvaPurchaseResult.Cancelled })
        purchase(controller { throw GalvaPurchaseException.Failed("declined") })

        assertEquals(listOf("GPA.1", "GPA.play"), reported.map { it.orderId })
    }

    @Test
    fun `pending and cancelled are results`() {
        assertEquals(JsonPrimitive("pending"), purchase(controller { GalvaPurchaseResult.Pending }).getOrThrow()["outcome"])
        assertEquals(JsonPrimitive("cancelled"), purchase(controller { GalvaPurchaseResult.Cancelled }).getOrThrow()["outcome"])
    }

    @Test
    fun `structured controller failures keep their bridge code`() {
        assertEquals(
            BridgeError.Code.INELIGIBLE_FOR_OFFER,
            purchase(controller { throw GalvaPurchaseException.IneligibleForOffer() }).errorCode(),
        )
        assertEquals(
            BridgeError.Code.NETWORK_ERROR,
            purchase(controller { throw GalvaPurchaseException.NetworkError("offline") }).errorCode(),
        )
    }

    @Test
    fun `any other controller failure is a generic purchase failure`() {
        assertEquals(BridgeError.Code.PURCHASE_FAILED, purchase(controller { error("boom") }).errorCode())
    }
}
