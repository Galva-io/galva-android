package io.galva.sdk.impl.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import io.galva.billing.model.BillingLaunchState
import io.galva.billing.model.BillingLaunchState.FailureKind
import io.galva.sdk.GalvaPurchaseException
import io.galva.sdk.GalvaPurchaseResult
import io.galva.sdk.GalvaTransaction
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class PlayBillingPurchaseTest {

    private fun failed(kind: FailureKind = FailureKind.BILLING, code: Int? = null) =
        BillingLaunchState.Failed("pro", "token", "reason", responseCode = code, kind = kind)

    private fun exceptionOf(state: BillingLaunchState.Failed, offerRequested: Boolean = true): KClass<*> =
        PlayBillingPurchase.exception(state, offerRequested)::class

    @Test
    fun `flow outcomes are results`() {
        assertNull(PlayBillingPurchase.result(BillingLaunchState.Launching("pro", null), true))
        assertNull(PlayBillingPurchase.result(BillingLaunchState.Showing("pro", null), true))
        assertEquals(GalvaPurchaseResult.Pending, PlayBillingPurchase.result(BillingLaunchState.Pending("pro", null), true))
        assertEquals(GalvaPurchaseResult.Cancelled, PlayBillingPurchase.result(BillingLaunchState.Cancelled("pro", null), true))
    }

    @Test
    fun `completed purchase carries the transaction and account token`() {
        val state = BillingLaunchState.Completed("pro", "t", "purchase-token", "GPA.1", 42L, false, "anon-1")

        assertEquals(
            GalvaPurchaseResult.Completed(GalvaTransaction("GPA.1", "pro", "purchase-token", 42L, "anon-1", acknowledged = false)),
            PlayBillingPurchase.result(state, true),
        )
    }

    @Test
    fun `await returns the terminal state of the flow`() = runBlocking {
        val states = flowOf(
            BillingLaunchState.Launching("pro", null),
            BillingLaunchState.Showing("pro", null),
            BillingLaunchState.Cancelled("pro", null),
        )

        assertEquals(GalvaPurchaseResult.Cancelled, PlayBillingPurchase.await(states, offerRequested = false))
    }

    @Test
    fun `await throws the failure, never reports it as cancelled`() = runBlocking {
        val thrown = runCatching { PlayBillingPurchase.await(flowOf(failed(code = BillingResponseCode.ERROR)), true) }

        assertTrue(thrown.exceptionOrNull() is GalvaPurchaseException.Failed)
    }

    @Test
    fun `a flow that ends without a result is a failure`() = runBlocking {
        val thrown = runCatching { PlayBillingPurchase.await(emptyFlow(), true) }

        assertTrue(thrown.exceptionOrNull() is GalvaPurchaseException.Failed)
    }

    @Test
    fun `failure kinds map to the iOS error cases`() {
        assertEquals(GalvaPurchaseException.ProductUnavailable::class, exceptionOf(failed(FailureKind.PRODUCT_NOT_FOUND)))
        assertEquals(GalvaPurchaseException.IneligibleForOffer::class, exceptionOf(failed(FailureKind.OFFER_NOT_FOUND)))
        assertEquals(
            GalvaPurchaseException.ProductUnavailable::class,
            exceptionOf(failed(FailureKind.OFFER_NOT_FOUND), offerRequested = false),
        )
        assertEquals(GalvaPurchaseException.NetworkError::class, exceptionOf(failed(FailureKind.NOT_CONNECTED)))
        assertEquals(GalvaPurchaseException.Timeout::class, exceptionOf(failed(FailureKind.TIMEOUT)))
    }

    @Test
    fun `play response codes map to the iOS error cases`() {
        assertEquals(GalvaPurchaseException.ProductUnavailable::class, exceptionOf(failed(code = BillingResponseCode.ITEM_UNAVAILABLE)))
        assertEquals(GalvaPurchaseException.PurchaseNotAllowed::class, exceptionOf(failed(code = BillingResponseCode.BILLING_UNAVAILABLE)))
        assertEquals(GalvaPurchaseException.PurchaseNotAllowed::class, exceptionOf(failed(code = BillingResponseCode.FEATURE_NOT_SUPPORTED)))
        assertEquals(GalvaPurchaseException.NetworkError::class, exceptionOf(failed(code = BillingResponseCode.NETWORK_ERROR)))
        assertEquals(GalvaPurchaseException.NetworkError::class, exceptionOf(failed(code = BillingResponseCode.SERVICE_UNAVAILABLE)))
        assertEquals(GalvaPurchaseException.InvalidOffer::class, exceptionOf(failed(code = BillingResponseCode.DEVELOPER_ERROR)))
        assertEquals(
            GalvaPurchaseException.Failed::class,
            exceptionOf(failed(code = BillingResponseCode.DEVELOPER_ERROR), offerRequested = false),
        )
        assertEquals(GalvaPurchaseException.Failed::class, exceptionOf(failed(code = BillingResponseCode.ITEM_ALREADY_OWNED)))
    }
}
