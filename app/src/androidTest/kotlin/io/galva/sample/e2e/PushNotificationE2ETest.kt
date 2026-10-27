package io.galva.sample.e2e

import android.Manifest
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import io.galva.sample.MainActivity
import io.galva.sdk.Galva
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Galva pushes rendered by the SDK, tracked as `$gv_notification_*` events like the iOS SDK. */
@RunWith(AndroidJUnit4::class)
class PushNotificationE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @After
    fun cleanUp() {
        device.pressHome()
        notificationManager.cancelAll()
    }

    @Test
    fun push_isPresented_thenTapOpensTheAppAndIsTracked() {
        val push = pushFor(uniqueId("push-tap"))
        val communicationId = push.getValue("communicationId")
        ActivityScenario.launch(MainActivity::class.java).use {
            Galva.instance.handlePush(push)

            val presented = MockBackend.awaitBatchMessage("presented of $communicationId") {
                it.isNotificationEvent(PRESENTED, communicationId)
            }
            assertEquals("e2e", presented.getJSONObject("properties").getString("campaign"))
            assertEquals(push.getValue("title"), presented.getJSONObject("properties").getString("title"))
            awaitPosted(communicationId)

            val launches = ActivityStartRecorder()
            instrumentation.addMonitor(launches)
            try {
                // Tap it from the notification shade, like a user.
                device.openNotification()
                awaitValue("notification \"${push.getValue("title")}\" in the shade", UI_TIMEOUT_MS) {
                    device.findObject(By.text(push.getValue("title")))
                }.click()

                MockBackend.awaitBatchMessage("tap of $communicationId") { it.isNotificationEvent(TAPPED, communicationId) }
                // The SDK opens the launcher activity with the push data as extras (onNewIntent here,
                // as the demo activity is singleTop).
                val launch = awaitValue("launcher activity opened with the push data") {
                    launches.started.firstOrNull { it.getStringExtra("communicationId") == communicationId }
                }
                assertEquals(push.getValue("campaign"), launch.getStringExtra("campaign"))
            } finally {
                instrumentation.removeMonitor(launches)
            }
        }
    }

    @Test
    fun dismissedPush_isTracked() {
        val push = pushFor(uniqueId("push-dismiss"))
        val communicationId = push.getValue("communicationId")
        ActivityScenario.launch(MainActivity::class.java).use {
            Galva.instance.handlePush(push)
            val posted = awaitPosted(communicationId)

            // What the system sends when the user swipes the notification away.
            posted.notification.deleteIntent.send()

            val dismissed = MockBackend.awaitBatchMessage("dismissal of $communicationId") {
                it.isNotificationEvent(DISMISSED, communicationId)
            }
            assertEquals("e2e", dismissed.getJSONObject("properties").getString("campaign"))
        }
    }

    private fun pushFor(communicationId: String) = mapOf(
        "communicationId" to communicationId,
        "title" to "E2E $communicationId",
        "body" to "Your trial ends today",
        "campaign" to "e2e",
    )

    private fun awaitPosted(communicationId: String): StatusBarNotification =
        awaitValue("notification for $communicationId to be posted") {
            notificationManager.activeNotifications.firstOrNull { it.id == communicationId.hashCode() }
        }

    /** Records every activity start made from the app process, without intercepting it. */
    private class ActivityStartRecorder : Instrumentation.ActivityMonitor() {
        val started = CopyOnWriteArrayList<Intent>()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            started += Intent(intent)
            return null
        }
    }

    private fun JSONObject.isNotificationEvent(event: String, communicationId: String) =
        optString("type") == "track" && optString("event") == event &&
            optJSONObject("properties")?.optString("id") == communicationId

    private companion object {
        const val PRESENTED = "\$gv_notification_presented"
        const val TAPPED = "\$gv_notification_tapped"
        const val DISMISSED = "\$gv_notification_dismissed"
        const val UI_TIMEOUT_MS = 15_000L
    }
}
