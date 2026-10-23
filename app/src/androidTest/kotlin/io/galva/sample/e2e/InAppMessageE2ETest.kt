package io.galva.sample.e2e

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.Stage
import io.galva.sample.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Poll → resolve → bundle download → full-screen WebView → JS bridge → dismiss, against the mock. */
@RunWith(AndroidJUnit4::class)
class InAppMessageE2ETest {
    @get:Rule
    val activities = ActivityTracker()

    @Test
    fun polledMessage_isShown_bridgeAnswersPage_andDismisses() {
        val communicationId = uniqueId("iam")
        ActivityScenario.launch(MainActivity::class.java).use {
            MockBackend.queueCommunication(communicationId)
            ControlPanel.checkForMessages()

            val resolve = MockBackend.awaitRequest("resolve of $communicationId") {
                it.method == "POST" && it.path == "${MockBackend.COMMUNICATIONS}/$communicationId/resolve"
            }.json()
            assertEquals("android", resolve.getString("devicePlatform"))
            assertTrue("anonymousId missing: $resolve", resolve.optString("anonymousId").isNotEmpty())

            val poll = MockBackend.recordedRequests().first { it.method == "GET" && it.path == MockBackend.COMMUNICATIONS }
            assertEquals("in-app", poll.query["channelType"])

            MockBackend.awaitRequest("bundle download") {
                it.method == "GET" && it.path == "/webview/${MockBackend.webviewVersionFor(communicationId)}.html"
            }
            activities.awaitStage(InAppMessage.ACTIVITY, Stage.RESUMED)

            // The page got the resolved payload over getMessageData and could call apiFetch.
            val report = InAppMessage.awaitBundleReport(communicationId)
            assertEquals(MockBackend.OFFER_TITLE, report.getJSONObject("messageData").getString("title"))
            assertEquals("android", report.optString("platform"))
            val steps = report.getJSONArray("steps").toString()
            assertTrue("bridge steps: $steps", listOf("ready", "getMessageData", "getPageContext").all { it in steps })
            assertTrue("no deep link params expected for a polled message: $report", report.isNull("deepLinkParams"))

            InAppMessage.closeFromPage()
            activities.awaitStage(InAppMessage.ACTIVITY, Stage.DESTROYED)
            assertEquals("message resolved once", 1, InAppMessage.resolveRequests(communicationId).size)
            assertEquals("message shown once", 1, activities.count(InAppMessage.ACTIVITY, Stage.CREATED))
        }
    }
}
