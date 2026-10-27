package io.galva.sample.e2e

import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.Stage
import io.galva.sample.MainActivity
import io.galva.sdk.Galva
import io.galva.sdk.GalvaPurchaseController
import io.galva.sdk.GalvaPurchaseException
import io.galva.sdk.GalvaPurchaseRequest
import io.galva.sdk.GalvaPurchaseResult
import io.galva.sdk.GalvaTransaction
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A purchase the page requests runs through the app's `GalvaPurchaseController` (the seam the
 * RevenueCat integration plugs into), and its answer reaches the page as for the built-in flow.
 */
@RunWith(AndroidJUnit4::class)
class PurchaseControllerE2ETest {
    @get:Rule
    val activities = ActivityTracker()

    @After
    fun restoreBuiltInFlow() = Galva.instance.setPurchaseController(null)

    private class RecordingController(private val answer: (GalvaPurchaseRequest) -> GalvaPurchaseResult) :
        GalvaPurchaseController {
        val requests = CopyOnWriteArrayList<GalvaPurchaseRequest>()
        val onMainThread = CopyOnWriteArrayList<Boolean>()

        override suspend fun purchase(request: GalvaPurchaseRequest): GalvaPurchaseResult {
            requests += request
            onMainThread += Looper.myLooper() == Looper.getMainLooper()
            return answer(request)
        }
    }

    /** Shows a message, taps its buy button and returns what the page received. */
    private fun buyFromMessage(communicationId: String): JSONObject {
        MockBackend.queueCommunication(communicationId)
        ControlPanel.checkForMessages()
        activities.awaitStage(InAppMessage.ACTIVITY, Stage.RESUMED)
        InAppMessage.awaitBundleReport(communicationId)

        InAppMessage.buyFromPage()
        val report = InAppMessage.awaitPurchaseReport(communicationId)

        InAppMessage.closeFromPage()
        activities.awaitStage(InAppMessage.ACTIVITY, Stage.DESTROYED)
        return report
    }

    @Test
    fun messagePurchase_runsThroughTheInstalledController() {
        val controller = RecordingController {
            GalvaPurchaseResult.Completed(GalvaTransaction("GPA.e2e", it.productId, "token-e2e", 0L, it.obfuscatedAccountId))
        }
        Galva.instance.setPurchaseController(controller)
        val communicationId = uniqueId("buy")

        val report = ActivityScenario.launch(MainActivity::class.java).use { buyFromMessage(communicationId) }

        val result = report.getJSONObject("result")
        assertEquals("completed", result.getString("outcome"))
        val transaction = result.getJSONObject("transaction")
        assertEquals("GPA.e2e", transaction.getString("id"))
        assertEquals("e2e_pro", transaction.getString("productId"))
        assertEquals("monthly", transaction.getString("basePlanId"))
        assertEquals("intro", transaction.getString("offerId"))

        val request = controller.requests.single()
        assertEquals(listOf("e2e_pro", "monthly", "intro"), listOf(request.productId, request.basePlanId, request.offerId))
        assertEquals(communicationId, request.messageId)
        assertEquals(InAppMessage.ACTIVITY, request.activity.javaClass.name)
        assertTrue("account id missing", request.obfuscatedAccountId.isNotEmpty())
        assertEquals(request.obfuscatedAccountId, transaction.getString("appAccountToken"))
        assertEquals("controller runs on the main thread", listOf(true), controller.onMainThread.toList())
    }

    @Test
    fun controllerFailure_reachesThePageWithItsCode() {
        Galva.instance.setPurchaseController(RecordingController { throw GalvaPurchaseException.IneligibleForOffer() })
        val communicationId = uniqueId("buy-fail")

        val report = ActivityScenario.launch(MainActivity::class.java).use { buyFromMessage(communicationId) }

        assertTrue("no result expected: $report", report.isNull("result"))
        assertEquals("ineligibleForOffer", report.getJSONObject("error").getString("code"))
    }
}
