package io.galva.sdk

import android.app.Activity
import io.galva.billing.BillingManager
import io.galva.billing.model.BillingLaunchState
import io.galva.billing.model.BillingLaunchState.FailureKind
import io.galva.common.logger.NoOpLogger
import io.galva.common.utils.HashUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

/** `Galva.purchase`: an app-started purchase through the built-in Play Billing flow. */
@RunWith(RobolectricTestRunner::class)
class GalvaPurchaseTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val galva = Galva(NoOpLogger, scope)
    private val activity = mock<Activity>()

    @After
    fun tearDown() = scope.cancel()

    private fun billing(vararg states: BillingLaunchState) = mock<BillingManager> {
        on { launchBilling(any(), any(), any(), anyOrNull(), anyOrNull()) } doReturn flowOf(*states)
    }

    private fun purchase(billing: BillingManager, offerId: String? = "intro") = runBlocking {
        galva.purchaseWithPlayBilling(billing, activity, "pro", "monthly", offerId)
    }

    @Test
    fun `purchase before configure fails`() {
        assertThrows(GalvaPurchaseException.Failed::class.java) {
            runBlocking { galva.purchase(activity, "pro", "monthly", null) }
        }
    }

    @Test
    fun `the Play flow gets the ids and the hashed account id`() {
        val billing = billing(
            BillingLaunchState.Launching("pro", "t"),
            BillingLaunchState.Completed("pro", "t", "play-token", "GPA.1", 42L, false, "acct"),
        )

        val result = purchase(billing)

        verify(billing).launchBilling(activity, "pro", "monthly", "intro", HashUtils.hashToSafeToken(galva.obfuscatedAccountId))
        assertEquals(
            GalvaPurchaseResult.Completed(GalvaTransaction("GPA.1", "pro", "play-token", 42L, "acct", false)),
            result,
        )
    }

    @Test
    fun `pending and cancelled are results`() {
        assertEquals(GalvaPurchaseResult.Pending, purchase(billing(BillingLaunchState.Pending("pro", "t"))))
        assertEquals(GalvaPurchaseResult.Cancelled, purchase(billing(BillingLaunchState.Cancelled("pro", "t"))))
    }

    @Test
    fun `failures are thrown as GalvaPurchaseException`() {
        val offerMissing = BillingLaunchState.Failed("pro", "intro", "Offer not found", kind = FailureKind.OFFER_NOT_FOUND)

        assertThrows(GalvaPurchaseException.IneligibleForOffer::class.java) { purchase(billing(offerMissing)) }
        assertThrows(GalvaPurchaseException.ProductUnavailable::class.java) {
            purchase(billing(offerMissing), offerId = null)
        }
    }
}
