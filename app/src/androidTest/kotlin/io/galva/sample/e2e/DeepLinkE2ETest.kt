package io.galva.sample.e2e

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.Stage
import io.galva.sample.MainActivity
import io.galva.sdk.Galva
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `gv…://openCommunication?communicationId=…` links, from the intent filter to the rendered page. */
@RunWith(AndroidJUnit4::class)
class DeepLinkE2ETest {
    @get:Rule
    val activities = ActivityTracker()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun deepLinkWhileAnonymous_waitsForIdentify_thenOpensCommunication() {
        val communicationId = MainActivity.SAMPLE_COMMUNICATION_ID
        val userId = uniqueId("deeplink-user")
        ActivityScenario.launch(MainActivity::class.java).use {
            ControlPanel.logout()
            awaitCondition("logout to be processed") { Galva.instance.isAnonymous }

            // Through the intent filter into onNewIntent (singleTop), while still anonymous.
            ControlPanel.openSampleDeepLink()
            ControlPanel.identify(userId)

            // Resolved with the identified user: the link was held until identify, not opened anonymously.
            val resolve = MockBackend.awaitRequest("resolve of $communicationId") {
                it.method == "POST" && it.path == "${MockBackend.COMMUNICATIONS}/$communicationId/resolve"
            }.json()
            assertEquals(userId, resolve.optString("endUserId"))

            activities.awaitStage(InAppMessage.ACTIVITY, Stage.RESUMED)
            val params = InAppMessage.awaitBundleReport(communicationId).getJSONObject("deepLinkParams")
            assertEquals(communicationId, params.getString("communicationId"))
            assertEquals("control-panel", params.getString("source"))

            InAppMessage.closeFromPage()
            activities.awaitStage(InAppMessage.ACTIVITY, Stage.DESTROYED)
            assertEquals("deferred link opened once", 1, InAppMessage.resolveRequests(communicationId).size)
        }
    }

    @Test
    fun coldStartDeepLink_forIdentifiedUser_opensCommunicationWithParams() {
        val communicationId = uniqueId("dl")
        val userId = uniqueId("cold-user")
        val link = Uri.parse("gvdemo://openCommunication?communicationId=$communicationId&campaign=e2e")

        // The manifest routes the link to the control panel.
        val handlers = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, link).setPackage(context.packageName), 0
        ).map { it.activityInfo.name }
        assertEquals(listOf(MainActivity::class.java.name), handlers)

        Galva.instance.identify(userId)
        awaitCondition("identify of $userId to be processed") { Galva.instance.currentUserId == userId }

        val launch = Intent(Intent.ACTION_VIEW, link).setClass(context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(launch).use {
            val resolve = MockBackend.awaitRequest("resolve of $communicationId") {
                it.method == "POST" && it.path == "${MockBackend.COMMUNICATIONS}/$communicationId/resolve"
            }.json()
            assertEquals(userId, resolve.optString("endUserId"))

            activities.awaitStage(InAppMessage.ACTIVITY, Stage.RESUMED)
            val params = InAppMessage.awaitBundleReport(communicationId).getJSONObject("deepLinkParams")
            assertEquals(communicationId, params.getString("communicationId"))
            assertEquals("e2e", params.getString("campaign"))
            assertEquals("only the link's query parameters: $params", 2, params.length())

            InAppMessage.closeFromPage()
            activities.awaitStage(InAppMessage.ACTIVITY, Stage.DESTROYED)
        }
    }
}
