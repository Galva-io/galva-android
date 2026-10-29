package io.galva.sample.e2e

import android.widget.CompoundButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.galva.sample.MainActivity
import io.galva.sample.R
import io.galva.sdk.Galva
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentityE2ETest {

    @After
    fun optBackIn() = Galva.instance.setOptOut(false)

    @Test
    fun firstForeground_sendsSessionStart() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val event = MockBackend.awaitBatchMessage("session_start track event") {
                it.optString("type") == "track" && it.optString("event") == "session_start"
            }
            val properties = event.getJSONObject("properties")
            assertEquals(Galva.sdkVersion, properties.getString("sdk_version"))
            assertTrue("os_version missing: $properties", properties.has("os_version"))
            assertTrue("anonymousId missing: $event", event.optString("anonymousId").isNotEmpty())
        }
    }

    @Test
    fun track_uploadsTrackMessageWithJsonAttributesOnly() {
        val eventName = uniqueId("E2EPurchase")
        ActivityScenario.launch(MainActivity::class.java).use {
            Galva.instance.track(
                eventName,
                mapOf(
                    "sku" to "pro_yearly",
                    "price" to 9.99,
                    "count" to 2,
                    "nested" to mapOf("ok" to true),
                    "unsupported" to Any(),
                ),
            )

            val event = MockBackend.awaitBatchMessage("track of $eventName") {
                it.optString("type") == "track" && it.optString("event") == eventName
            }
            val properties = event.getJSONObject("properties")
            assertEquals("pro_yearly", properties.getString("sku"))
            assertEquals(9.99, properties.getDouble("price"), 0.0)
            assertEquals(2, properties.getInt("count"))
            assertTrue(properties.getJSONObject("nested").getBoolean("ok"))
            assertFalse("non-JSON attribute should be dropped: $properties", properties.has("unsupported"))
            assertTrue("anonymousId missing: $event", event.optString("anonymousId").isNotEmpty())
        }
    }

    @Test
    fun trackFromControlPanel_uploadsDemoEvent() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ControlPanel.trackEvent()

            val event = MockBackend.awaitBatchMessage("DemoButtonTapped track event") {
                it.optString("type") == "track" && it.optString("event") == "DemoButtonTapped"
            }
            assertEquals("control_panel", event.getJSONObject("properties").getString("screen"))
        }
    }

    @Test
    fun identify_fromControlPanel_uploadsIdentifyWithEndUserId() {
        val userId = uniqueId("user")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ControlPanel.identify(userId)

            val message = MockBackend.awaitBatchMessage("identify of $userId") { it.isIdentifyOf(userId) }
            assertTrue("anonymousId missing: $message", message.optString("anonymousId").isNotEmpty())
            assertNotNull("context missing: $message", message.optJSONObject("context"))

            val upload = MockBackend.recordedRequests().first {
                it.path == MockBackend.BATCH_COLLECT && it.body.contains(userId)
            }
            assertEquals(MockBackend.API_KEY, upload.header("X-API-Key"))
            assertEquals("android/${Galva.sdkVersion}", upload.header("x-sdk-version"))

            awaitCondition("SDK to report $userId as the current user") { Galva.instance.currentUserId == userId }
            assertFalse(Galva.instance.isAnonymous)
            awaitCondition("control panel to show $userId") {
                var text = ""
                scenario.onActivity { text = it.findViewById<TextView>(R.id.status).text.toString() }
                text.contains(userId)
            }
        }
    }

    @Test
    fun logoutBeforeUpload_stillSendsTheSignedOutUsersCalls_alongWithTheNextUsers() {
        val firstUser = uniqueId("first-user")
        val nextUser = uniqueId("next-user")
        val firstUsersEvent = uniqueId("FirstUserEvent")
        ActivityScenario.launch(MainActivity::class.java).use {
            // Back to back, well inside the queue's batching window: the first user's calls are still
            // queued when logout and the next identify run.
            Galva.instance.identify(firstUser)
            Galva.instance.track(firstUsersEvent)
            Galva.instance.logout()
            Galva.instance.identify(nextUser)

            val next = MockBackend.awaitBatchMessage("identify of $nextUser") { it.isIdentifyOf(nextUser) }
            // FIFO queue: the first user's calls were uploaded no later than the next user's.
            val first = MockBackend.batchMessages().firstOrNull { it.isIdentifyOf(firstUser) }
            assertNotNull("identify of $firstUser dropped by the logout", first)
            val event = MockBackend.batchMessages().firstOrNull {
                it.optString("type") == "track" && it.optString("event") == firstUsersEvent
            }
            assertNotNull("track of $firstUsersEvent dropped by the logout", event)

            // Each call kept the identity it was made as.
            assertEquals(firstUser, event!!.optString("endUserId"))
            assertEquals(first!!.optString("anonymousId"), event.optString("anonymousId"))
            assertNotEquals(first.optString("anonymousId"), next.optString("anonymousId"))
        }
    }

    @Test
    fun setEmail_fromControlPanel_uploadsEmailTrait() {
        val email = "${uniqueId("e2e")}@example.com"
        ActivityScenario.launch(MainActivity::class.java).use {
            ControlPanel.setEmail(email)

            MockBackend.awaitBatchMessage("identify with \$gv_email $email") {
                it.optString("type") == "identify" && it.optJSONObject("traits")?.optString("\$gv_email") == email
            }
        }
    }

    @Test
    fun optOut_dropsIdentify_untilOptedBackIn() {
        val optedOutUser = uniqueId("opted-out")
        val sentinelUser = uniqueId("sentinel")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Opting out purges undelivered events; get earlier tests' events out first.
            drainSdkQueue()

            ControlPanel.toggleOptOut()
            assertTrue("switch should opt out", Galva.instance.isOptedOut)
            awaitCondition("opt-out switch to show the opted-out state") {
                var checked = false
                scenario.onActivity { checked = it.findViewById<CompoundButton>(R.id.opt_out_switch).isChecked }
                checked
            }

            val userBefore = Galva.instance.currentUserId
            ControlPanel.identify(optedOutUser)
            // Calls run in order: once the logout after it took effect, the identify was handled (and
            // dropped) while opted out. Toggling back immediately is covered by the next test.
            ControlPanel.logout()
            awaitCondition("logout to be processed") {
                Galva.instance.isAnonymous && Galva.instance.currentUserId != userBefore
            }

            ControlPanel.toggleOptOut()
            assertFalse("switch should opt back in", Galva.instance.isOptedOut)
            ControlPanel.identify(sentinelUser)
            MockBackend.awaitBatchMessage("identify of $sentinelUser after opting back in") { it.isIdentifyOf(sentinelUser) }

            // FIFO queue: anything recorded before the sentinel has been uploaded by now.
            val leaked = MockBackend.batchMessages().filter { it.isIdentifyOf(optedOutUser) }
            assertTrue("identify sent while opted out: $leaked", leaked.isEmpty())
        }
    }

    @Test
    fun optOut_appliesInCallOrder_evenWhenToggledBackImmediately() {
        val optedOutUser = uniqueId("opted-out-fast")
        val sentinelUser = uniqueId("sentinel-fast")
        ActivityScenario.launch(MainActivity::class.java).use {
            drainSdkQueue()

            // No barrier: the identify made while opted out must be dropped even though opt-out is
            // lifted before the SDK queue gets to it.
            Galva.instance.setOptOut(true)
            Galva.instance.identify(optedOutUser)
            Galva.instance.setOptOut(false)
            Galva.instance.identify(sentinelUser)
            MockBackend.awaitBatchMessage("identify of $sentinelUser after opting back in") { it.isIdentifyOf(sentinelUser) }

            val leaked = MockBackend.batchMessages().filter { it.isIdentifyOf(optedOutUser) }
            assertTrue("identify sent while opted out: $leaked", leaked.isEmpty())
        }
    }
}
